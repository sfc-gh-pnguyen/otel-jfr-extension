package com.otel.jfr;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.metrics.LongCounter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;

/**
 * Aggregates execution samples into "folded stacks" ({@code outer;...;leaf}
 * plus a sample count) and flushes one log record per distinct stack per
 * interval -- the standard flame-graph input. Backends can then build a flame
 * graph, top call paths, and self/total time per method with plain group-bys,
 * which the one-trace-per-sample span output cannot do cheaply.
 *
 * <p>Also maintains {@code jfr.jvm.execution_sample.count}, a counter of
 * samples by leaf (on-CPU) method -- a direct "self time" signal. Its method
 * cardinality is capped; methods beyond the cap are counted as {@code other}.
 *
 * <p>Runs alongside the span or profile output; it never replaces either.
 */
final class JfrStackSummary {

  private static final java.util.logging.Logger LOG =
      java.util.logging.Logger.getLogger(JfrStackSummary.class.getName());

  private static final AttributeKey<String> EVENT_NAME = AttributeKey.stringKey("event.name");
  private static final AttributeKey<String> FOLDED = AttributeKey.stringKey("jfr.stack.folded");
  private static final AttributeKey<String> LEAF = AttributeKey.stringKey("jfr.stack.leaf");
  private static final AttributeKey<String> ROOT = AttributeKey.stringKey("jfr.stack.root");
  private static final AttributeKey<Long> DEPTH = AttributeKey.longKey("jfr.stack.depth");
  private static final AttributeKey<Long> COUNT = AttributeKey.longKey("jfr.sample.count");
  private static final AttributeKey<String> SAMPLE_TYPE = AttributeKey.stringKey("jfr.sample.type");
  private static final AttributeKey<String> THREAD_NAME = AttributeKey.stringKey("jfr.thread.name");
  private static final AttributeKey<Long> INTERVAL_MS = AttributeKey.longKey("jfr.interval.ms");
  private static final AttributeKey<String> CODE_FUNCTION = AttributeKey.stringKey("code.function");

  /** Frames kept per stack (outermost callers are dropped beyond this). */
  private static final int MAX_FRAMES = 64;

  /** Distinct stacks kept per interval; the rest are counted as truncated. */
  private static final int MAX_STACKS_PER_INTERVAL = 5000;

  /** Distinct leaf methods tracked on the counter before folding into "other". */
  private static final int MAX_LEAF_METHODS = 200;

  private static final String TRUNCATED = "[truncated]";

  private final Logger logger;
  private final LongCounter sampleCounter;
  private final ThreadNameNormalizer normalizer;
  private final long intervalMs;

  private volatile Map<StackKey, LongAdder> current = new ConcurrentHashMap<>();
  private final Set<String> knownLeaves = ConcurrentHashMap.newKeySet();
  private volatile boolean emitErrorReported = false;

  JfrStackSummary(OpenTelemetry openTelemetry, boolean normalizeThreadNames, long intervalMs) {
    Logger lg = null;
    try {
      lg = openTelemetry.getLogsBridge().get("com.otel.jfr");
    } catch (RuntimeException | LinkageError t) {
      LOG.log(
          java.util.logging.Level.WARNING,
          "otel-jfr-extension: logs bridge unavailable; folded-stack logs disabled,"
              + " sample counter unaffected",
          t);
    }
    this.logger = lg;
    this.sampleCounter =
        openTelemetry
            .getMeter("com.otel.jfr")
            .counterBuilder("jfr.jvm.execution_sample.count")
            .setUnit("{sample}")
            .setDescription(
                "Execution samples by leaf (on-CPU) method, from jdk.ExecutionSample /"
                    + " jdk.NativeMethodSample")
            .build();
    this.normalizer = normalizeThreadNames ? new ThreadNameNormalizer() : null;
    this.intervalMs = intervalMs;
  }

  void onSample(RecordedEvent event) {
    RecordedStackTrace stackTrace = event.getStackTrace();
    if (stackTrace == null) {
      return;
    }
    List<RecordedFrame> frames = stackTrace.getFrames();
    if (frames.isEmpty()) {
      return;
    }
    String sampleType =
        "jdk.NativeMethodSample".equals(event.getEventType().getName()) ? "native" : "java";
    String thread = threadName(event);

    // JFR frames are innermost-first; keep the innermost MAX_FRAMES and fold
    // them outermost-first, as flame-graph tooling expects.
    int n = Math.min(frames.size(), MAX_FRAMES);
    StringBuilder folded = new StringBuilder(n * 32);
    for (int i = n - 1; i >= 0; i--) {
      if (folded.length() > 0) {
        folded.append(';');
      }
      folded.append(frameName(frames.get(i)));
    }
    String leaf = frameName(frames.get(0));
    String root = frameName(frames.get(n - 1));

    Map<StackKey, LongAdder> map = current;
    StackKey key = new StackKey(folded.toString(), leaf, root, n, thread, sampleType);
    LongAdder adder = map.get(key);
    if (adder == null) {
      if (map.size() >= MAX_STACKS_PER_INTERVAL) {
        key = new StackKey(TRUNCATED, TRUNCATED, TRUNCATED, 0, thread, sampleType);
      }
      adder = map.computeIfAbsent(key, k -> new LongAdder());
    }
    adder.increment();

    String counted = leaf;
    if (!knownLeaves.contains(leaf)) {
      if (knownLeaves.size() < MAX_LEAF_METHODS) {
        knownLeaves.add(leaf);
      } else {
        counted = "other";
      }
    }
    sampleCounter.add(
        1, Attributes.of(CODE_FUNCTION, counted, THREAD_NAME, thread, SAMPLE_TYPE, sampleType));
  }

  /** Emits and resets the current interval's folded stacks. */
  void flush() {
    Map<StackKey, LongAdder> map = current;
    current = new ConcurrentHashMap<>();
    if (logger == null || map.isEmpty()) {
      return;
    }
    Instant now = Instant.now();
    try {
      for (Map.Entry<StackKey, LongAdder> e : map.entrySet()) {
        StackKey k = e.getKey();
        logger
            .logRecordBuilder()
            .setTimestamp(now)
            .setSeverity(Severity.INFO)
            .setBody(k.folded)
            .setAttribute(EVENT_NAME, "jfr.stack.summary")
            .setAttribute(FOLDED, k.folded)
            .setAttribute(LEAF, k.leaf)
            .setAttribute(ROOT, k.root)
            .setAttribute(DEPTH, (long) k.depth)
            .setAttribute(COUNT, e.getValue().sum())
            .setAttribute(THREAD_NAME, k.thread)
            .setAttribute(SAMPLE_TYPE, k.sampleType)
            .setAttribute(INTERVAL_MS, intervalMs)
            .emit();
      }
    } catch (RuntimeException ex) {
      if (!emitErrorReported) {
        emitErrorReported = true;
        LOG.log(
            java.util.logging.Level.WARNING,
            "otel-jfr-extension: folded-stack log emission failed; further failures suppressed",
            ex);
      }
    }
  }

  private static String frameName(RecordedFrame frame) {
    RecordedMethod method = frame.getMethod();
    if (method == null) {
      return "unknown";
    }
    String type = method.getType() != null ? method.getType().getName() : "unknown";
    return type + "." + method.getName();
  }

  private String threadName(RecordedEvent event) {
    RecordedThread thread = null;
    // jdk.ExecutionSample / NativeMethodSample carry the profiled thread here.
    if (event.hasField("sampledThread")) {
      thread = event.getThread("sampledThread");
    }
    if (thread == null) {
      thread = event.getThread();
    }
    if (thread == null) {
      return "unknown";
    }
    String name = thread.getJavaName() != null ? thread.getJavaName() : thread.getOSName();
    if (name == null) {
      return "unknown";
    }
    return normalizer != null ? normalizer.normalize(name) : name;
  }

  private static final class StackKey {
    final String folded;
    final String leaf;
    final String root;
    final int depth;
    final String thread;
    final String sampleType;
    private final int hash;

    StackKey(String folded, String leaf, String root, int depth, String thread, String sampleType) {
      this.folded = folded;
      this.leaf = leaf;
      this.root = root;
      this.depth = depth;
      this.thread = thread;
      this.sampleType = sampleType;
      this.hash = java.util.Objects.hash(folded, thread, sampleType);
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof StackKey)) {
        return false;
      }
      StackKey k = (StackKey) o;
      return folded.equals(k.folded) && thread.equals(k.thread) && sampleType.equals(k.sampleType);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }
}

package com.otel.jfr;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;

/**
 * Aggregation mode: buffers execution samples per thread over the flush window
 * and merges them into one weighted call tree before emitting anything.
 * Node identity is the frame name along the path from
 * the root; weight is the sample count, never a duration.
 *
 * <p>Per window it emits:
 *
 * <ul>
 *   <li>one {@code jfr.flame.node} log record per tree node --
 *       {@code thread | frame | parent_frame | value} plus path, depth and
 *       self count -- so a dashboard can read the tree with no OPAL re-aggregation;
 *   <li>optionally, one merged trace per thread whose span widths are
 *       proportional to sample count, so Trace Explorer's flame chart draws
 *       an aggregated flame graph for the window.
 * </ul>
 *
 * <p>Replaces the one-trace-per-sample span output when enabled.
 */
final class JfrFlameAggregator {

  private static final java.util.logging.Logger LOG =
      java.util.logging.Logger.getLogger(JfrFlameAggregator.class.getName());

  private static final AttributeKey<String> EVENT_NAME = AttributeKey.stringKey("event.name");
  private static final AttributeKey<String> THREAD_NAME = AttributeKey.stringKey("jfr.thread.name");
  private static final AttributeKey<String> SAMPLE_TYPE = AttributeKey.stringKey("jfr.sample.type");
  private static final AttributeKey<String> FRAME = AttributeKey.stringKey("jfr.flame.frame");
  private static final AttributeKey<String> PARENT = AttributeKey.stringKey("jfr.flame.parent_frame");
  private static final AttributeKey<String> PATH = AttributeKey.stringKey("jfr.flame.path");
  private static final AttributeKey<Long> DEPTH = AttributeKey.longKey("jfr.flame.depth");
  private static final AttributeKey<Long> VALUE = AttributeKey.longKey("jfr.flame.value");
  private static final AttributeKey<Long> SELF = AttributeKey.longKey("jfr.flame.self_value");
  private static final AttributeKey<Long> TOTAL = AttributeKey.longKey("jfr.flame.thread_total");
  private static final AttributeKey<Long> INTERVAL_MS = AttributeKey.longKey("jfr.interval.ms");
  private static final AttributeKey<String> CODE_FUNCTION = AttributeKey.stringKey("code.function");
  private static final AttributeKey<Boolean> SYNTHETIC_DURATION =
      AttributeKey.booleanKey("jfr.synthetic_duration");
  private static final AttributeKey<Boolean> AGGREGATED = AttributeKey.booleanKey("jfr.aggregated");

  /** Frames kept per sample (outermost callers dropped beyond this). */
  private static final int MAX_FRAMES = 64;

  /** Tree nodes kept per window across all threads; deeper new paths stop growing beyond it. */
  private static final int MAX_NODES_PER_WINDOW = 20000;

  private final Logger logger;
  private final Tracer tracer;
  private final boolean tracesEnabled;
  private final long maxTraceSpans;
  private final long samplingPeriodMs;
  private final long intervalMs;
  private final ThreadNameNormalizer normalizer;

  private final Object lock = new Object();
  private Map<String, Node> roots = new HashMap<>();
  private int nodeCount = 0;
  private Instant windowStart = Instant.now();
  private volatile boolean emitErrorReported = false;

  JfrFlameAggregator(
      OpenTelemetry openTelemetry,
      boolean normalizeThreadNames,
      long intervalMs,
      long samplingPeriodMs,
      boolean tracesEnabled,
      long maxTraceSpans) {
    Logger lg = null;
    try {
      lg = openTelemetry.getLogsBridge().get("com.otel.jfr");
    } catch (RuntimeException | LinkageError t) {
      LOG.log(
          java.util.logging.Level.WARNING,
          "otel-jfr-extension: logs bridge unavailable; jfr.flame.node logs disabled",
          t);
    }
    this.logger = lg;
    this.tracer = openTelemetry.getTracer("com.otel.jfr");
    this.tracesEnabled = tracesEnabled;
    this.maxTraceSpans = Math.max(0L, maxTraceSpans);
    this.samplingPeriodMs = Math.max(1L, samplingPeriodMs);
    this.intervalMs = intervalMs;
    this.normalizer = normalizeThreadNames ? new ThreadNameNormalizer() : null;
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
    int n = Math.min(frames.size(), MAX_FRAMES);

    synchronized (lock) {
      String rootKey = thread + '\u0000' + sampleType;
      Node node = roots.get(rootKey);
      if (node == null) {
        node = new Node(thread, null, sampleType);
        roots.put(rootKey, node);
      }
      node.value++;
      // JFR frames are innermost-first; walk outermost -> leaf.
      for (int i = n - 1; i >= 0; i--) {
        String name = frameName(frames.get(i));
        Node child = node.children.get(name);
        if (child == null) {
          if (nodeCount >= MAX_NODES_PER_WINDOW) {
            break; // stop descending; counted as self time of the deepest kept node
          }
          child = new Node(name, node, sampleType);
          node.children.put(name, child);
          nodeCount++;
        }
        child.value++;
        node = child;
      }
      node.self++;
    }
  }

  /** Emits and resets the current window's trees. */
  void flush() {
    Map<String, Node> snapshot;
    Instant start;
    synchronized (lock) {
      snapshot = roots;
      start = windowStart;
      roots = new HashMap<>();
      nodeCount = 0;
      windowStart = Instant.now();
    }
    if (snapshot.isEmpty()) {
      return;
    }
    Instant now = Instant.now();
    long[] spanBudget = {maxTraceSpans};
    try {
      for (Node threadRoot : snapshot.values()) {
        for (Node top : sorted(threadRoot)) {
          emitLogs(threadRoot, top, "", "", 0, now);
        }
        if (tracesEnabled && spanBudget[0] > 0) {
          emitTrace(threadRoot, start, spanBudget);
        }
      }
    } catch (RuntimeException ex) {
      if (!emitErrorReported) {
        emitErrorReported = true;
        LOG.log(
            java.util.logging.Level.WARNING,
            "otel-jfr-extension: flame aggregate emission failed; further failures suppressed",
            ex);
      }
    }
  }

  private void emitLogs(Node threadRoot, Node node, String parent, String parentPath, int depth,
      Instant now) {
    String path = parentPath.isEmpty() ? node.name : parentPath + ';' + node.name;
    if (logger != null) {
      logger
          .logRecordBuilder()
          .setTimestamp(now)
          .setSeverity(Severity.INFO)
          .setBody(path)
          .setAttribute(EVENT_NAME, "jfr.flame.node")
          .setAttribute(THREAD_NAME, threadRoot.name)
          .setAttribute(SAMPLE_TYPE, threadRoot.sampleType)
          .setAttribute(FRAME, node.name)
          .setAttribute(PARENT, parent)
          .setAttribute(PATH, path)
          .setAttribute(DEPTH, (long) depth)
          .setAttribute(VALUE, node.value)
          .setAttribute(SELF, node.self)
          .setAttribute(TOTAL, threadRoot.value)
          .setAttribute(INTERVAL_MS, intervalMs)
          .emit();
    }
    for (Node child : node.children.values()) {
      emitLogs(threadRoot, child, node.name, path, depth + 1, now);
    }
  }

  /**
   * One trace per thread per window. Each span lasts value x sampling period and
   * children are laid out back-to-back, heaviest first, so the trace flame chart
   * renders the merged tree with widths proportional to sample count.
   */
  private void emitTrace(Node threadRoot, Instant start, long[] budget) {
    long startNanos = toNanos(start);
    budget[0]--;
    Span root =
        tracer
            .spanBuilder("jfr.flame: " + threadRoot.name)
            .setParent(Context.root())
            .setSpanKind(SpanKind.INTERNAL)
            .setStartTimestamp(startNanos, TimeUnit.NANOSECONDS)
            .setAttribute(THREAD_NAME, threadRoot.name)
            .setAttribute(SAMPLE_TYPE, threadRoot.sampleType)
            .setAttribute(VALUE, threadRoot.value)
            .setAttribute(INTERVAL_MS, intervalMs)
            .setAttribute(AGGREGATED, true)
            .setAttribute(SYNTHETIC_DURATION, true)
            .startSpan();
    emitChildren(threadRoot, root, startNanos, threadRoot.value, budget);
    root.end(startNanos + width(threadRoot.value), TimeUnit.NANOSECONDS);
  }

  private void emitChildren(Node node, Span parentSpan, long offsetNanos, long total, long[] budget) {
    long cursor = offsetNanos;
    Context parentCtx = Context.root().with(parentSpan);
    for (Node child : sorted(node)) {
      if (budget[0] <= 0) {
        return;
      }
      budget[0]--;
      Span span =
          tracer
              .spanBuilder(child.name)
              .setParent(parentCtx)
              .setSpanKind(SpanKind.INTERNAL)
              .setStartTimestamp(cursor, TimeUnit.NANOSECONDS)
              .setAttribute(CODE_FUNCTION, child.name)
              .setAttribute(FRAME, child.name)
              .setAttribute(VALUE, child.value)
              .setAttribute(SELF, child.self)
              .setAttribute(TOTAL, total)
              .setAttribute(AGGREGATED, true)
              .setAttribute(SYNTHETIC_DURATION, true)
              .startSpan();
      emitChildren(child, span, cursor, total, budget);
      cursor += width(child.value);
      span.end(cursor, TimeUnit.NANOSECONDS);
    }
  }

  private long width(long samples) {
    return TimeUnit.MILLISECONDS.toNanos(samples * samplingPeriodMs);
  }

  private static List<Node> sorted(Node node) {
    List<Node> list = new ArrayList<>(node.children.values());
    list.sort((a, b) -> Long.compare(b.value, a.value));
    return list;
  }

  private static long toNanos(Instant i) {
    return TimeUnit.SECONDS.toNanos(i.getEpochSecond()) + i.getNano();
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

  private static final class Node {
    final String name;
    final Node parent;
    final String sampleType;
    final Map<String, Node> children = new HashMap<>();
    long value;
    long self;

    Node(String name, Node parent, String sampleType) {
      this.name = name;
      this.parent = parent;
      this.sampleType = sampleType;
    }
  }
}

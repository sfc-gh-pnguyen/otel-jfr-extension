package com.otel.jfr;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;

/**
 * Converts {@code jdk.ExecutionSample} stack samples into OTLP spans that render
 * as a flame/waterfall in a trace viewer.
 *
 * <p>This is materially more accurate than the repo's Python {@code jfr.py}
 * equivalent. That converter only ever saw New Relic's already-merged
 * {@code JfrFlameLevel} output, so it had to reconstruct a call tree from
 * parent-name references and invent span widths via an icicle layout. Here we
 * read the raw JDK event, which gives us:
 *
 * <ul>
 *   <li>a real per-sample timestamp ({@link RecordedEvent#getStartTime()}),
 *   <li>the actual ordered stack trace for that instant, and
 *   <li>real elapsed time between consecutive samples for the same thread.
 * </ul>
 *
 * <p>Layout: one trace per stack sample. The frames of a single sample form a
 * strict linear chain (outermost caller to innermost callee) -- a point-in-time
 * stack has no branching -- so each frame nests inside its caller and shares the
 * sample's interval. The interval is the real gap to that thread's previous
 * sample, clamped to a sane maximum so an idle thread that has not been sampled
 * for minutes does not produce an absurdly long span.
 *
 * <p>Every span still carries {@code jfr.synthetic_duration=true}: the outer
 * interval is real elapsed time, but attributing that whole interval to this
 * exact stack is a sampling inference, not a measured per-frame duration. Do not
 * sum these durations as if they were latency.
 */
final class JfrSpanMapper {

  // Deliberately NOT "thread.name": the agent's span processor auto-stamps
  // every span with the *current* (JFR dispatch) thread's name, which would
  // clobber the sampled thread's identity. Use a distinct key so per-thread
  // profiling analysis reflects the thread that was actually sampled.
  private static final AttributeKey<String> THREAD_NAME =
      AttributeKey.stringKey("jfr.thread.name");
  private static final AttributeKey<String> CODE_NAMESPACE =
      AttributeKey.stringKey("code.namespace");
  private static final AttributeKey<String> CODE_FUNCTION = AttributeKey.stringKey("code.function");
  private static final AttributeKey<Long> CODE_LINENO = AttributeKey.longKey("code.lineno");
  private static final AttributeKey<Boolean> SYNTHETIC_DURATION =
      AttributeKey.booleanKey("jfr.synthetic_duration");
  private static final AttributeKey<Long> STACK_DEPTH = AttributeKey.longKey("jfr.stack.depth");
  private static final AttributeKey<Long> FRAME_INDEX = AttributeKey.longKey("jfr.frame.index");
  private static final AttributeKey<String> FRAME_TYPE = AttributeKey.stringKey("jfr.frame.type");

  /** Fallback interval for a thread's first-ever sample. */
  private static final Duration DEFAULT_INTERVAL = Duration.ofMillis(20);

  /** Cap so a long-idle thread does not yield a multi-minute span. */
  private static final Duration MAX_INTERVAL = Duration.ofSeconds(1);

  /** Frames deeper than this are dropped to bound span volume per sample. */
  private static final int MAX_FRAMES = 64;

  private final Tracer tracer;

  /** When true, also emit each sample's stack as a span-correlated log record. */
  private final boolean stackLogEnabled;

  private final Logger stackLogger;

  /** Diagnostic logger; reports the first stack-log emission failure, if any. */
  private static final java.util.logging.Logger LOG =
      java.util.logging.Logger.getLogger(JfrSpanMapper.class.getName());

  /** Ensures a stack-log emission failure is reported at most once. */
  private volatile boolean stackLogErrorReported = false;

  /** Per-thread timestamp of the previous sample, for real interval computation. */
  private final Map<String, Instant> lastSampleByThread = new ConcurrentHashMap<>();

  JfrSpanMapper(OpenTelemetry openTelemetry, boolean stackLogEnabled) {
    this.tracer = openTelemetry.getTracer("com.otel.jfr");
    Logger lg = null;
    boolean enabled = stackLogEnabled;
    if (enabled) {
      try {
        lg = openTelemetry.getLogsBridge().get("com.otel.jfr");
      } catch (RuntimeException | LinkageError t) {
        // Never let the logs subsystem prevent span emission: degrade to spans-only.
        enabled = false;
        LOG.log(
            java.util.logging.Level.WARNING,
            "otel-jfr-extension: logs bridge unavailable; stack-log disabled,"
                + " JFR spans unaffected",
            t);
      }
    }
    this.stackLogger = lg;
    this.stackLogEnabled = enabled;
  }

  void onExecutionSample(RecordedEvent event) {
    RecordedStackTrace stackTrace = event.getStackTrace();
    if (stackTrace == null) {
      return;
    }
    List<RecordedFrame> frames = stackTrace.getFrames();
    if (frames.isEmpty()) {
      return;
    }

    String thread = threadName(event);
    Instant sampleTime = event.getStartTime();
    Instant end = sampleTime.plus(intervalFor(thread, sampleTime));

    // JFR returns frames innermost-first; reverse for caller -> callee nesting.
    int frameCount = Math.min(frames.size(), MAX_FRAMES);

    Span rootSpan =
        tracer
            .spanBuilder("jfr.stack.sample: " + thread)
            .setSpanKind(SpanKind.INTERNAL)
            .setNoParent()
            .setStartTimestamp(sampleTime)
            .setAttribute(THREAD_NAME, thread)
            .setAttribute(SYNTHETIC_DURATION, true)
            .setAttribute(STACK_DEPTH, (long) frames.size())
            .startSpan();

    Context parentContext = Context.root().with(rootSpan);
    Span[] spans = new Span[frameCount];

    for (int i = 0; i < frameCount; i++) {
      // frames.size() - 1 is the outermost caller (e.g. Thread.run).
      RecordedFrame frame = frames.get(frames.size() - 1 - i);
      Span span = spanForFrame(frame, i, parentContext, sampleTime, thread);
      spans[i] = span;
      parentContext = parentContext.with(span);
    }

    // End innermost-first so children always close before their parents.
    for (int i = frameCount - 1; i >= 0; i--) {
      spans[i].end(end);
    }
    rootSpan.end(end);

    // Emit the stack log LAST and defensively. Span export is the primary
    // output; building/emitting the log must never leave the root span unended
    // (that previously suppressed ALL JFR spans) or abort the child spans. Use a
    // fresh root context -- parentContext was reassigned to the deepest child
    // during nesting above, so it no longer points at the root span.
    if (stackLogEnabled) {
      try {
        emitStackLog(frames, frameCount, Context.root().with(rootSpan), sampleTime, thread);
      } catch (RuntimeException e) {
        if (!stackLogErrorReported) {
          stackLogErrorReported = true;
          LOG.log(
              java.util.logging.Level.WARNING,
              "otel-jfr-extension: stack-log emission failed; JFR spans unaffected,"
                  + " further failures suppressed",
              e);
        }
      }
    }
  }

  private Span spanForFrame(
      RecordedFrame frame, int depth, Context parent, Instant start, String thread) {
    RecordedMethod method = frame.getMethod();
    String className = "unknown";
    String methodName = "unknown";
    if (method != null) {
      RecordedClass type = method.getType();
      if (type != null && type.getName() != null) {
        className = type.getName();
      }
      if (method.getName() != null) {
        methodName = method.getName();
      }
    }

    int line = frame.getLineNumber();

    // Include the source line in the span name (e.g. jfr.Http11Processor.service:399)
    // for parity with the JFR flamegraph, which labels frames by call site.
    String spanName = "jfr." + className + "." + methodName;
    if (line > 0) {
      spanName = spanName + ":" + line;
    }

    var builder =
        tracer
            .spanBuilder(spanName)
            .setSpanKind(SpanKind.INTERNAL)
            .setParent(parent)
            .setStartTimestamp(start)
            .setAttribute(CODE_NAMESPACE, className)
            .setAttribute(CODE_FUNCTION, methodName)
            .setAttribute(FRAME_INDEX, (long) depth)
            .setAttribute(THREAD_NAME, thread)
            .setAttribute(SYNTHETIC_DURATION, true);

    if (line > 0) {
      builder.setAttribute(CODE_LINENO, (long) line);
    }
    String frameType = frame.getType();
    if (frameType != null) {
      builder.setAttribute(FRAME_TYPE, frameType);
    }
    return builder.startSpan();
  }

  /**
   * Real elapsed time since this thread's previous sample, clamped to {@link
   * #MAX_INTERVAL}, defaulting to {@link #DEFAULT_INTERVAL} for a first sample.
   */
  private Duration intervalFor(String thread, Instant sampleTime) {
    Instant previous = lastSampleByThread.put(thread, sampleTime);
    if (previous == null) {
      return DEFAULT_INTERVAL;
    }
    Duration gap = Duration.between(previous, sampleTime);
    if (gap.isNegative() || gap.isZero()) {
      return DEFAULT_INTERVAL;
    }
    return gap.compareTo(MAX_INTERVAL) > 0 ? MAX_INTERVAL : gap;
  }

  /**
   * Emit the sample's stack as a single log record correlated (trace id + span
   * id) to the {@code jfr.stack.sample} root span, so it appears under "Logs
   * related to span". Frames are top-of-stack first and formatted like the JFR
   * viewer's stack panel (e.g. {@code java/net/URLClassLoader$1.run:421}).
   */
  private void emitStackLog(
      List<RecordedFrame> frames,
      int frameCount,
      Context spanContext,
      Instant sampleTime,
      String thread) {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < frameCount; i++) {
      // Index 0 is innermost (current) frame -> top of the list, as the viewer shows it.
      if (i > 0) {
        body.append('\n');
      }
      body.append(frameLine(frames.get(i)));
    }
    stackLogger
        .logRecordBuilder()
        .setContext(spanContext)
        .setTimestamp(sampleTime)
        .setSeverity(Severity.INFO)
        .setBody(body.toString())
        .setAttribute(THREAD_NAME, thread)
        .setAttribute(STACK_DEPTH, (long) frames.size())
        .emit();
  }

  /** One JFR-viewer-style frame line, e.g. {@code java/net/URLClassLoader$1.run:421}. */
  private static String frameLine(RecordedFrame frame) {
    RecordedMethod method = frame.getMethod();
    String className = "unknown";
    String methodName = "unknown";
    if (method != null) {
      RecordedClass type = method.getType();
      if (type != null && type.getName() != null) {
        className = type.getName();
      }
      if (method.getName() != null) {
        methodName = method.getName();
      }
    }
    String qualified = className.replace('.', '/') + "." + methodName;
    int lineNumber = frame.getLineNumber();
    return lineNumber > 0 ? qualified + ":" + lineNumber : qualified;
  }

  private static String threadName(RecordedEvent event) {
    // jdk.ExecutionSample stores the profiled thread in "sampledThread"; the
    // generic getThread() reads "eventThread", which is null for samples
    // (committed by the JVM sampler, not the sampled thread) -> "unknown".
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
    String name = thread.getJavaName();
    return name != null ? name : thread.getOSName();
  }
}

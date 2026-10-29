package com.otel.jfr;

import com.otel.jfr.JfrExtensionProvider.JfrSettings;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import io.opentelemetry.proto.resource.v1.Resource;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

/**
 * Owns the {@link RecordingStream} lifecycle and routes each JFR event to the
 * metric, span, or profile mapper.
 *
 * <p>Metrics are always emitted. Execution samples become either trace spans
 * (default) or an OTLP profiles signal, depending on {@code
 * otel.instrumentation.jfr.profile}. In profile mode a scheduled task flushes
 * the accumulated profile to the OTLP profiles endpoint on a fixed interval.
 */
final class JfrRecordingManager {

  private static final Logger logger = Logger.getLogger(JfrRecordingManager.class.getName());

  private static final String EV_CPU_LOAD = "jdk.CPULoad";
  private static final String EV_THREAD_CPU_LOAD = "jdk.ThreadCPULoad";
  private static final String EV_THREAD_ALLOC = "jdk.ThreadAllocationStatistics";
  private static final String EV_THREAD_CTX_SWITCH = "jdk.ThreadContextSwitchRate";
  private static final String EV_GC_HEAP_SUMMARY = "jdk.GCHeapSummary";
  private static final String EV_METASPACE_SUMMARY = "jdk.MetaspaceSummary";
  private static final String EV_GARBAGE_COLLECTION = "jdk.GarbageCollection";
  private static final String EV_G1_GARBAGE_COLLECTION = "jdk.G1GarbageCollection";
  private static final String EV_ALLOC_IN_NEW_TLAB = "jdk.ObjectAllocationInNewTLAB";
  private static final String EV_ALLOC_OUTSIDE_TLAB = "jdk.ObjectAllocationOutsideTLAB";
  private static final String EV_SOCKET_READ = "jdk.SocketRead";
  private static final String EV_SOCKET_WRITE = "jdk.SocketWrite";
  private static final String EV_ALLOC_REQUIRING_GC = "jdk.AllocationRequiringGC";
  private static final String EV_MONITOR_WAIT = "jdk.JavaMonitorWait";
  private static final String EV_MONITOR_ENTER = "jdk.JavaMonitorEnter";
  private static final String EV_COMPILATION = "jdk.Compilation";
  private static final String EV_SYNC_ON_VBC = "jdk.SyncOnValueBasedClass";
  // Pre-release JDK 16 name for the same event.
  private static final String EV_SYNC_ON_PRIMITIVE_WRAPPER = "jdk.SyncOnPrimitiveWrapper";
  private static final String EV_JVM_INFORMATION = "jdk.JVMInformation";
  private static final String EV_INITIAL_SYSTEM_PROPERTY = "jdk.InitialSystemProperty";
  private static final String EV_EXECUTION_SAMPLE = "jdk.ExecutionSample";
  private static final String EV_NATIVE_METHOD_SAMPLE = "jdk.NativeMethodSample";

  private final OpenTelemetry openTelemetry;
  private final Resource resource;
  private final JfrSettings settings;

  /** Event names whose handler failure has already been reported (dedupes log spam). */
  private final Set<String> reportedHandlerErrors = ConcurrentHashMap.newKeySet();

  private volatile RecordingStream stream;
  private volatile ScheduledExecutorService flushExecutor;
  private volatile JfrProfileMapper profileMapper;
  private volatile JfrProfileExporter profileExporter;
  private volatile JfrStackSummary stackSummary;
  private volatile JfrFlameAggregator flameAggregator;

  JfrRecordingManager(OpenTelemetry openTelemetry, Resource protoResource, JfrSettings settings) {
    this.openTelemetry = openTelemetry;
    this.resource = protoResource;
    this.settings = settings;
  }

  void start() {
    boolean profileMode = settings.executionSampleEnabled && settings.profileMode;
    boolean aggregateMode =
        settings.executionSampleEnabled && !settings.profileMode && settings.aggregateEnabled;
    boolean spanMode = settings.executionSampleEnabled && !settings.profileMode && !aggregateMode;

    JfrMetricMapper metrics =
        settings.metricsEnabled
            ? new JfrMetricMapper(openTelemetry, settings.normalizeThreadNames)
            : null;
    JfrSpanMapper spans = spanMode ? new JfrSpanMapper(openTelemetry, settings.stackLogEnabled, settings.spanMaxPerSecond) : null;
    if (aggregateMode) {
      flameAggregator =
          new JfrFlameAggregator(
              openTelemetry,
              settings.normalizeThreadNames,
              Math.max(settings.flushIntervalMs, 1000L),
              settings.samplingPeriodMs,
              settings.aggregateTracesEnabled,
              settings.aggregateMaxTraceSpans);
    }
    if (profileMode) {
      profileMapper = new JfrProfileMapper(settings.samplingPeriodMs, resource);
      profileExporter = new JfrProfileExporter(settings.profilesEndpoint, settings.profilesHeaders);
    }

    if (!settings.metricsEnabled && !profileMode && !spanMode && !aggregateMode) {
      logger.info("otel-jfr-extension: profiling-level=off, nothing to collect");
      return;
    }

    RecordingStream rs = new RecordingStream();
    Duration metricsPeriod = Duration.ofMillis(Math.max(settings.metricsPeriodMs, 1000L));

    // ---- metric families (gated by the profiling level) ----
    if (metrics != null) {
      rs.enable(EV_CPU_LOAD).withPeriod(metricsPeriod);
      rs.enable(EV_THREAD_CTX_SWITCH).withPeriod(metricsPeriod);
      rs.enable(EV_GC_HEAP_SUMMARY);
      rs.enable(EV_METASPACE_SUMMARY);
      rs.enable(EV_GARBAGE_COLLECTION);
      rs.enable(EV_G1_GARBAGE_COLLECTION);

      rs.onEvent(EV_CPU_LOAD, guard(EV_CPU_LOAD, metrics::onCpuLoad));
      rs.onEvent(EV_THREAD_CTX_SWITCH, guard(EV_THREAD_CTX_SWITCH, metrics::onThreadContextSwitchRate));
      rs.onEvent(EV_GC_HEAP_SUMMARY, guard(EV_GC_HEAP_SUMMARY, metrics::onGcHeapSummary));
      rs.onEvent(EV_METASPACE_SUMMARY, guard(EV_METASPACE_SUMMARY, metrics::onMetaspaceSummary));
      rs.onEvent(EV_GARBAGE_COLLECTION, guard(EV_GARBAGE_COLLECTION, metrics::onGarbageCollection));
      rs.onEvent(EV_G1_GARBAGE_COLLECTION, guard(EV_G1_GARBAGE_COLLECTION, metrics::onG1GarbageCollection));

      // Low-volume GC trigger events.
      rs.enable(EV_ALLOC_REQUIRING_GC);
      rs.onEvent(EV_ALLOC_REQUIRING_GC, guard(EV_ALLOC_REQUIRING_GC, metrics::onAllocationRequiringGc));

      // Lock contention and JIT: thresholded so only slow events are recorded.
      Duration lockThreshold = Duration.ofMillis(Math.max(settings.lockThresholdMs, 0L));
      rs.enable(EV_MONITOR_WAIT).withThreshold(lockThreshold);
      rs.enable(EV_MONITOR_ENTER).withThreshold(lockThreshold);
      rs.onEvent(EV_MONITOR_WAIT, guard(EV_MONITOR_WAIT, metrics::onMonitorWait));
      rs.onEvent(EV_MONITOR_ENTER, guard(EV_MONITOR_ENTER, metrics::onMonitorEnter));
      rs.enable(EV_COMPILATION)
          .withThreshold(Duration.ofMillis(Math.max(settings.jitThresholdMs, 0L)));
      rs.onEvent(EV_COMPILATION, guard(EV_COMPILATION, metrics::onCompilation));

      // Only exists on JDK 16+; enabling an unknown event name is a no-op.
      rs.enable(EV_SYNC_ON_VBC);
      rs.enable(EV_SYNC_ON_PRIMITIVE_WRAPPER);
      rs.onEvent(EV_SYNC_ON_VBC, guard(EV_SYNC_ON_VBC, metrics::onSyncOnValueBasedClass));
      rs.onEvent(
          EV_SYNC_ON_PRIMITIVE_WRAPPER,
          guard(EV_SYNC_ON_PRIMITIVE_WRAPPER, metrics::onSyncOnValueBasedClass));

      // One-shot JVM context as log records.
      JfrJvmInfoMapper jvmInfo = new JfrJvmInfoMapper(openTelemetry);
      rs.enable(EV_JVM_INFORMATION);
      rs.onEvent(EV_JVM_INFORMATION, guard(EV_JVM_INFORMATION, jvmInfo::onJvmInformation));
      if (settings.systemPropertiesEnabled) {
        rs.enable(EV_INITIAL_SYSTEM_PROPERTY);
        rs.onEvent(
            EV_INITIAL_SYSTEM_PROPERTY,
            guard(EV_INITIAL_SYSTEM_PROPERTY, jvmInfo::onInitialSystemProperty));
      }

      // Per-thread metrics: high cardinality in thread-per-request apps.
      if (settings.perThreadMetricsEnabled) {
        rs.enable(EV_THREAD_CPU_LOAD).withPeriod(metricsPeriod);
        rs.enable(EV_THREAD_ALLOC).withPeriod(metricsPeriod);
        rs.onEvent(EV_THREAD_CPU_LOAD, guard(EV_THREAD_CPU_LOAD, metrics::onThreadCpuLoad));
        rs.onEvent(EV_THREAD_ALLOC, guard(EV_THREAD_ALLOC, metrics::onThreadAllocation));
      }

      // Allocation events: the highest-volume family (fires per allocation).
      if (settings.allocationEnabled) {
        rs.enable(EV_ALLOC_IN_NEW_TLAB).withoutThreshold();
        rs.enable(EV_ALLOC_OUTSIDE_TLAB).withoutThreshold();
        rs.onEvent(EV_ALLOC_IN_NEW_TLAB, guard(EV_ALLOC_IN_NEW_TLAB, metrics::onAllocationInNewTlab));
        rs.onEvent(EV_ALLOC_OUTSIDE_TLAB, guard(EV_ALLOC_OUTSIDE_TLAB, metrics::onAllocationOutsideTlab));
      }

      // Socket I/O: high volume in I/O-heavy apps.
      if (settings.socketEnabled) {
        rs.enable(EV_SOCKET_READ).withoutThreshold();
        rs.onEvent(EV_SOCKET_READ, guard(EV_SOCKET_READ, metrics::onSocketRead));
        rs.enable(EV_SOCKET_WRITE).withoutThreshold();
        rs.onEvent(EV_SOCKET_WRITE, guard(EV_SOCKET_WRITE, metrics::onSocketWrite));
      }
    }

    // ---- execution samples -> spans or profiles ----
    // jdk.NativeMethodSample has the same shape (sampledThread + stackTrace), so
    // it reuses the execution-sample handler of whichever output mode is active.
    Consumer<RecordedEvent> sampleHandler =
        profileMode ? profileMapper::onExecutionSample
            : spanMode ? spans::onExecutionSample
            : aggregateMode ? flameAggregator::onSample
            : null;
    if (sampleHandler != null && settings.stackSummaryEnabled) {
      JfrStackSummary summary =
          new JfrStackSummary(
              openTelemetry,
              settings.normalizeThreadNames,
              Math.max(settings.flushIntervalMs, 1000L));
      stackSummary = summary;
      Consumer<RecordedEvent> primary = sampleHandler;
      // Summary failures must not suppress the primary span/profile output.
      Consumer<RecordedEvent> guardedSummary = guard("jfr.stack.summary", summary::onSample);
      sampleHandler =
          event -> {
            primary.accept(event);
            guardedSummary.accept(event);
          };
    }
    if (sampleHandler != null) {
      Duration samplingPeriod = Duration.ofMillis(settings.samplingPeriodMs);
      rs.enable(EV_EXECUTION_SAMPLE).withPeriod(samplingPeriod).withStackTrace();
      rs.onEvent(EV_EXECUTION_SAMPLE, guard(EV_EXECUTION_SAMPLE, sampleHandler));
      if (settings.nativeSampleEnabled) {
        rs.enable(EV_NATIVE_METHOD_SAMPLE).withPeriod(samplingPeriod).withStackTrace();
        rs.onEvent(EV_NATIVE_METHOD_SAMPLE, guard(EV_NATIVE_METHOD_SAMPLE, sampleHandler));
      }
      if (profileMode || aggregateMode || stackSummary != null) {
        startFlushLoop();
      }
    }

    rs.setReuse(false);
    rs.onError(t -> logger.log(Level.WARNING, "otel-jfr-extension: JFR stream error", t));

    this.stream = rs;
    rs.startAsync();
    logger.info(
        "otel-jfr-extension: JFR recording stream started (metrics="
            + settings.metricsEnabled
            + ", executionSample="
            + (profileMode ? "profile" : spanMode ? "span" : aggregateMode ? "aggregate" : "off")
            + ", nativeSamples="
            + (sampleHandler != null && settings.nativeSampleEnabled)
            + ", stackSummary="
            + (stackSummary != null)
            + ")");
  }

  /**
   * Wraps a JFR event handler so a failure processing one event type can never
   * propagate out of the dispatch and disrupt the other handlers (they all run
   * on the single RecordingStream thread). The first failure per event type is
   * logged at WARNING; subsequent failures for that type are suppressed.
   */
  private Consumer<RecordedEvent> guard(String eventName, Consumer<RecordedEvent> handler) {
    return event -> {
      try {
        handler.accept(event);
      } catch (RuntimeException e) {
        if (reportedHandlerErrors.add(eventName)) {
          logger.log(
              Level.WARNING,
              "otel-jfr-extension: handler for "
                  + eventName
                  + " failed; other JFR events unaffected, further failures for this"
                  + " event suppressed",
              e);
        }
      }
    };
  }

  private void startFlushLoop() {    flushExecutor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "otel-jfr-profile-flush");
              t.setDaemon(true);
              return t;
            });
    long interval = Math.max(settings.flushIntervalMs, 1000L);
    flushExecutor.scheduleAtFixedRate(
        this::flushAll, interval, interval, TimeUnit.MILLISECONDS);
    logger.info("otel-jfr-extension: sample flush every " + interval + "ms"
        + (profileExporter != null ? " -> " + settings.profilesEndpoint : ""));
  }

  private void flushAll() {
    flushProfiles();
    JfrFlameAggregator agg = flameAggregator;
    if (agg != null) {
      try {
        agg.flush();
      } catch (RuntimeException | Error e) {
        logger.log(Level.WARNING, "otel-jfr-extension: flame aggregate flush failed", e);
      }
    }
    JfrStackSummary summary = stackSummary;
    if (summary != null) {
      try {
        summary.flush();
      } catch (RuntimeException | Error e) {
        logger.log(Level.WARNING, "otel-jfr-extension: stack summary flush failed", e);
      }
    }
  }

  private void flushProfiles() {
    try {
      JfrProfileMapper mapper = profileMapper;
      JfrProfileExporter exporter = profileExporter;
      if (mapper == null || exporter == null) {
        return;
      }
      ExportProfilesServiceRequest request = mapper.flush();
      if (request != null) {
        exporter.export(request);
      }
    } catch (RuntimeException | Error e) {
      logger.log(Level.WARNING, "otel-jfr-extension: profile flush failed", e);
    }
  }

  void stop() {
    ScheduledExecutorService exec = this.flushExecutor;
    if (exec != null) {
      flushAll(); // final drain
      exec.shutdown();
    }
    RecordingStream rs = this.stream;
    if (rs != null) {
      try {
        rs.close();
        logger.info("otel-jfr-extension: JFR recording stream closed");
      } catch (RuntimeException e) {
        logger.log(Level.FINE, "otel-jfr-extension: error closing JFR stream", e);
      }
    }
  }
}

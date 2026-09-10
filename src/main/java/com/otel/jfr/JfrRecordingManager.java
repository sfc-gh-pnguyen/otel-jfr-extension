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
  private static final String EV_EXECUTION_SAMPLE = "jdk.ExecutionSample";

  private final OpenTelemetry openTelemetry;
  private final Resource resource;
  private final JfrSettings settings;

  /** Event names whose handler failure has already been reported (dedupes log spam). */
  private final Set<String> reportedHandlerErrors = ConcurrentHashMap.newKeySet();

  private volatile RecordingStream stream;
  private volatile ScheduledExecutorService flushExecutor;
  private volatile JfrProfileMapper profileMapper;
  private volatile JfrProfileExporter profileExporter;

  JfrRecordingManager(OpenTelemetry openTelemetry, Resource protoResource, JfrSettings settings) {
    this.openTelemetry = openTelemetry;
    this.resource = protoResource;
    this.settings = settings;
  }

  void start() {
    boolean profileMode = settings.executionSampleEnabled && settings.profileMode;
    boolean spanMode = settings.executionSampleEnabled && !settings.profileMode;

    JfrMetricMapper metrics = settings.metricsEnabled ? new JfrMetricMapper(openTelemetry) : null;
    JfrSpanMapper spans = spanMode ? new JfrSpanMapper(openTelemetry, settings.stackLogEnabled) : null;
    if (profileMode) {
      profileMapper = new JfrProfileMapper(settings.samplingPeriodMs, resource);
      profileExporter = new JfrProfileExporter(settings.profilesEndpoint, settings.profilesHeaders);
    }

    if (!settings.metricsEnabled && !profileMode && !spanMode) {
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

      // Socket reads: high volume in I/O-heavy apps.
      if (settings.socketEnabled) {
        rs.enable(EV_SOCKET_READ).withoutThreshold();
        rs.onEvent(EV_SOCKET_READ, guard(EV_SOCKET_READ, metrics::onSocketRead));
      }
    }

    // ---- execution samples -> spans or profiles ----
    if (profileMode) {
      rs.enable(EV_EXECUTION_SAMPLE)
          .withPeriod(Duration.ofMillis(settings.samplingPeriodMs))
          .withStackTrace();
      rs.onEvent(EV_EXECUTION_SAMPLE, guard(EV_EXECUTION_SAMPLE, profileMapper::onExecutionSample));
      startFlushLoop();
    } else if (spanMode) {
      rs.enable(EV_EXECUTION_SAMPLE)
          .withPeriod(Duration.ofMillis(settings.samplingPeriodMs))
          .withStackTrace();
      rs.onEvent(EV_EXECUTION_SAMPLE, guard(EV_EXECUTION_SAMPLE, spans::onExecutionSample));
    }

    rs.setReuse(false);
    rs.onError(t -> logger.log(Level.WARNING, "otel-jfr-extension: JFR stream error", t));

    this.stream = rs;
    rs.startAsync();
    logger.info(
        "otel-jfr-extension: JFR recording stream started (metrics="
            + settings.metricsEnabled
            + ", executionSample="
            + (profileMode ? "profile" : spanMode ? "span" : "off")
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
        this::flushProfiles, interval, interval, TimeUnit.MILLISECONDS);
    logger.info("otel-jfr-extension: profile flush every " + interval + "ms -> "
        + settings.profilesEndpoint);
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
      flushProfiles(); // final drain
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

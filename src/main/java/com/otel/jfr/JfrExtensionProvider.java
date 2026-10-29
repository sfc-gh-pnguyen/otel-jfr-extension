package com.otel.jfr;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.util.Collections;
import java.util.Map;

/**
 * Captures the agent's resolved {@link ConfigProperties} so {@link JfrAgentListener}
 * can read this extension's settings once the SDK is ready.
 *
 * <p>Does NOT start JFR or touch {@code GlobalOpenTelemetry} (see
 * {@link JfrAgentListener} for why). Startup happens in {@code afterAgent}.
 *
 * <p>Configuration:
 *
 * <pre>
 *   otel.instrumentation.jfr.enabled              (boolean, default false)
 *   otel.instrumentation.jfr.profiling-level      (full|standard|light|off, default standard)
 *       - one dial that bundles the JFR volume knobs (see Level enum)
 *   otel.instrumentation.jfr.profiling-enabled    (boolean, default true)
 *       - hard gate on execution samples, applied on top of the level
 *   otel.instrumentation.jfr.sampling-period-ms   (long; overrides the level's period)
 *   otel.instrumentation.jfr.profile              (boolean, default false)
 *       - false: execution samples -> trace spans (default)
 *       - true : execution samples -> OTLP profiles signal instead
 *   otel.instrumentation.jfr.profile.flush-interval-ms (long, default 10000)
 *   otel.instrumentation.jfr.stack-log            (boolean, default true)
 *       - span mode only: also emit each sample's full stack as a log record
 *         correlated to its jfr.stack.sample span (shows under "Logs related
 *         to span"). Requires the logs pipeline to be enabled.
 *   otel.instrumentation.jfr.native-samples       (boolean; default on for level=full only)
 *       - also sample threads running native code (jdk.NativeMethodSample)
 *   otel.instrumentation.jfr.thread-name-normalize (boolean, default true)
 *       - collapse pool-3-thread-17 style names to pool#thread# on metrics
 *   otel.instrumentation.jfr.lock-threshold-ms    (long, default 5)
 *       - minimum jdk.JavaMonitorWait / JavaMonitorEnter duration recorded
 *   otel.instrumentation.jfr.jit-threshold-ms     (long, default 100)
 *       - minimum jdk.Compilation duration recorded
 *   otel.instrumentation.jfr.system-properties    (boolean, default false)
 *       - log initial system properties once (secret-looking keys redacted)
 *   otel.instrumentation.jfr.stack-summary        (boolean, default true)
 *       - also aggregate samples into folded-stack log records (flame-graph
 *         input) plus a jfr.jvm.execution_sample.count leaf-method counter,
 *         flushed every profile.flush-interval-ms
 * </pre>
 *
 * <p>When {@code profile=true}, profiles are POSTed to (in precedence order):
 *
 * <pre>
 *   otel.exporter.otlp.profiles.endpoint  (used verbatim)
 *   otel.exporter.otlp.endpoint + "/v1development/profiles"
 * </pre>
 *
 * with headers from {@code otel.exporter.otlp.profiles.headers} (falling back to
 * {@code otel.exporter.otlp.headers}). The agent does not autoconfigure a
 * profiles pipeline, so this extension reads these itself. Trace/metric routing
 * remains fully native.
 */
public class JfrExtensionProvider implements AutoConfigurationCustomizerProvider {

  static final String PROP_ENABLED = "otel.instrumentation.jfr.enabled";
  static final String PROP_PROFILING_LEVEL = "otel.instrumentation.jfr.profiling-level";
  static final String PROP_SAMPLING_PERIOD_MS = "otel.instrumentation.jfr.sampling-period-ms";
  static final String PROP_PROFILING_ENABLED = "otel.instrumentation.jfr.profiling-enabled";
  static final String PROP_PROFILE = "otel.instrumentation.jfr.profile";
  static final String PROP_FLUSH_INTERVAL_MS = "otel.instrumentation.jfr.profile.flush-interval-ms";
  static final String PROP_STACK_LOG = "otel.instrumentation.jfr.stack-log";
  static final String PROP_NATIVE_SAMPLES = "otel.instrumentation.jfr.native-samples";
  static final String PROP_THREAD_NAME_NORMALIZE =
      "otel.instrumentation.jfr.thread-name-normalize";
  static final String PROP_LOCK_THRESHOLD_MS = "otel.instrumentation.jfr.lock-threshold-ms";
  static final String PROP_JIT_THRESHOLD_MS = "otel.instrumentation.jfr.jit-threshold-ms";
  static final String PROP_SYSTEM_PROPERTIES = "otel.instrumentation.jfr.system-properties";
  static final String PROP_AGGREGATE = "otel.instrumentation.jfr.aggregate";
  static final String PROP_AGGREGATE_TRACES = "otel.instrumentation.jfr.aggregate.traces";
  static final String PROP_AGGREGATE_MAX_TRACE_SPANS =
      "otel.instrumentation.jfr.aggregate.max-trace-spans";
  static final String PROP_SPAN_MAX_PER_SECOND =
      "otel.instrumentation.jfr.span.max-per-second";
  static final String PROP_STACK_SUMMARY = "otel.instrumentation.jfr.stack-summary";

  static final String PROP_OTLP_ENDPOINT = "otel.exporter.otlp.endpoint";
  static final String PROP_OTLP_PROFILES_ENDPOINT = "otel.exporter.otlp.profiles.endpoint";
  static final String PROP_OTLP_HEADERS = "otel.exporter.otlp.headers";
  static final String PROP_OTLP_PROFILES_HEADERS = "otel.exporter.otlp.profiles.headers";

  static final String DEFAULT_OTLP_ENDPOINT = "http://localhost:4318";
  static final String PROFILES_PATH = "/v1development/profiles";

  /** Resolved config, captured during SDK autoconfigure, read later by the listener. */
  static volatile ConfigProperties capturedConfig;

  @Override
  public void customize(AutoConfigurationCustomizer customizer) {
    customizer.addPropertiesCustomizer(
        config -> {
          capturedConfig = config;
          return Collections.emptyMap();
        });
  }

  /** Immutable resolved configuration. */
  static final class JfrSettings {
    // execution-sample output
    final boolean executionSampleEnabled;
    final long samplingPeriodMs;
    final boolean profileMode;
    final long flushIntervalMs;
    final String profilesEndpoint;
    final Map<String, String> profilesHeaders;
    // span mode: also emit each sample's stack as a span-correlated log record
    final boolean stackLogEnabled;
    // metric knobs (driven by profiling-level)
    final boolean metricsEnabled;
    final long metricsPeriodMs;
    final boolean perThreadMetricsEnabled;
    final boolean allocationEnabled;
    final boolean socketEnabled;
    // sampling, threshold and logging knobs
    final boolean nativeSampleEnabled;
    final boolean normalizeThreadNames;
    final long lockThresholdMs;
    final long jitThresholdMs;
    final boolean systemPropertiesEnabled;
    final boolean stackSummaryEnabled;
    /** Span mode: max spans/sec from execution samples; 0 = unlimited. */
    final long spanMaxPerSecond;
    final boolean aggregateEnabled;
    final boolean aggregateTracesEnabled;
    final long aggregateMaxTraceSpans;

    JfrSettings(
        boolean executionSampleEnabled,
        long samplingPeriodMs,
        boolean profileMode,
        long flushIntervalMs,
        String profilesEndpoint,
        Map<String, String> profilesHeaders,
        boolean stackLogEnabled,
        boolean metricsEnabled,
        long metricsPeriodMs,
        boolean perThreadMetricsEnabled,
        boolean allocationEnabled,
        boolean socketEnabled,
        boolean nativeSampleEnabled,
        boolean normalizeThreadNames,
        long lockThresholdMs,
        long jitThresholdMs,
        boolean systemPropertiesEnabled,
        boolean stackSummaryEnabled,
        long spanMaxPerSecond,
        boolean aggregateEnabled,
        boolean aggregateTracesEnabled,
        long aggregateMaxTraceSpans) {
      this.executionSampleEnabled = executionSampleEnabled;
      this.samplingPeriodMs = samplingPeriodMs;
      this.profileMode = profileMode;
      this.flushIntervalMs = flushIntervalMs;
      this.profilesEndpoint = profilesEndpoint;
      this.profilesHeaders = profilesHeaders;
      this.stackLogEnabled = stackLogEnabled;
      this.metricsEnabled = metricsEnabled;
      this.metricsPeriodMs = metricsPeriodMs;
      this.perThreadMetricsEnabled = perThreadMetricsEnabled;
      this.allocationEnabled = allocationEnabled;
      this.socketEnabled = socketEnabled;
      this.nativeSampleEnabled = nativeSampleEnabled;
      this.normalizeThreadNames = normalizeThreadNames;
      this.lockThresholdMs = lockThresholdMs;
      this.jitThresholdMs = jitThresholdMs;
      this.systemPropertiesEnabled = systemPropertiesEnabled;
      this.stackSummaryEnabled = stackSummaryEnabled;
      this.spanMaxPerSecond = spanMaxPerSecond;
      this.aggregateEnabled = aggregateEnabled;
      this.aggregateTracesEnabled = aggregateTracesEnabled;
      this.aggregateMaxTraceSpans = aggregateMaxTraceSpans;
    }
  }

  /**
   * Volume tiers for {@code otel.instrumentation.jfr.profiling-level} (Option A:
   * one dial). Each level bundles the individual JFR volume knobs. Default is
   * {@link #STANDARD}. {@code sampling-period-ms} may still be set explicitly to
   * override the level's execution-sample period.
   *
   * <pre>
   * knob                 FULL    STANDARD   LIGHT    OFF
   * metrics               on       on        on      off
   * metrics period (ms)  1000     2000      5000      -
   * per-thread metrics    on       on        off     off
   * allocation events     on       off       off     off   (highest-volume family)
   * socket read events    on       on        off     off
   * native samples        on       off       off     off
   * execution samples     on       on        on      off
   * sampling period (ms)   10       20        50       -
   * </pre>
   */
  enum Level {
    FULL(true, 1000L, true, true, true, true, 10L, true),
    STANDARD(true, 2000L, true, false, true, true, 20L, false),
    LIGHT(true, 5000L, false, false, false, true, 50L, false),
    OFF(false, 0L, false, false, false, false, 0L, false);

    final boolean metricsEnabled;
    final long metricsPeriodMs;
    final boolean perThreadMetricsEnabled;
    final boolean allocationEnabled;
    final boolean socketEnabled;
    final boolean executionSampleEnabled;
    final long samplingPeriodMs;
    final boolean nativeSampleEnabled;

    Level(
        boolean metricsEnabled,
        long metricsPeriodMs,
        boolean perThreadMetricsEnabled,
        boolean allocationEnabled,
        boolean socketEnabled,
        boolean executionSampleEnabled,
        long samplingPeriodMs,
        boolean nativeSampleEnabled) {
      this.metricsEnabled = metricsEnabled;
      this.metricsPeriodMs = metricsPeriodMs;
      this.perThreadMetricsEnabled = perThreadMetricsEnabled;
      this.allocationEnabled = allocationEnabled;
      this.socketEnabled = socketEnabled;
      this.executionSampleEnabled = executionSampleEnabled;
      this.samplingPeriodMs = samplingPeriodMs;
      this.nativeSampleEnabled = nativeSampleEnabled;
    }

    static Level fromString(String s) {
      if (s == null || s.isEmpty()) {
        return STANDARD;
      }
      switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
        case "full":
          return FULL;
        case "light":
          return LIGHT;
        case "off":
          return OFF;
        case "standard":
          return STANDARD;
        default:
          return STANDARD; // unknown value -> safe default
      }
    }
  }
}

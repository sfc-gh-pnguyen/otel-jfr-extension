package com.otel.jfr;

import com.otel.jfr.JfrExtensionProvider.JfrSettings;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Starts JFR profiling once the OpenTelemetry SDK is fully initialized.
 *
 * <p>The agent invokes {@link #afterAgent} after it has built and registered the
 * real SDK (in a dedicated thread). This is the race-free place to obtain the
 * configured {@link OpenTelemetry} and {@link Resource} -- unlike calling
 * {@code GlobalOpenTelemetry.get()} from a timer, which front-runs the agent's
 * own registration and crashes startup.
 */
public class JfrAgentListener implements AgentListener {

  private static final Logger logger = Logger.getLogger(JfrAgentListener.class.getName());

  @Override
  public void afterAgent(AutoConfiguredOpenTelemetrySdk autoConfiguredSdk) {
    try {
      ConfigProperties config = JfrExtensionProvider.capturedConfig;

      if (!getBoolean(config, JfrExtensionProvider.PROP_ENABLED, false)) {
        logger.fine(
            "otel-jfr-extension: disabled (set "
                + JfrExtensionProvider.PROP_ENABLED
                + "=true to enable)");
        return;
      }

      JfrExtensionProvider.Level level =
          JfrExtensionProvider.Level.fromString(
              getString(config, JfrExtensionProvider.PROP_PROFILING_LEVEL));

      // sampling-period-ms, when explicitly set, overrides the level's period.
      long samplingPeriodMs = level.samplingPeriodMs;
      Long periodOverride = getLongOrNull(config, JfrExtensionProvider.PROP_SAMPLING_PERIOD_MS);
      if (periodOverride != null) {
        samplingPeriodMs = periodOverride;
      }

      // profiling-enabled is a hard gate on execution samples, on top of the level.
      boolean executionSampleEnabled =
          level.executionSampleEnabled
              && getBoolean(config, JfrExtensionProvider.PROP_PROFILING_ENABLED, true);

      boolean profileMode = getBoolean(config, JfrExtensionProvider.PROP_PROFILE, false);
      JfrSettings settings =
          new JfrSettings(
              executionSampleEnabled,
              samplingPeriodMs,
              profileMode,
              getLong(config, JfrExtensionProvider.PROP_FLUSH_INTERVAL_MS, 10000L),
              resolveProfilesEndpoint(config),
              resolveProfilesHeaders(config),
              level.metricsEnabled,
              level.metricsPeriodMs,
              level.perThreadMetricsEnabled,
              level.allocationEnabled,
              level.socketEnabled);

      logger.info(
          "otel-jfr-extension: enabled (level="
              + level.name().toLowerCase(java.util.Locale.ROOT)
              + ", metrics="
              + settings.metricsEnabled
              + ", executionSampleOutput="
              + (executionSampleEnabled ? (profileMode ? "profile" : "span") : "off")
              + ", samplingPeriodMs="
              + settings.samplingPeriodMs
              + (profileMode && executionSampleEnabled
                  ? ", profilesEndpoint=" + settings.profilesEndpoint
                  : "")
              + ")");

      OpenTelemetry openTelemetry = autoConfiguredSdk.getOpenTelemetrySdk();
      // AutoConfiguredOpenTelemetrySdk.getResource() is not public in this SDK
      // version, so build the profiles Resource from the user-configured
      // attributes (otel.service.name + otel.resource.attributes) instead.
      io.opentelemetry.proto.resource.v1.Resource protoResource =
          JfrProfileExporter.protoResource(resolveResourceAttributes(config));
      JfrRecordingManager manager =
          new JfrRecordingManager(openTelemetry, protoResource, settings);
      Runtime.getRuntime().addShutdownHook(new Thread(manager::stop, "otel-jfr-shutdown"));
      manager.start();
    } catch (RuntimeException | Error e) {
      logger.log(Level.WARNING, "otel-jfr-extension: failed to start", e);
    }
  }

  /** Resource attributes from otel.service.name + otel.resource.attributes. */
  private static Map<String, String> resolveResourceAttributes(ConfigProperties config) {
    Map<String, String> attrs = new LinkedHashMap<>();
    String serviceName = getString(config, "otel.service.name");
    if (serviceName != null && !serviceName.isEmpty()) {
      attrs.put("service.name", serviceName);
    }
    String raw = getString(config, "otel.resource.attributes");
    if (raw != null && !raw.isEmpty()) {
      for (String pair : raw.split(",")) {
        int eq = pair.indexOf('=');
        if (eq > 0) {
          attrs.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
      }
    }
    return attrs;
  }

  /**
   * Profiles endpoint: the signal-specific property verbatim, else the base OTLP
   * endpoint with the {@code /v1development/profiles} path appended.
   */
  private static String resolveProfilesEndpoint(ConfigProperties config) {
    String specific = getString(config, JfrExtensionProvider.PROP_OTLP_PROFILES_ENDPOINT);
    if (specific != null && !specific.isEmpty()) {
      return specific;
    }
    String base = getString(config, JfrExtensionProvider.PROP_OTLP_ENDPOINT);
    if (base == null || base.isEmpty()) {
      base = JfrExtensionProvider.DEFAULT_OTLP_ENDPOINT;
    }
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + JfrExtensionProvider.PROFILES_PATH;
  }

  /** Signal-specific headers if present, else the base OTLP headers. */
  private static Map<String, String> resolveProfilesHeaders(ConfigProperties config) {
    String raw = getString(config, JfrExtensionProvider.PROP_OTLP_PROFILES_HEADERS);
    if (raw == null || raw.isEmpty()) {
      raw = getString(config, JfrExtensionProvider.PROP_OTLP_HEADERS);
    }
    return parseHeaders(raw);
  }

  /** Parse W3C-baggage-style "k1=v1,k2=v2" header lists. */
  private static Map<String, String> parseHeaders(String raw) {
    Map<String, String> headers = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) {
      return headers;
    }
    for (String pair : raw.split(",")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        headers.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
      }
    }
    return headers;
  }

  // ConfigProperties honors system props + env vars; fall back to system props only.
  private static boolean getBoolean(ConfigProperties config, String key, boolean def) {
    if (config != null) {
      Boolean v = config.getBoolean(key);
      if (v != null) {
        return v;
      }
    }
    String sys = System.getProperty(key);
    return sys != null ? Boolean.parseBoolean(sys) : def;
  }

  private static long getLong(ConfigProperties config, String key, long def) {
    Long v = getLongOrNull(config, key);
    return v != null ? v : def;
  }

  /** Returns the configured long, or null when the property is not set anywhere. */
  private static Long getLongOrNull(ConfigProperties config, String key) {
    if (config != null) {
      Long v = config.getLong(key);
      if (v != null) {
        return v;
      }
    }
    String sys = System.getProperty(key);
    if (sys != null) {
      try {
        return Long.parseLong(sys);
      } catch (NumberFormatException ignored) {
        // fall through
      }
    }
    return null;
  }

  private static String getString(ConfigProperties config, String key) {
    if (config != null) {
      String v = config.getString(key);
      if (v != null) {
        return v;
      }
    }
    return System.getProperty(key);
  }
}

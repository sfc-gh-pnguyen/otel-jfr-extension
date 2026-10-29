package com.otel.jfr;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import jdk.jfr.consumer.RecordedEvent;

/**
 * Emits one-shot JVM context as OTel log records: {@code jdk.JVMInformation}
 * (JVM name, version, start time, pid) and, opt-in, {@code
 * jdk.InitialSystemProperty}.
 *
 * <p>JFR re-emits both families at every chunk rotation, so each is deduped and
 * logged once per JVM. JVM and java command-line arguments are deliberately
 * NOT emitted: {@code -D} flags routinely carry license keys and OTLP auth
 * headers. For the same reason system properties default off and, when
 * enabled, values of secret-looking keys are redacted.
 */
final class JfrJvmInfoMapper {

  private static final java.util.logging.Logger LOG =
      java.util.logging.Logger.getLogger(JfrJvmInfoMapper.class.getName());

  private static final AttributeKey<String> EVENT_NAME = AttributeKey.stringKey("event.name");
  private static final AttributeKey<String> JVM_NAME = AttributeKey.stringKey("jvm.name");
  private static final AttributeKey<String> JVM_VERSION = AttributeKey.stringKey("jvm.version");
  private static final AttributeKey<String> JVM_START_TIME =
      AttributeKey.stringKey("jvm.start_time");
  private static final AttributeKey<Long> PROCESS_PID = AttributeKey.longKey("process.pid");
  private static final AttributeKey<String> PROP_KEY =
      AttributeKey.stringKey("jvm.system_property.key");
  private static final AttributeKey<String> PROP_VALUE =
      AttributeKey.stringKey("jvm.system_property.value");

  private static final Pattern SECRET_KEY =
      Pattern.compile(
          "(?i).*(key|token|secret|passw|pwd|credential|auth|header|cookie|session|private).*");
  private static final String REDACTED = "<redacted>";

  private final Logger logger;
  private volatile boolean jvmInfoEmitted = false;
  private final Set<String> emittedProperties = ConcurrentHashMap.newKeySet();

  JfrJvmInfoMapper(OpenTelemetry openTelemetry) {
    Logger lg = null;
    try {
      lg = openTelemetry.getLogsBridge().get("com.otel.jfr");
    } catch (RuntimeException | LinkageError t) {
      LOG.log(
          java.util.logging.Level.WARNING,
          "otel-jfr-extension: logs bridge unavailable; JVM information logs disabled",
          t);
    }
    this.logger = lg;
  }

  // ---- jdk.JVMInformation ----
  void onJvmInformation(RecordedEvent event) {
    if (logger == null || jvmInfoEmitted) {
      return;
    }
    jvmInfoEmitted = true;
    String name = string(event, "jvmName");
    String version = string(event, "jvmVersion");
    io.opentelemetry.api.logs.LogRecordBuilder rec =
        logger
            .logRecordBuilder()
            .setTimestamp(event.getStartTime())
            .setSeverity(Severity.INFO)
            .setBody("JVM information: " + name + " " + version)
            .setAttribute(EVENT_NAME, "jfr.jvm.information")
            .setAttribute(JVM_NAME, name)
            .setAttribute(JVM_VERSION, version);
    if (event.hasField("jvmStartTime")) {
      rec.setAttribute(JVM_START_TIME, event.getInstant("jvmStartTime").toString());
    }
    if (event.hasField("pid")) {
      rec.setAttribute(PROCESS_PID, event.getLong("pid"));
    }
    rec.emit();
  }

  // ---- jdk.InitialSystemProperty ----
  void onInitialSystemProperty(RecordedEvent event) {
    if (logger == null) {
      return;
    }
    String key = string(event, "key");
    if (!emittedProperties.add(key)) {
      return;
    }
    String value = SECRET_KEY.matcher(key).matches() ? REDACTED : string(event, "value");
    logger
        .logRecordBuilder()
        .setTimestamp(event.getStartTime())
        .setSeverity(Severity.INFO)
        .setBody(key + "=" + value)
        .setAttribute(EVENT_NAME, "jfr.jvm.system_property")
        .setAttribute(PROP_KEY, key)
        .setAttribute(PROP_VALUE, value)
        .emit();
  }

  private static String string(RecordedEvent event, String field) {
    if (!event.hasField(field)) {
      return "unknown";
    }
    String v = event.getString(field);
    return v != null ? v : "unknown";
  }
}

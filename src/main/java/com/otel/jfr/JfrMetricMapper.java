package com.otel.jfr;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;

/**
 * Maps JFR {@code jdk.*} events onto OpenTelemetry instruments.
 *
 * <p>Naming follows real OTel semantic conventions where they exist
 * ({@code jvm.memory.*}, {@code jvm.gc.*}, {@code process.cpu.utilization},
 * {@code system.cpu.utilization}, {@code cpu.mode}), and a clearly-labelled
 * custom {@code jvm.jfr.*} namespace where they do not. This mirrors the
 * mapping table already established in the repo's {@code jfr.py} so metrics
 * from either pipeline are interchangeable in dashboards.
 *
 * <p>Unlike the Python converter -- which had to infer units from magnitude
 * because New Relic's Metric API payload does not declare them -- durations
 * here come from {@link RecordedEvent#getDuration()}, which is a real {@code
 * java.time.Duration}. No unit guessing.
 */
final class JfrMetricMapper {

  private static final AttributeKey<String> CPU_MODE = AttributeKey.stringKey("cpu.mode");
  private static final AttributeKey<String> THREAD_NAME = AttributeKey.stringKey("thread.name");
  private static final AttributeKey<String> JVM_MEMORY_TYPE =
      AttributeKey.stringKey("jvm.memory.type");
  private static final AttributeKey<String> JVM_MEMORY_POOL_NAME =
      AttributeKey.stringKey("jvm.memory.pool.name");
  private static final AttributeKey<String> JVM_GC_NAME = AttributeKey.stringKey("jvm.gc.name");
  private static final AttributeKey<String> JVM_GC_ACTION = AttributeKey.stringKey("jvm.gc.action");
  private static final AttributeKey<String> JVM_GC_CAUSE = AttributeKey.stringKey("jvm.gc.cause");
  private static final AttributeKey<String> ALLOC_LOCATION =
      AttributeKey.stringKey("jvm.jfr.allocation.location");

  private static final Attributes CPU_USER = Attributes.of(CPU_MODE, "user");
  private static final Attributes CPU_SYSTEM = Attributes.of(CPU_MODE, "system");
  private static final Attributes CPU_TOTAL = Attributes.of(CPU_MODE, "total");
  private static final Attributes HEAP = Attributes.of(JVM_MEMORY_TYPE, "heap");

  // ---- instruments ----
  private final DoubleGauge processCpuUtilization;
  private final DoubleGauge systemCpuUtilization;
  private final DoubleGauge threadCpuUtilization;
  private final DoubleGauge threadContextSwitchRate;
  private final DoubleGauge threadMemoryAllocated;
  private final DoubleGauge memoryUsed;
  private final DoubleGauge memoryCommitted;
  private final DoubleGauge memoryLimit;
  private final DoubleGauge gcLongestPause;
  private final DoubleHistogram gcDuration;
  private final DoubleHistogram memoryAllocation;
  private final DoubleHistogram socketReadBytes;
  private final DoubleHistogram socketReadDuration;

  JfrMetricMapper(OpenTelemetry openTelemetry) {
    Meter meter = openTelemetry.getMeter("com.otel.jfr");

    processCpuUtilization =
        meter
            .gaugeBuilder("jfr.process.cpu.utilization")
            .setUnit("1")
            .setDescription("JVM process CPU utilization, from jdk.CPULoad")
            .build();
    systemCpuUtilization =
        meter
            .gaugeBuilder("jfr.system.cpu.utilization")
            .setUnit("1")
            .setDescription("Machine-wide CPU utilization, from jdk.CPULoad")
            .build();
    threadCpuUtilization =
        meter
            .gaugeBuilder("jfr.jvm.thread.cpu.utilization")
            .setUnit("1")
            .setDescription("Per-thread CPU utilization, from jdk.ThreadCPULoad")
            .build();
    threadContextSwitchRate =
        meter
            .gaugeBuilder("jfr.jvm.thread.context_switch.rate")
            .setUnit("{switch}/s")
            .setDescription("Thread context switch rate, from jdk.ThreadContextSwitchRate")
            .build();
    threadMemoryAllocated =
        meter
            .gaugeBuilder("jfr.jvm.thread.memory.allocated")
            .setUnit("By")
            .setDescription("Cumulative bytes allocated per thread, from "
                + "jdk.ThreadAllocationStatistics")
            .build();

    memoryUsed =
        meter.gaugeBuilder("jfr.jvm.memory.used").setUnit("By").setDescription("Memory used").build();
    memoryCommitted =
        meter
            .gaugeBuilder("jfr.jvm.memory.committed")
            .setUnit("By")
            .setDescription("Memory committed")
            .build();
    memoryLimit =
        meter
            .gaugeBuilder("jfr.jvm.memory.limit")
            .setUnit("By")
            .setDescription("Memory limit / reserved")
            .build();

    gcLongestPause =
        meter
            .gaugeBuilder("jfr.jvm.gc.longest_pause")
            .setUnit("s")
            .setDescription("Longest GC pause of the collection, from jdk.GarbageCollection")
            .build();
    gcDuration =
        meter
            .histogramBuilder("jfr.jvm.gc.duration")
            .setUnit("s")
            .setDescription("Garbage collection duration")
            .build();
    memoryAllocation =
        meter
            .histogramBuilder("jfr.jvm.memory.allocation")
            .setUnit("By")
            .setDescription("Object allocation sizes, from jdk.ObjectAllocation* events")
            .build();
    socketReadBytes =
        meter
            .histogramBuilder("jfr.jvm.socket.read.bytes")
            .setUnit("By")
            .setDescription("Bytes read per socket read, from jdk.SocketRead")
            .build();
    socketReadDuration =
        meter
            .histogramBuilder("jfr.jvm.socket.read.duration")
            .setUnit("s")
            .setDescription("Socket read duration, from jdk.SocketRead")
            .build();
  }

  // ---- jdk.CPULoad ----
  void onCpuLoad(RecordedEvent event) {
    if (event.hasField("jvmUser")) {
      processCpuUtilization.set(event.getFloat("jvmUser"), CPU_USER);
    }
    if (event.hasField("jvmSystem")) {
      processCpuUtilization.set(event.getFloat("jvmSystem"), CPU_SYSTEM);
    }
    if (event.hasField("machineTotal")) {
      systemCpuUtilization.set(event.getFloat("machineTotal"), CPU_TOTAL);
    }
  }

  // ---- jdk.ThreadCPULoad ----
  void onThreadCpuLoad(RecordedEvent event) {
    String thread = threadName(event);
    if (event.hasField("user")) {
      threadCpuUtilization.set(
          event.getFloat("user"), Attributes.of(THREAD_NAME, thread, CPU_MODE, "user"));
    }
    if (event.hasField("system")) {
      threadCpuUtilization.set(
          event.getFloat("system"), Attributes.of(THREAD_NAME, thread, CPU_MODE, "system"));
    }
  }

  // ---- jdk.ThreadAllocationStatistics ----
  void onThreadAllocation(RecordedEvent event) {
    if (event.hasField("allocated")) {
      threadMemoryAllocated.set(
          event.getLong("allocated"), Attributes.of(THREAD_NAME, threadName(event)));
    }
  }

  // ---- jdk.ThreadContextSwitchRate ----
  void onThreadContextSwitchRate(RecordedEvent event) {
    if (event.hasField("switchRate")) {
      threadContextSwitchRate.set(event.getFloat("switchRate"), Attributes.empty());
    }
  }

  // ---- jdk.GCHeapSummary ----
  void onGcHeapSummary(RecordedEvent event) {
    if (event.hasField("heapUsed")) {
      memoryUsed.set(event.getLong("heapUsed"), HEAP);
    }
    // heapSpace is a RecordedObject with committedSize / reservedSize.
    if (event.hasField("heapSpace")) {
      Object space = event.getValue("heapSpace");
      if (space instanceof jdk.jfr.consumer.RecordedObject) {
        jdk.jfr.consumer.RecordedObject heapSpace = (jdk.jfr.consumer.RecordedObject) space;
        if (heapSpace.hasField("committedSize")) {
          memoryCommitted.set(heapSpace.getLong("committedSize"), HEAP);
        }
        if (heapSpace.hasField("reservedSize")) {
          memoryLimit.set(heapSpace.getLong("reservedSize"), HEAP);
        }
      }
    }
  }

  // ---- jdk.MetaspaceSummary ----
  void onMetaspaceSummary(RecordedEvent event) {
    recordMetaspacePool(event, "metaspace", "Metaspace");
    recordMetaspacePool(event, "classSpace", "Compressed Class Space");
    recordMetaspacePool(event, "dataSpace", "Metaspace Data");
  }

  private void recordMetaspacePool(RecordedEvent event, String field, String poolName) {
    if (!event.hasField(field)) {
      return;
    }
    Object value = event.getValue(field);
    if (!(value instanceof jdk.jfr.consumer.RecordedObject)) {
      return;
    }
    jdk.jfr.consumer.RecordedObject pool = (jdk.jfr.consumer.RecordedObject) value;
    Attributes attrs =
        Attributes.of(JVM_MEMORY_TYPE, "non_heap", JVM_MEMORY_POOL_NAME, poolName);
    if (pool.hasField("used")) {
      memoryUsed.set(pool.getLong("used"), attrs);
    }
    if (pool.hasField("committed")) {
      memoryCommitted.set(pool.getLong("committed"), attrs);
    }
    if (pool.hasField("reserved")) {
      memoryLimit.set(pool.getLong("reserved"), attrs);
    }
  }

  // ---- jdk.GarbageCollection ----
  void onGarbageCollection(RecordedEvent event) {
    String name = event.hasField("name") ? event.getString("name") : "unknown";
    String cause = event.hasField("cause") ? event.getString("cause") : "unknown";

    Attributes attrs = Attributes.of(JVM_GC_NAME, name, JVM_GC_CAUSE, cause);
    gcDuration.record(seconds(event), attrs);

    if (event.hasField("longestPause")) {
      gcLongestPause.set(event.getDuration("longestPause").toNanos() / 1_000_000_000.0, attrs);
    }
  }

  // ---- jdk.G1GarbageCollection ----
  void onG1GarbageCollection(RecordedEvent event) {
    gcDuration.record(
        seconds(event), Attributes.of(JVM_GC_NAME, "G1", JVM_GC_ACTION, "collection"));
  }

  // ---- jdk.ObjectAllocationInNewTLAB / OutsideTLAB ----
  void onAllocationInNewTlab(RecordedEvent event) {
    recordAllocation(event, "in_new_tlab", "tlabSize");
  }

  void onAllocationOutsideTlab(RecordedEvent event) {
    recordAllocation(event, "outside_tlab", "allocationSize");
  }

  private void recordAllocation(RecordedEvent event, String location, String sizeField) {
    long size = 0;
    if (event.hasField(sizeField)) {
      size = event.getLong(sizeField);
    } else if (event.hasField("allocationSize")) {
      size = event.getLong("allocationSize");
    }
    if (size <= 0) {
      return;
    }
    memoryAllocation.record(
        size, Attributes.of(THREAD_NAME, threadName(event), ALLOC_LOCATION, location));
  }

  // ---- jdk.SocketRead ----
  void onSocketRead(RecordedEvent event) {
    Attributes attrs = Attributes.of(THREAD_NAME, threadName(event));
    if (event.hasField("bytesRead")) {
      socketReadBytes.record(event.getLong("bytesRead"), attrs);
    }
    socketReadDuration.record(seconds(event), attrs);
  }

  // ---- helpers ----
  private static double seconds(RecordedEvent event) {
    return event.getDuration().toNanos() / 1_000_000_000.0;
  }

  private static String threadName(RecordedEvent event) {
    RecordedThread thread = event.getThread();
    if (thread == null) {
      return "unknown";
    }
    String name = thread.getJavaName();
    return name != null ? name : thread.getOSName();
  }
}

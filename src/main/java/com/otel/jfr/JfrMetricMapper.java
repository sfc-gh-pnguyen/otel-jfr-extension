package com.otel.jfr;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;

/**
 * Maps JFR {@code jdk.*} events onto OpenTelemetry instruments.
 *
 * <p>Naming follows real OTel semantic conventions where they exist
 * ({@code jvm.memory.*}, {@code jvm.gc.*}, {@code process.cpu.utilization},
 * {@code system.cpu.utilization}, {@code cpu.mode}), and a clearly-labelled
 * custom {@code jvm.jfr.*} namespace where they do not.
 *
 * <p>Durations come from {@link RecordedEvent#getDuration()}, which is a real
 * {@code java.time.Duration}, so units are exact rather than inferred.
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
  private static final AttributeKey<String> JVM_GC_GENERATION =
      AttributeKey.stringKey("jvm.gc.generation");
  private static final AttributeKey<String> MONITOR_CLASS =
      AttributeKey.stringKey("jvm.jfr.monitor.class");
  private static final AttributeKey<Boolean> MONITOR_TIMED_OUT =
      AttributeKey.booleanKey("jvm.jfr.monitor.timed_out");
  private static final AttributeKey<String> JIT_COMPILER = AttributeKey.stringKey("jvm.jit.compiler");
  private static final AttributeKey<Long> JIT_LEVEL = AttributeKey.longKey("jvm.jit.compile_level");
  private static final AttributeKey<Boolean> JIT_SUCCEEDED =
      AttributeKey.booleanKey("jvm.jit.succeeded");
  private static final AttributeKey<String> VALUE_BASED_CLASS =
      AttributeKey.stringKey("jvm.jfr.value_based_class");

  /**
   * Collectors whose jdk.GarbageCollection events are young/minor collections.
   * Everything else counts as major.
   */
  private static final Set<String> MINOR_GC_NAMES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "DefNew",
                  "G1New",
                  "ParallelScavenge",
                  "ParNew",
                  "PSMarkSweep",
                  "Z",
                  "ZGC Minor",
                  "Shenandoah")));

  private static final Attributes CPU_USER = Attributes.of(CPU_MODE, "user");
  private static final Attributes CPU_SYSTEM = Attributes.of(CPU_MODE, "system");
  private static final Attributes CPU_TOTAL = Attributes.of(CPU_MODE, "total");
  private static final Attributes HEAP = Attributes.of(JVM_MEMORY_TYPE, "heap");

  /**
   * Histogram boundaries sized to the data. The SDK default (0, 5, 10, 25 ...
   * 10000) suits milliseconds; with second-valued durations nearly every
   * sample lands in the first bucket and percentiles become meaningless.
   */
  private static final List<Double> SECONDS_BUCKETS =
      Collections.unmodifiableList(
          Arrays.asList(
              0.0001, 0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0,
              2.5, 5.0, 10.0));

  private static final List<Double> BYTES_BUCKETS =
      Collections.unmodifiableList(
          Arrays.asList(
              64.0, 256.0, 1024.0, 4096.0, 16384.0, 65536.0, 262144.0, 1048576.0, 4194304.0,
              16777216.0, 67108864.0, 268435456.0));

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
  private final DoubleHistogram socketWriteBytes;
  private final DoubleHistogram socketWriteDuration;
  private final DoubleHistogram allocationRequiringGc;
  private final DoubleHistogram gcHeapTransitionDuration;
  private final DoubleHistogram monitorWaitDuration;
  private final DoubleHistogram monitorEnterDuration;
  private final DoubleHistogram jitCompilationDuration;
  private final LongCounter syncOnValueBasedClass;
  private final DoubleCounter gcPauseTime;
  private final DoubleCounter jitCompilationTime;

  /** Null when thread-name normalization is disabled (raw names are used). */
  private final ThreadNameNormalizer threadNameNormalizer;

  /** "Before GC" GCHeapSummary timestamp per gcId, awaiting the matching "After GC". */
  private final Map<Long, Instant> gcBeforeById = new ConcurrentHashMap<>();

  JfrMetricMapper(OpenTelemetry openTelemetry, boolean normalizeThreadNames) {
    Meter meter = openTelemetry.getMeter("com.otel.jfr");
    this.threadNameNormalizer = normalizeThreadNames ? new ThreadNameNormalizer() : null;

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
            .setExplicitBucketBoundariesAdvice(SECONDS_BUCKETS)
            .build();
    memoryAllocation =
        meter
            .histogramBuilder("jfr.jvm.memory.allocation")
            .setUnit("By")
            .setDescription("Object allocation sizes, from jdk.ObjectAllocation* events")
            .setExplicitBucketBoundariesAdvice(BYTES_BUCKETS)
            .build();
    socketReadBytes =
        meter
            .histogramBuilder("jfr.jvm.socket.read.bytes")
            .setUnit("By")
            .setDescription("Bytes read per socket read, from jdk.SocketRead")
            .setExplicitBucketBoundariesAdvice(BYTES_BUCKETS)
            .build();
    socketReadDuration =
        meter
            .histogramBuilder("jfr.jvm.socket.read.duration")
            .setUnit("s")
            .setDescription("Socket read duration, from jdk.SocketRead")
            .setExplicitBucketBoundariesAdvice(SECONDS_BUCKETS)
            .build();
    socketWriteBytes =
        meter
            .histogramBuilder("jfr.jvm.socket.write.bytes")
            .setUnit("By")
            .setDescription("Bytes written per socket write, from jdk.SocketWrite")
            .setExplicitBucketBoundariesAdvice(BYTES_BUCKETS)
            .build();
    socketWriteDuration =
        meter
            .histogramBuilder("jfr.jvm.socket.write.duration")
            .setUnit("s")
            .setDescription("Socket write duration, from jdk.SocketWrite")
            .setExplicitBucketBoundariesAdvice(SECONDS_BUCKETS)
            .build();
    allocationRequiringGc =
        meter
            .histogramBuilder("jfr.jvm.gc.allocation_requiring_gc.size")
            .setUnit("By")
            .setDescription(
                "Size of the allocation that could not be satisfied and triggered a GC, "
                    + "from jdk.AllocationRequiringGC")
            .setExplicitBucketBoundariesAdvice(BYTES_BUCKETS)
            .build();
    gcHeapTransitionDuration =
        meter
            .histogramBuilder("jfr.jvm.gc.heap_transition.duration")
            .setUnit("s")
            .setDescription(
                "Time between the Before GC and After GC heap summaries of one collection, "
                    + "from jdk.GCHeapSummary (collector-independent)")
            .setExplicitBucketBoundariesAdvice(SECONDS_BUCKETS)
            .build();
    monitorWaitDuration =
        meter
            .histogramBuilder("jfr.jvm.monitor.wait.duration")
            .setUnit("s")
            .setDescription("Object.wait() time above threshold, from jdk.JavaMonitorWait")
            .setExplicitBucketBoundariesAdvice(SECONDS_BUCKETS)
            .build();
    monitorEnterDuration =
        meter
            .histogramBuilder("jfr.jvm.monitor.enter.duration")
            .setUnit("s")
            .setDescription(
                "Time blocked entering a contended synchronized monitor, "
                    + "from jdk.JavaMonitorEnter")
            .setExplicitBucketBoundariesAdvice(SECONDS_BUCKETS)
            .build();
    jitCompilationDuration =
        meter
            .histogramBuilder("jfr.jvm.jit.compilation.duration")
            .setUnit("s")
            .setDescription("JIT compilation time above threshold, from jdk.Compilation")
            .setExplicitBucketBoundariesAdvice(SECONDS_BUCKETS)
            .build();
    syncOnValueBasedClass =
        meter
            .counterBuilder("jfr.jvm.sync_on_value_based_class")
            .setUnit("{event}")
            .setDescription(
                "Synchronization on value-based class instances (e.g. Integer), from "
                    + "jdk.SyncOnValueBasedClass")
            .build();
    gcPauseTime =
        meter
            .counterBuilder("jfr.jvm.gc.pause.time")
            .ofDoubles()
            .setUnit("s")
            .setDescription(
                "Total time spent in garbage collection (monotonic sum), from "
                    + "jdk.GarbageCollection")
            .build();
    jitCompilationTime =
        meter
            .counterBuilder("jfr.jvm.jit.compilation.time")
            .ofDoubles()
            .setUnit("s")
            .setDescription(
                "Total JIT compilation time above threshold (monotonic sum), from "
                    + "jdk.Compilation")
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
  // The event's own thread is the periodic sampler; the measured thread is
  // carried in the "thread" field.
  void onThreadAllocation(RecordedEvent event) {
    if (event.hasField("allocated")) {
      RecordedThread measured = event.hasField("thread") ? event.getThread("thread") : null;
      threadMemoryAllocated.set(
          event.getLong("allocated"),
          Attributes.of(
              THREAD_NAME, measured != null ? threadName(measured) : threadName(event)));
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
    recordHeapTransition(event);
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

  /** Pairs Before/After GC summaries by gcId into a collection duration. */
  private void recordHeapTransition(RecordedEvent event) {
    if (!event.hasField("gcId") || !event.hasField("when")) {
      return;
    }
    long gcId = event.getLong("gcId");
    String when = event.getString("when");
    if ("Before GC".equals(when)) {
      // Bound the map: an unmatched Before (e.g. stream ended mid-GC) must not leak.
      if (gcBeforeById.size() > 1000) {
        gcBeforeById.clear();
      }
      gcBeforeById.put(gcId, event.getStartTime());
    } else if ("After GC".equals(when)) {
      Instant before = gcBeforeById.remove(gcId);
      if (before != null) {
        long nanos = java.time.Duration.between(before, event.getStartTime()).toNanos();
        if (nanos >= 0) {
          gcHeapTransitionDuration.record(nanos / 1_000_000_000.0, Attributes.empty());
        }
      }
    }
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

    String generation = MINOR_GC_NAMES.contains(name) ? "minor" : "major";
    Attributes attrs =
        Attributes.of(JVM_GC_NAME, name, JVM_GC_CAUSE, cause, JVM_GC_GENERATION, generation);
    gcDuration.record(seconds(event), attrs);
    gcPauseTime.add(seconds(event), Attributes.of(JVM_GC_NAME, name, JVM_GC_GENERATION, generation));

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

  // ---- jdk.SocketWrite ----
  void onSocketWrite(RecordedEvent event) {
    Attributes attrs = Attributes.of(THREAD_NAME, threadName(event));
    if (event.hasField("bytesWritten")) {
      socketWriteBytes.record(event.getLong("bytesWritten"), attrs);
    }
    socketWriteDuration.record(seconds(event), attrs);
  }

  // ---- jdk.AllocationRequiringGC ----
  void onAllocationRequiringGc(RecordedEvent event) {
    if (event.hasField("size")) {
      allocationRequiringGc.record(
          event.getLong("size"), Attributes.of(THREAD_NAME, threadName(event)));
    }
  }

  // ---- jdk.JavaMonitorWait ----
  void onMonitorWait(RecordedEvent event) {
    boolean timedOut = event.hasField("timedOut") && event.getBoolean("timedOut");
    monitorWaitDuration.record(
        seconds(event),
        Attributes.of(
            THREAD_NAME, threadName(event),
            MONITOR_CLASS, className(event, "monitorClass"),
            MONITOR_TIMED_OUT, timedOut));
  }

  // ---- jdk.JavaMonitorEnter ----
  void onMonitorEnter(RecordedEvent event) {
    monitorEnterDuration.record(
        seconds(event),
        Attributes.of(
            THREAD_NAME, threadName(event), MONITOR_CLASS, className(event, "monitorClass")));
  }

  // ---- jdk.Compilation ----
  void onCompilation(RecordedEvent event) {
    // JDK spells the field "succeded"; accept the corrected spelling too.
    boolean succeeded = true;
    if (event.hasField("succeded")) {
      succeeded = event.getBoolean("succeded");
    } else if (event.hasField("succeeded")) {
      succeeded = event.getBoolean("succeeded");
    }
    String compiler = event.hasField("compiler") ? event.getString("compiler") : null;
    long level = event.hasField("compileLevel") ? event.getShort("compileLevel") : -1L;
    jitCompilationDuration.record(
        seconds(event),
        Attributes.of(
            JIT_COMPILER, compiler != null ? compiler : "unknown",
            JIT_LEVEL, level,
            JIT_SUCCEEDED, succeeded));
    jitCompilationTime.add(
        seconds(event), Attributes.of(JIT_COMPILER, compiler != null ? compiler : "unknown"));
  }

  // ---- jdk.SyncOnValueBasedClass (JDK 16+; jdk.SyncOnPrimitiveWrapper in early builds) ----
  void onSyncOnValueBasedClass(RecordedEvent event) {
    syncOnValueBasedClass.add(
        1, Attributes.of(VALUE_BASED_CLASS, className(event, "valueBasedClass", "boxClass")));
  }

  // ---- helpers ----
  private static double seconds(RecordedEvent event) {
    return event.getDuration().toNanos() / 1_000_000_000.0;
  }

  private String threadName(RecordedEvent event) {
    return threadName(event.getThread());
  }

  private String threadName(RecordedThread thread) {
    if (thread == null) {
      return "unknown";
    }
    String name = thread.getJavaName();
    if (name == null) {
      name = thread.getOSName();
    }
    if (name == null) {
      return "unknown";
    }
    return threadNameNormalizer != null ? threadNameNormalizer.normalize(name) : name;
  }

  /** Name of the first present RecordedClass field, or "unknown". */
  private static String className(RecordedEvent event, String... fields) {
    for (String field : fields) {
      if (event.hasField(field)) {
        RecordedClass c = event.getClass(field);
        if (c != null && c.getName() != null) {
          return c.getName();
        }
      }
    }
    return "unknown";
  }
}

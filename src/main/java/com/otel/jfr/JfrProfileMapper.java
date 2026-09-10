package com.otel.jfr;

import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.profiles.v1development.Function;
import io.opentelemetry.proto.profiles.v1development.KeyValueAndUnit;
import io.opentelemetry.proto.profiles.v1development.Line;
import io.opentelemetry.proto.profiles.v1development.Location;
import io.opentelemetry.proto.profiles.v1development.Profile;
import io.opentelemetry.proto.profiles.v1development.ProfilesDictionary;
import io.opentelemetry.proto.profiles.v1development.ResourceProfiles;
import io.opentelemetry.proto.profiles.v1development.Sample;
import io.opentelemetry.proto.profiles.v1development.ScopeProfiles;
import io.opentelemetry.proto.profiles.v1development.Stack;
import io.opentelemetry.proto.profiles.v1development.ValueType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;

/**
 * Accumulates {@code jdk.ExecutionSample} events into an aggregated OTLP
 * profile and, on flush, produces an {@link ExportProfilesServiceRequest}.
 *
 * <p>Targets the OTLP profiles v1development model (public Alpha, 2026), which
 * factors the pprof tables (strings, functions, locations, stacks) into a
 * single {@link ProfilesDictionary} shared at the request level; each
 * {@link Sample} references a {@link Stack} by index and carries occurrence
 * counts. This mapper interns everything and de-duplicates identical stacks.
 *
 * <p>Sample type is {@code samples/count}; period type is {@code cpu/nanoseconds}
 * with the configured sampling period. This is a minimal but well-formed CPU
 * profile: value = number of times a given stack was sampled in the window.
 *
 * <p>NOTE: v1development is explicitly unstable; the message shape can change
 * between releases. Built against opentelemetry-proto 1.11.0-alpha.
 *
 * <p>Thread-safety: events arrive on the JFR dispatch thread; flush runs on a
 * scheduler thread. All mutation is guarded by {@code this}.
 */
final class JfrProfileMapper {

  private static final String SCOPE_NAME = "com.otel.jfr";
  private static final String ATTR_THREAD_NAME = "thread.name";

  private final long samplingPeriodNanos;
  private final io.opentelemetry.proto.resource.v1.Resource protoResource;

  // ---- interned tables (rebuilt each window) ----
  private final Map<String, Integer> stringTable = new LinkedHashMap<>();
  private final Map<String, Integer> functionTable = new LinkedHashMap<>();
  private final List<Function> functions = new ArrayList<>();
  private final Map<String, Integer> locationTable = new LinkedHashMap<>();
  private final List<Location> locations = new ArrayList<>();
  private final Map<String, Integer> stackTable = new LinkedHashMap<>();
  private final List<Stack> stacks = new ArrayList<>();
  private final Map<String, Integer> attributeTable = new LinkedHashMap<>();
  private final List<KeyValueAndUnit> attributes = new ArrayList<>();

  // sample key "stackIndex:attrIndex" -> occurrence count
  private final Map<String, long[]> sampleCounts = new LinkedHashMap<>();
  // sample key -> [stackIndex, attrIndex]
  private final Map<String, int[]> sampleRefs = new LinkedHashMap<>();

  private long windowStartNanos = 0L;

  JfrProfileMapper(long samplingPeriodMs, io.opentelemetry.proto.resource.v1.Resource protoResource) {
    this.samplingPeriodNanos = Math.max(samplingPeriodMs, 1L) * 1_000_000L;
    this.protoResource = protoResource;
    intern(""); // string table index 0 must be the empty string
  }

  synchronized void onExecutionSample(RecordedEvent event) {
    RecordedStackTrace stackTrace = event.getStackTrace();
    if (stackTrace == null) {
      return;
    }
    List<RecordedFrame> frames = stackTrace.getFrames();
    if (frames.isEmpty()) {
      return;
    }
    if (windowStartNanos == 0L) {
      windowStartNanos = toNanos(event.getStartTime());
    }

    // Build (or reuse) the Stack: pprof convention is leaf-first, which is the
    // order JFR already returns frames in.
    List<Integer> locationIndices = new ArrayList<>(frames.size());
    for (RecordedFrame frame : frames) {
      locationIndices.add(locationIndex(frame));
    }
    int stackIndex = stackIndex(locationIndices);
    int attrIndex = threadAttributeIndex(threadName(event));

    String key = stackIndex + ":" + attrIndex;
    sampleCounts.computeIfAbsent(key, k -> new long[] {0L})[0]++;
    sampleRefs.putIfAbsent(key, new int[] {stackIndex, attrIndex});
  }

  /**
   * Build an export request for the accumulated samples and reset the window.
   * Returns null when nothing was captured.
   */
  synchronized ExportProfilesServiceRequest flush() {
    if (sampleCounts.isEmpty()) {
      return null;
    }
    long now = toNanos(Instant.now());
    long duration = Math.max(now - windowStartNanos, 0L);

    ValueType sampleType =
        ValueType.newBuilder()
            .setTypeStrindex(intern("samples"))
            .setUnitStrindex(intern("count"))
            .build();
    ValueType periodType =
        ValueType.newBuilder()
            .setTypeStrindex(intern("cpu"))
            .setUnitStrindex(intern("nanoseconds"))
            .build();

    Profile.Builder profile =
        Profile.newBuilder()
            .setSampleType(sampleType)
            .setPeriodType(periodType)
            .setPeriod(samplingPeriodNanos)
            .setTimeUnixNano(windowStartNanos)
            .setDurationNano(duration);

    for (Map.Entry<String, long[]> e : sampleCounts.entrySet()) {
      int[] refs = sampleRefs.get(e.getKey());
      profile.addSamples(
          Sample.newBuilder()
              .setStackIndex(refs[0])
              .addValues(e.getValue()[0])
              .addAttributeIndices(refs[1])
              .build());
    }

    ProfilesDictionary dictionary =
        ProfilesDictionary.newBuilder()
            .addAllStringTable(stringTable.keySet())
            .addAllFunctionTable(functions)
            .addAllLocationTable(locations)
            .addAllStackTable(stacks)
            .addAllAttributeTable(attributes)
            .build();

    ResourceProfiles.Builder resourceProfiles = ResourceProfiles.newBuilder();
    if (protoResource != null) {
      resourceProfiles.setResource(protoResource);
    }
    resourceProfiles.addScopeProfiles(
        ScopeProfiles.newBuilder()
            .setScope(InstrumentationScope.newBuilder().setName(SCOPE_NAME).build())
            .addProfiles(profile.build())
            .build());

    ExportProfilesServiceRequest request =
        ExportProfilesServiceRequest.newBuilder()
            .setDictionary(dictionary)
            .addResourceProfiles(resourceProfiles.build())
            .build();

    reset();
    return request;
  }

  private void reset() {
    stringTable.clear();
    functionTable.clear();
    functions.clear();
    locationTable.clear();
    locations.clear();
    stackTable.clear();
    stacks.clear();
    attributeTable.clear();
    attributes.clear();
    sampleCounts.clear();
    sampleRefs.clear();
    windowStartNanos = 0L;
    intern(""); // re-seed empty string at index 0
  }

  // ---- interning helpers ----

  private int intern(String s) {
    String key = s != null ? s : "";
    Integer idx = stringTable.get(key);
    if (idx != null) {
      return idx;
    }
    int next = stringTable.size();
    stringTable.put(key, next);
    return next;
  }

  private int functionIndex(String className, String methodName) {
    String key = className + "\u0000" + methodName;
    Integer idx = functionTable.get(key);
    if (idx != null) {
      return idx;
    }
    int next = functions.size();
    functions.add(Function.newBuilder().setNameStrindex(intern(className + "." + methodName)).build());
    functionTable.put(key, next);
    return next;
  }

  private int locationIndex(RecordedFrame frame) {
    String className = "unknown";
    String methodName = "unknown";
    RecordedMethod method = frame.getMethod();
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
    int functionIndex = functionIndex(className, methodName);

    String key = functionIndex + ":" + line;
    Integer idx = locationTable.get(key);
    if (idx != null) {
      return idx;
    }
    int next = locations.size();
    Line.Builder lineBuilder = Line.newBuilder().setFunctionIndex(functionIndex);
    if (line > 0) {
      lineBuilder.setLine(line);
    }
    locations.add(Location.newBuilder().addLines(lineBuilder.build()).build());
    locationTable.put(key, next);
    return next;
  }

  private int stackIndex(List<Integer> locationIndices) {
    String key = locationIndices.toString();
    Integer idx = stackTable.get(key);
    if (idx != null) {
      return idx;
    }
    int next = stacks.size();
    stacks.add(Stack.newBuilder().addAllLocationIndices(locationIndices).build());
    stackTable.put(key, next);
    return next;
  }

  private int threadAttributeIndex(String threadName) {
    Integer idx = attributeTable.get(threadName);
    if (idx != null) {
      return idx;
    }
    int next = attributes.size();
    attributes.add(
        KeyValueAndUnit.newBuilder()
            .setKeyStrindex(intern(ATTR_THREAD_NAME))
            .setValue(AnyValue.newBuilder().setStringValue(threadName).build())
            .build());
    attributeTable.put(threadName, next);
    return next;
  }

  private static long toNanos(Instant instant) {
    return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
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

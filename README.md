# otel-jfr-extension

An [OpenTelemetry Java agent](https://opentelemetry.io/docs/zero-code/java/agent/)
**extension** that adds JFR (Java Flight Recorder) continuous-profiling telemetry
to any app already running under the stock OTel Java agent — and forwards it as
standard OTLP metrics and trace spans, so it lands in any OTLP
backend) with no separate profiler or agent fork.

## What it does — and what it deliberately does NOT do

**Does:** while the JVM runs, it consumes JFR events in-process via the JDK's own
`jdk.jfr.consumer.RecordingStream` API and converts them to OpenTelemetry:

- **Metrics** — CPU load, per-thread CPU, heap/metaspace usage, GC duration,
  allocation, socket reads, thread allocation & context-switch rate. All names
  are prefixed with `jfr.` (e.g. `jfr.jvm.memory.used`, `jfr.jvm.gc.duration`,
  `jfr.process.cpu.utilization`) so everything this extension emits is findable
  with one `jfr.` search. See the Telemetry reference for the full list and the
  semconv tradeoff.
- **Trace spans** — `jdk.ExecutionSample` stack samples become one trace per
  sample, nested caller→callee, so they render as a flame/waterfall in a trace
  viewer. Timestamps and stack frames are **real** (straight from JFR), and each
  span is tagged `jfr.synthetic_duration=true` because the per-frame *width* is a
  sampling inference, not measured latency — do not sum these as if they were.
- **Profiles (OTLP profiles signal)** — alternatively, with
  `-Dotel.instrumentation.jfr.profile=true`, `jdk.ExecutionSample` stack samples
  are aggregated and emitted as the OpenTelemetry **profiles** signal
  (`POST /v1development/profiles`) instead of spans. This is a future-facing,
  span-or-profile switch. See "Profiles output mode" below — the profiles signal
  is **Alpha / "development"** and most backends cannot ingest it yet.

**Does NOT:** configure exporters, endpoints, protocols, or headers. **Splitting
signals to different endpoints is already native to the OpenTelemetry SDK** — you
do not need this extension (or any custom code) for that. Just set the standard
per-signal properties/env vars (see below). JFR telemetry is emitted through the
same SDK the agent already built, so it automatically inherits whatever routing
you configure.

## Requirements

- **JDK 11+** to run (JFR streaming API requires 14+; tested on 17). The build
  uses only `javac` + `jar` + `curl`.
- A copy of `opentelemetry-javaagent.jar` (the standard agent). The extension is
  built against the OTel SDK version bundled in your agent.

## Build

```bash
./build.sh
```

This fetches the OpenTelemetry API/SDK jars (compile-time only), compiles, and
produces `otel-jfr-extension.jar`. Those OTel jars are **not** bundled into
the output — the agent provides them at runtime. The one thing that *is* bundled
is `opentelemetry-proto` (the generated OTLP profiles message classes), needed
only for profiles output mode; `protobuf-java` is compile-only because the agent
already ships it at runtime (see `build.sh` for the version-alignment notes).

If your agent bundles a different OTel SDK version than the default (`1.64.0`),
override it. Check your agent's version with:

```bash
unzip -p opentelemetry-javaagent.jar \
  io/opentelemetry/javaagent/shaded/io/opentelemetry/api/version.properties
```

then:

```bash
OTEL_VERSION=1.64.0 ./build.sh
```

## Run (PowerShell)

Launch your app (`test.jar`) with the OTel agent **and** this extension. Every
`-D` flag is quoted — PowerShell mangles unquoted args that contain `=` or `.`,
which silently breaks the launch.

```powershell
java `
  "-javaagent:C:\path\to\opentelemetry-javaagent.jar" `
  "-Dotel.javaagent.extensions=C:\path\to\otel-jfr-extension.jar" `
  "-Dotel.service.name=APP-12345 | Spring Boot | DEV" `
  "-Dotel.resource.attributes=deployment.environment.name=localhost,service.namespace=macbook-pro,service.version=0.0.1" `
  "-Dotel.java.disabled.resource.providers=io.opentelemetry.instrumentation.resources.ProcessResourceProvider" `
  "-Dotel.instrumentation.jfr.enabled=true" `
  "-Dotel.exporter.otlp.protocol=http/protobuf" `
  "-Dotel.exporter.otlp.traces.endpoint=https://1234567890.otlp.example.com/v1/traces" `
  "-Dotel.exporter.otlp.metrics.endpoint=https://1234567890.otlp.example.com/v1/metrics" `
  "-Dotel.exporter.otlp.logs.endpoint=https://1234567890.otlp.example.com/v1/logs" `
  "-Dotel.exporter.otlp.headers=Authorization=Bearer <token>" `
  "-jar" "test.jar"
```

> **Redacting the launch command / secrets.** The line
> `-Dotel.java.disabled.resource.providers=io.opentelemetry.instrumentation.resources.ProcessResourceProvider`
> disables the OTel Java agent's `ProcessResourceProvider`, which otherwise
> attaches `process.command_args` / `process.command_line` (your full JVM
> invocation) as resource attributes on **every** span, metric, and profile —
> leaking any endpoints or API keys passed as `-D` flags. This is an agent-side
> resource provider, not part of this extension. To keep just the process
> metadata but drop those two keys, use
> `-Dotel.experimental.resource.disabled-keys=process.command_args,process.command_line`
> instead. Either way, prefer passing secrets via env vars
> (`OTEL_EXPORTER_OTLP_HEADERS`, `OTEL_EXPORTER_OTLP_ENDPOINT`) rather than the
> command line, since CLI args are also visible via `ps` / `/proc/<pid>/cmdline`.

### Quick local smoke test (print telemetry to the console instead of a remote backend)

```powershell
java `
  "-javaagent:C:\path\to\opentelemetry-javaagent.jar" `
  "-Dotel.javaagent.extensions=C:\path\to\otel-jfr-extension.jar" `
  "-Dotel.service.name=jfr-ext-smoketest" `
  "-Dotel.instrumentation.jfr.enabled=true" `
  "-Dotel.traces.exporter=logging" `
  "-Dotel.metrics.exporter=logging" `
  "-Dotel.logs.exporter=none" `
  "-jar" "test.jar"
```

Look for `otel-jfr-extension: enabled ...` then
`otel-jfr-extension: JFR recording stream started` in the console, followed by
`jfr.*` metrics and `jfr.stack.sample: ...` / `jfr.<Class>.<method>` spans from the logging
exporter. (Send some traffic to the app so `jdk.ExecutionSample` has something to
capture.)

## Configuration

All options use the OTel-idiomatic `otel.instrumentation.<name>.*` namespace and
can also be set as environment variables (uppercase, dots/hyphens → underscores,
e.g. `OTEL_INSTRUMENTATION_JFR_ENABLED`).

| Property | Default | Description |
| --- | --- | --- |
| `otel.instrumentation.jfr.enabled` | `false` | Master switch. Extension is inert unless `true`. |
| `otel.instrumentation.jfr.profiling-level` | `standard` | Single volume dial: `full` / `standard` / `light` / `off`. See the table below. |
| `otel.instrumentation.jfr.profile` | `false` | `false` = execution samples → trace spans; `true` = → OTLP profiles signal. |
| `otel.instrumentation.jfr.stack-log` | `true` | Span mode only: also emit each sample's full stack as a log record correlated to its `jfr.stack.sample` span (shows under **Logs related to span**). Requires the logs pipeline. |
| `otel.instrumentation.jfr.profiling-enabled` | `true` | Hard gate on execution samples, applied on top of the level (set `false` for metrics only). |
| `otel.instrumentation.jfr.sampling-period-ms` | *(level's value)* | Explicit override of the level's execution-sample period. |
| `otel.instrumentation.jfr.profile.flush-interval-ms` | `10000` | Profile-mode only: how often the aggregated profile is flushed/exported. |

### Volume tiers — `profiling-level`

One dial controls how much JFR data the extension collects. `standard` is the
default when the property is omitted.

| Knob | `full` | `standard` (default) | `light` | `off` |
| --- | --- | --- | --- | --- |
| Metrics (CPU, heap, GC, metaspace) | on | on | on | off |
| Metric period | 1s | 2s | 5s | — |
| Per-thread metrics (ThreadCPULoad, ThreadAllocationStatistics) | on | on | **off** | off |
| Allocation events (TLAB — highest volume) | on | **off** | off | off |
| Socket read events | on | on | **off** | off |
| Execution samples (spans/profiles) | on | on | on | off |
| Sampling period | 10ms | 20ms | 50ms | — |

- `full` — everything, finest sampling. Highest volume and overhead; use for
  short, focused investigations.
- `standard` — balanced default. Drops the very-high-volume allocation events
  but keeps CPU, heap, GC, socket, per-thread metrics, and 20ms profiling.
- `light` — minimal footprint: coarse 50ms profiling, no per-thread/allocation/
  socket detail, 5s metric period. Good for always-on, low-overhead collection.
- `off` — collects nothing (extension loads but stays idle). Useful to mute
  without removing the `-D` flags.

`sampling-period-ms`, if set, overrides whatever period the level implies.
`profiling-enabled=false` forces execution samples off regardless of level
(metrics still follow the level).

### Endpoint / protocol / header routing (native OTel — not this extension)

These are standard OpenTelemetry SDK properties, honored by the agent itself.
Per-signal values override the generic `otel.exporter.otlp.*`; anything unset
falls back to it.

| Property | Env var |
| --- | --- |
| `otel.exporter.otlp.traces.endpoint` | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` |
| `otel.exporter.otlp.metrics.endpoint` | `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` |
| `otel.exporter.otlp.logs.endpoint` | `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` |
| `otel.exporter.otlp.{traces,metrics,logs}.protocol` | `OTEL_EXPORTER_OTLP_*_PROTOCOL` |
| `otel.exporter.otlp.{traces,metrics,logs}.headers` | `OTEL_EXPORTER_OTLP_*_HEADERS` |

> Note: with the generic `otel.exporter.otlp.endpoint`, the SDK auto-appends
> `/v1/traces` etc. With the **per-signal** endpoints you must include the full
> path yourself (`.../v1/traces`).

## Stack as a span-correlated log (`stack-log`)

In span mode (`profile=false`), the nested `jfr.<Class>.<method>:<line>` spans draw
the flame chart. With `otel.instrumentation.jfr.stack-log=true` (the **default**),
each sample *additionally* emits **one log record** carrying that sample's full
call stack, correlated by `trace_id` + `span_id` to its `jfr.stack.sample` root
span. In a trace viewer it appears under **"Logs related to span"** when you
select that span — so you get both the flame chart *and* a copy-pasteable stack
list, mirroring a JFR viewer's stack panel:

```
org/springframework/boot/loader/jar/JarURLConnection.connect:97
org/springframework/boot/loader/jar/JarURLConnection.getInputStream:163
...
java/lang/Thread.run:840
```

Frames are top-of-stack first and formatted `pkg/Class.method:line` to match the
JFR viewer. The record also carries `jfr.thread.name` and `jfr.stack.depth`.

Notes:

- **Logs pipeline required.** The record only ships if a logs exporter is
  configured (e.g. an `otel.exporter.otlp.logs.endpoint`, or `otel.logs.exporter`
  set to something other than `none`). The console smoke test above sets
  `otel.logs.exporter=none`, so flip it to `logging` to see the stack log locally.
- **Correlates to the JFR sample span, not your app's HTTP span.**
  `jdk.ExecutionSample` carries no application span context, so the log attaches
  to the extension's `jfr.stack.sample` span — not to the Tomcat `GET /` span.
- **Volume.** One log per sample per active thread (≈50/s/thread at 20 ms
  sampling). Set `otel.instrumentation.jfr.stack-log=false` to turn it off, or
  pair it with `profiling-level=light` (50 ms) to reduce the rate.
- **Span mode only.** In profile mode (`profile=true`) there are no spans to
  correlate to, so this flag is a no-op.

## Profiles output mode (experimental)

With `-Dotel.instrumentation.jfr.profile=true`, execution samples are emitted as
the OpenTelemetry **profiles** signal instead of trace spans. Everything else
(metrics, the on/off switch, sampling period) behaves the same; this only changes
the *output format* of `jdk.ExecutionSample`.

Because the OpenTelemetry Java agent does **not** autoconfigure a profiles
pipeline, this extension builds and drives its own OTLP/HTTP profiles exporter,
reading these properties itself:

| Property | Env var | Default |
| --- | --- | --- |
| `otel.exporter.otlp.profiles.endpoint` | `OTEL_EXPORTER_OTLP_PROFILES_ENDPOINT` | base OTLP endpoint + `/v1development/profiles` |
| `otel.exporter.otlp.profiles.headers` | `OTEL_EXPORTER_OTLP_PROFILES_HEADERS` | falls back to `otel.exporter.otlp.headers` |

The profile is a pprof-derived `Profile` (sample type `samples/count`, period type
`cpu/nanoseconds`), aggregated over the flush interval, with the sampled thread
carried as a `thread.name` sample attribute. Resource attributes come from
`otel.service.name` + `otel.resource.attributes`.

```powershell
java `
  "-javaagent:C:\path\to\opentelemetry-javaagent.jar" `
  "-Dotel.javaagent.extensions=C:\path\to\otel-jfr-extension.jar" `
  "-Dotel.service.name=APP-12345 | Spring Boot | DEV" `
  "-Dotel.instrumentation.jfr.enabled=true" `
  "-Dotel.instrumentation.jfr.profile=true" `
  "-Dotel.exporter.otlp.profiles.endpoint=https://1234567890.otlp.example.com/v1development/profiles" `
  "-Dotel.exporter.otlp.profiles.headers=Authorization=Bearer <token>" `
  "-jar" "test.jar"
```

> **CAVEATS — read before relying on this.**
> - The OTLP profiles signal is **public Alpha / "development"** (endpoint path
>   is literally `/v1development/profiles`). The wire format can change between
>   releases.
> - **Most backends cannot ingest profiles yet.**
>   This mode is built and locally verified as a future-ready path; confirm your
>   backend supports the profiles signal before depending on it.
> - Built against `opentelemetry-proto 1.10.0-alpha` (gencode 4.34.0), chosen to
>   stay `<=` the protobuf runtime the agent ships (4.35.0). If you change the
>   agent version, re-check its bundled protobuf version and pick a compatible
>   `OTEL_PROTO_VERSION` (see `build.sh`), or profile export will fail at class
>   init with a protobuf gencode/runtime mismatch.

## How it works

```
opentelemetry-javaagent.jar
  └─ loads otel-jfr-extension.jar  (via -Dotel.javaagent.extensions)
       └─ JfrExtensionProvider           (AutoConfigurationCustomizerProvider SPI)
            └─ if otel.instrumentation.jfr.enabled:
                 └─ JfrRecordingManager   (jdk.jfr.consumer.RecordingStream)
                      ├─ JfrMetricMapper    jdk.* events → OTel Meter  → OTLP metrics
                      ├─ JfrSpanMapper       jdk.ExecutionSample → OTel Tracer → OTLP spans   (profile=false)
                      └─ JfrProfileMapper    jdk.ExecutionSample → OTLP profiles (POST)         (profile=true)
                                            (metrics/spans flow through the agent's own exporters;
                                             profiles are POSTed by the extension's own exporter)
```

Two SPI registrations drive it:

- `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider`
  (`JfrExtensionProvider`) captures the resolved config during SDK autoconfigure.
- `META-INF/services/io.opentelemetry.javaagent.extension.AgentListener`
  (`JfrAgentListener`) is invoked by the agent **after** it has built and
  registered the SDK, and receives the configured `OpenTelemetry` directly via
  `afterAgent(AutoConfiguredOpenTelemetrySdk)`. This is the race-free way to get
  the SDK — calling `GlobalOpenTelemetry.get()` from an extension would install a
  no-op instance first and crash the agent's own registration.

Metrics and spans therefore use the exact same exporter pipeline (endpoints,
headers, protocol) as the agent's built-in auto-instrumentation. The profiles
signal is the one exception: the agent does not autoconfigure a profiles
pipeline, so the extension owns a small OTLP/HTTP profiles exporter (see the
Profiles output mode section).

## Telemetry reference

All metric and span **names** from this extension are prefixed with `jfr.` so you
can find everything it emits with a single `jfr.` search. (This intentionally
diverges from OTel semantic-convention names like `jvm.memory.used` — the
instrumentation scope `com.otel.jfr` still tags the source, but the name
prefix makes ad-hoc search trivial. If you need semconv-named metrics for stock
JVM dashboards, drop the `jfr.` prefix in `JfrMetricMapper`.)

**Metrics**

| OTel metric | Unit | Source JFR event |
| --- | --- | --- |
| `jfr.process.cpu.utilization` (`cpu.mode=user\|system`) | `1` | `jdk.CPULoad` |
| `jfr.system.cpu.utilization` (`cpu.mode=total`) | `1` | `jdk.CPULoad` |
| `jfr.jvm.thread.cpu.utilization` | `1` | `jdk.ThreadCPULoad` |
| `jfr.jvm.thread.context_switch.rate` | `{switch}/s` | `jdk.ThreadContextSwitchRate` |
| `jfr.jvm.thread.memory.allocated` | `By` | `jdk.ThreadAllocationStatistics` |
| `jfr.jvm.memory.used` / `jfr.jvm.memory.committed` / `jfr.jvm.memory.limit` | `By` | `jdk.GCHeapSummary`, `jdk.MetaspaceSummary` |
| `jfr.jvm.gc.duration` | `s` | `jdk.GarbageCollection`, `jdk.G1GarbageCollection` |
| `jfr.jvm.gc.longest_pause` | `s` | `jdk.GarbageCollection` |
| `jfr.jvm.memory.allocation` (`jvm.jfr.allocation.location=in_new_tlab\|outside_tlab`) | `By` | `jdk.ObjectAllocationInNewTLAB`, `jdk.ObjectAllocationOutsideTLAB` |
| `jfr.jvm.socket.read.bytes` / `jfr.jvm.socket.read.duration` | `By` / `s` | `jdk.SocketRead` |

**Spans** - from `jdk.ExecutionSample`: one trace per stack sample, root span
named `jfr.stack.sample: <thread>`, child spans named `jfr.<Class>.<method>` with
`code.namespace`, `code.function`, `code.lineno`, `jfr.thread.name`, and
`jfr.synthetic_duration=true`.

> The span *name* carries the `jfr.` prefix for search, but `code.namespace` /
> `code.function` attributes keep the real, unprefixed symbol names so flamegraph
> tooling reads correct method identifiers.
>
> The sampled thread is exposed as `jfr.thread.name`, not the standard
> `thread.name` -- the agent auto-stamps `thread.name` with the JFR dispatch
> thread, which would otherwise hide the thread that was actually sampled.

**Profiles** (profile mode) - function/symbol names are **not** prefixed: the
profiles signal is emitted only by this extension (the agent produces none), so
there is nothing to disambiguate, and a prefix would corrupt flamegraph symbols.

## Alternative build (Gradle)

`build.sh` is the primary, dependency-free build. If you prefer Gradle, an
equivalent `build.gradle.kts` would declare the OpenTelemetry API, SDK-common,
and `sdk-extension-autoconfigure-spi` as `compileOnly` and use the Shadow plugin
to produce the jar. The OTel dependencies must stay `compileOnly` — bundling
them shadows the agent's own copies and breaks the extension.

## Limitations

- Execution-sample profiling has real (small, typically <2%) overhead; tune
  `sampling-period-ms` and set `profiling-enabled=false` if you only want metrics.
- `jfr.synthetic_duration=true` spans reflect where threads were sampled, not
  measured call latency. Use them for flame/hotspot analysis, not SLA timing.

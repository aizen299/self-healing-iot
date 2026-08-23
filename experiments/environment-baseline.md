# Environment baseline

Recorded 2026-08-23. This is the reference machine for all experiments
unless a specific run records otherwise. The reproducibility contract in
`README.md` requires these facts alongside every result; capturing them
once here keeps individual run records short — a run only needs to note
what *differs* from this baseline.

## Machine

| Property | Value |
|---|---|
| CPU | Apple M3, 8 cores |
| Architecture | arm64 |
| RAM | 8 GB (8,589,934,592 bytes) |
| OS | macOS 26.5.2 (build 25F84) |
| Free disk | ~288 GB |

## Toolchain

| Tool | Version |
|---|---|
| JDK | OpenJDK 21.0.12.1, HotSpot, G1GC default (see ADR-002) |
| `JAVA_HOME` | `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` |
| Maven | 3.9.16 |
| Mosquitto | 2.1.2 |
| Docker CLI / Compose | 29.7.2 / v5.4.0 (daemon not yet enabled — Phase 7) |
| kubectl | v1.36.1 (no local cluster yet — Phase 8) |

Capture the exact runtime string in each run's raw output:

```bash
"$JAVA_HOME/bin/java" -version 2>&1
```

## Standing constraint: 8 GB memory ceiling

This is the binding constraint on experiment design, and it is a
constraint on the *measurement apparatus*, not on the system under test.

The full target stack — Kafka, an MQTT broker, a time-series database,
Prometheus, Grafana, the gateway, plus N device workloads — will not fit
alongside a Kubernetes cluster in 8 GB if each simulated device is its
own JVM or container. A bare JVM costs on the order of tens of megabytes
of resident memory before any application heap, so a one-process-per-device
model exhausts RAM in the low tens of devices, well short of the
scalability targets.

Two consequences follow, both settled before Phase 1 (see ADR-003):

1. **Fleet size is scoped to 50 devices.** The skill suggests up to
   250–500; this is a personal project with no industrial deployment
   target, so 50 is the declared ceiling and the scale ladder is
   **10 → 25 → 50**. This is a scoping decision, not a measured limit —
   it must never be presented as the hardware's maximum.
2. **Simulation model is hybrid.** A multi-device harness (one JVM, many
   device objects) is the default vehicle for Pillars A and C;
   one-process-per-device is reserved for the Phase 9 recovery demo at
   roughly 3–10 devices. The two models are not interchangeable for
   Pillar C, so every recorded run must state which one produced it.

Note this constraint does not weaken the thesis. Resource limitation is
the subject of the research — but it has to be a declared parameter of
the setup rather than an unexamined property of the laptop.

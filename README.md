# Self-Healing Edge/IoT Fleet Platform

Advanced Java semester project: a simulated fleet of resource-constrained
IoT/edge devices that communicate over MQTT, are monitored by a Java
gateway, stream telemetry and failure events through Kafka, persist
history in a time-series database, and are automatically recovered by a
Java-based Kubernetes controller when they fail.

## Research thesis

The project's academic contribution is not "IoT + Kubernetes" as a buzzword
stack. It is:

> Applying J2ME-era resource-conscious engineering discipline to modern
> Java under constrained memory, while building a self-healing IoT fleet
> and experimentally comparing constrained/optimized Java against a
> naively implemented Java baseline under equivalent resource limits.

We are not using literal J2ME (it is deprecated). We are applying
J2ME-style constrained-engineering discipline on a modern JVM, and
measuring the effect.

The two pillars evaluated are:

1. Constrained vs. naive Java under an identical heap/resource cap
   (memory, GC behavior, CPU, throughput, latency).
2. Automated failure detection and recovery (MTTR, recovery success
   rate, scalability with device count).

## Architecture

```
Java Edge Nodes (constrained + naive variants)
        |
      MQTT
        |
Java Gateway (heartbeat monitoring, validation, routing)
        |
Kafka / Kafka Streams
        |
Time-Series Storage
        |
Grafana
```

Failure path:

```
Edge Node Failure -> Heartbeat Timeout -> Java Gateway -> Failure Event
    -> Recovery Controller -> Kubernetes -> Replacement Edge Node
    -> Telemetry Resumes
```

Full architecture detail lives in `docs/architecture/`.

## Repository layout

| Path | Purpose |
|---|---|
| `edge-device/` | Simulated IoT/edge device (constrained + naive variants) |
| `gateway/` | MQTT-facing Java gateway: heartbeat monitoring, failure detection, routing to Kafka |
| `stream-processor/` | Kafka Streams topology: telemetry aggregation, failure/recovery event processing |
| `recovery-operator/` | Java Kubernetes operator/controller that reacts to failure events and provisions replacements |
| `common/` | Shared DTOs, protocol/schema definitions, utilities used across modules |
| `infrastructure/docker/` | Dockerfiles and Compose configuration for local runs |
| `infrastructure/kubernetes/` | Kubernetes manifests (Deployments, ConfigMaps, probes, RBAC for the operator) |
| `infrastructure/helm/` | Optional Helm charts (later phase) |
| `infrastructure/monitoring/` | Prometheus scrape config, Grafana dashboards/provisioning |
| `experiments/` | Reproducible experiment configs, scripts, and raw/processed results (see `experiments/README.md`) |
| `docs/` | Architecture docs, API docs, MQTT/Kafka topic specs, ADRs, testing strategy, experiment methodology |
| `tests/unit`, `tests/integration`, `tests/e2e` | Cross-module test suites |
| `.github/workflows/` | CI pipeline (added in the CI/CD phase) |

Each module directory has its own `README.md` describing its responsibility
and current implementation status.

## Status

**Phases 1–4 complete — a fleet whose failures are detected automatically.**
`common/`, `edge-device/`, and `gateway/` are implemented, tested, and
runnable; 115 tests pass. The remaining modules are still scaffolding.

The simulator runs a fleet of 50 devices in two variants — a
resource-disciplined one and a conventional baseline — producing an
identical deterministic workload and byte-identical payloads, which is
what makes Pillar A a fair comparison.

Devices publish real telemetry to Mosquitto, each over its own connection
so the broker's Last Will identifies an individual device rather than the
fleet. That per-device presence signal on `fleet/{id}/status` is what
Phase 4's failure detection will consume; the wire contract is in
[`docs/api/mqtt-topics.md`](docs/api/mqtt-topics.md).

The gateway subscribes to the whole fleet, validates every reading, tracks
per-device state, and serves it over HTTP.

It also **detects failures**, via two complementary paths. The broker's Last
Will catches a device that dies or loses its network. A heartbeat timeout
catches the case the will structurally cannot see: a device that stays
connected and keeps publishing telemetry while its liveness path has wedged.
Devices walk `ONLINE → SUSPECTED → OFFLINE → RECOVERING → ONLINE`, and
failures are announced on `fleet/{id}/events` for Phase 9's recovery to act
on. See [ADR-006](docs/decisions/ADR-006-failure-detection.md).

## Development phases

Build order is strict — never start a phase before the previous one has a
working, tested demonstration. This numbering is canonical and matches
`CLAUDE.md`:

- [x] Phase 1 — Java edge simulator
- [x] Phase 2 — MQTT communication
- [x] Phase 3 — Java gateway
- [x] Phase 4 — Heartbeat / failure detection
- [ ] Phase 5 — Persistent telemetry
- [ ] Phase 6 — Kafka streaming
- [ ] Phase 7 — Containerization (Docker)
- [ ] Phase 8 — Kubernetes deployment
- [ ] Phase 9 — Automatic recovery (operator)
- [ ] Phase 10 — Observability (Prometheus / Grafana)
- [ ] Phase 11 — Chaos experiments
- [ ] Phase 12 — CI/CD
- [ ] Phase 13 — GitOps (optional)

### Evaluation workstream

The research evaluation is **not** a single late phase — it is a
cross-cutting workstream, because the thesis is the measurement, not the
feature set. It attaches to the build phases as follows:

| Pillar | Measured | Depends on |
|---|---|---|
| A — Constrained vs. naive Java under an identical heap cap | heap usage, GC behavior, CPU, throughput, latency | Phase 1 (both variants exist); repeated after Phase 7 to confirm containerized runs match |
| B — Automated failure detection and recovery | MTTR, recovery success rate | Phases 4 and 9; exercised systematically in Phase 11 |
| C — Fleet scalability | messages/sec, gateway CPU/memory, detection and recovery latency vs. device count | Phases 3–6, re-run after Phase 8 |

Every run must satisfy the reproducibility contract in
`experiments/README.md`. No numeric result appears anywhere in this
project unless a recorded run in `experiments/` produced it.

## Toolchain

Pinned deliberately — the constrained-vs-naive comparison is only valid
if every run uses the same JVM, so the runtime is treated as part of the
experimental setup rather than an environment detail.

| Tool | Version | Notes |
|---|---|---|
| JDK | OpenJDK **21.0.12.1** (HotSpot, G1GC) | `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`. HotSpot is required — a GraalVM runtime's escape analysis can mask the allocation differences Pillar A measures. Matches the `eclipse-temurin:21` image used from Phase 7. |
| Maven | 3.9.16 | Build tool for all modules (no Gradle). |
| Mosquitto | 2.1.2 | Local MQTT broker + `mosquitto_pub`/`mosquitto_sub` for manual verification (Phase 2). |

Set `JAVA_HOME` before building, since the machine has several JDKs
installed and the default on `PATH` is not the pinned one:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

Deferred until the phase that needs them: `kind` (Phase 8), Docker
daemon (Phase 7), `helm` (Phase 13, optional).

## How to run

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn clean test
```

Run a fleet of 50 constrained devices under a 64 MB heap for 10 seconds:

```bash
FLEET_VARIANT=constrained FLEET_DEVICE_COUNT=50 FLEET_RUN_DURATION_SECONDS=10 \
  java -Xmx64m -cp edge-device/target/classes:common/target/classes io.fleet.edge.Main
```

Swap `FLEET_VARIANT=naive` for the baseline, or inject a deterministic
failure with `FLEET_FAILURE_MODE=CRASH FLEET_FAIL_AFTER=10`. All
variables are documented in `edge-device/README.md`.

To publish to a real broker instead, start Mosquitto and set
`FLEET_SINK=mqtt` (the Paho jar must be on the classpath — see
`edge-device/README.md`):

```bash
/opt/homebrew/opt/mosquitto/sbin/mosquitto -p 1883
```

```bash
mosquitto_sub -h 127.0.0.1 -t 'fleet/+/telemetry' -v
```

The counting sink remains the default because Pillar A must measure
telemetry *production*, not the MQTT client — and because tests and quick
runs should not need a broker.

The run summary is a **demonstration, not a result** — figures count only
when produced by a run recorded under `experiments/results/` with its
configuration attached.

## Documentation

See `docs/` for architecture, API/topic specs, database schema,
testing strategy, experiment methodology, and results as they are
produced. Architecture Decision Records live in `docs/decisions/`.

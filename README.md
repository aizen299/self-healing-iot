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

**Phase 0 — repository scaffolding.** No application code yet. This
commit establishes the folder structure, git history, and baseline
documentation that later phases build on.

## Development phases

Build order is strict — never start a phase before the previous one has a
working, tested demonstration. This numbering is canonical and matches
`CLAUDE.md`:

- [ ] Phase 1 — Java edge simulator
- [ ] Phase 2 — MQTT communication
- [ ] Phase 3 — Java gateway
- [ ] Phase 4 — Heartbeat / failure detection
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

Nothing is runnable yet. Run instructions will be added to this README as
each phase lands, starting with the edge simulator in Phase 1 and a local
Mosquitto broker in Phase 2.

## Documentation

See `docs/` for architecture, API/topic specs, database schema,
testing strategy, experiment methodology, and results as they are
produced. Architecture Decision Records live in `docs/decisions/`.

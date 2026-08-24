# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Self-Healing Edge/IoT Fleet Platform** — an advanced Java semester project. A
simulated fleet of resource-constrained IoT/edge devices communicates over
MQTT, is monitored by a Java gateway, streams telemetry/failure events
through Kafka, persists history in a time-series database, and is
automatically recovered by a Java-based Kubernetes controller when devices
fail.

**Research thesis** (the actual academic contribution — keep this framing in
any docs/experiments/code comments touching it): applying J2ME-style
resource-conscious engineering discipline to modern Java under a constrained
heap, and experimentally comparing a disciplined/constrained Java
implementation against a naively implemented Java baseline under an
identical resource cap. Never describe this as literally using J2ME (it's
deprecated) — the correct phrasing is "J2ME-style constrained Java
engineering discipline on a modern JVM." The two evaluated pillars are:

1. Constrained vs. naive Java under an identical heap/resource cap (memory,
   GC behavior, CPU, throughput, latency).
2. Automated failure detection and recovery (MTTR, recovery success rate,
   scalability with device count).

No numeric result may ever be reported (docs, README, presentation, code
comments) unless it was produced by an actual recorded experiment run in
`experiments/`. Never fabricate benchmark results.

## Current status

**Phases 1–7 complete — a containerised fleet whose failures are detected,
recorded, and streamed.** Phase 7 was taken before Phase 6 (ADR-008): Kafka
and a real TSDB both need a server, and containers supply them. The phase
numbers still identify the work, but no longer its order.
`common/`, `edge-device/`, `gateway/`, and `stream-processor/` are
implemented and tested; `recovery-operator/` is still README-only
scaffolding stating its target phase.

Build is **Maven** (no Gradle). The JVM is pinned to HotSpot OpenJDK 21
by ADR-002 and enforced by `maven-enforcer-plugin` — the build fails on
any other version *and* on GraalVM, because Graal's escape analysis can
erase the allocation differences Pillar A measures.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn clean test                                  # all modules
mvn -pl edge-device -am test                    # one module + its deps
```

The full stack runs in containers, published on **18080** because 8080 is
already taken on this machine:

```bash
docker compose up --build
```

Bring up one service at a time where you can — Docker Desktop's VM already
claims about half of the 8 GB host, and Phase 8 measures memory on it.

Run the simulator (see `edge-device/README.md` for all variables):

```bash
FLEET_VARIANT=constrained FLEET_DEVICE_COUNT=50 \
  java -Xmx64m -cp edge-device/target/classes:common/target/classes io.fleet.edge.Main
```

Fleet scope is 50 devices and the simulation model is hybrid — see
ADR-003.

Telemetry goes wherever `FLEET_SINK` says. It defaults to `COUNTING`
(no broker required) because Pillar A must measure telemetry production
rather than the MQTT client. `FLEET_SINK=mqtt` publishes to a real
broker, one connection per device — a Last Will belongs to a connection,
so sharing one would leave the broker unable to name which device died
(ADR-004). Start a local broker with:

```bash
/opt/homebrew/opt/mosquitto/sbin/mosquitto -p 1883
```

MQTT integration tests skip themselves when no broker is listening, so
`mvn test` stays green without one — but MQTT and gateway work should be
verified with a broker running. The wire contract is
`docs/api/mqtt-topics.md`.

The gateway ingests, validates, serves fleet state on an HTTP API, and
detects failures. Two detection paths, deliberately: the broker's Last Will
for a device that dies or disconnects, and a heartbeat timeout for one that
stays connected but wedges — telemetry is **not** treated as proof of life,
or that second case would be undetectable (ADR-006).

A device never heard from stays `UNKNOWN` and is never declared failed,
which is what keeps retained-presence ghosts out of the recovery path.
`devicesKnown` counts those ghosts; use `devicesReporting` for the real
fleet.

Heartbeats ride the telemetry tick, so `GATEWAY_HEARTBEAT_INTERVAL_MS` must
match `FLEET_PUBLISH_INTERVAL_MS`. Nothing enforces that across the two
processes; a mismatch shows up as false failures or slow detection.

Kafka is a **downstream copy, never the system of record**, and nothing on
the detection path touches it (ADR-009). `GATEWAY_KAFKA_ENABLED` defaults to
false; the gateway ingests, detects and persists without a broker. Telemetry
is forwarded as the exact bytes that arrived, so the wire format has one
encoder in the whole system. `device.failures` is a strict subset of
`device.events` so Phase 9's controller cannot see anything it should not
act on.

Kafka is by far the heaviest service — ~392 MB against ~3 MB for Mosquitto.
Leave it out of `docker compose up` unless the phase under test needs it.

Telemetry and health events persist to an embedded H2 store behind a
`TelemetryStore` interface (ADR-007) — embedded because containers are
Phase 7, and replaceable by a server-backed TSDB then. `/history` and
`/stats` query it. A store failure is never fatal, but the gaps travel with
the data: every query result carries an `integrity` block, and the run
summary prints `history: complete` or `INCOMPLETE — N readings lost`. A
window that is not complete cannot support a result.

## Development phases — strict build order

Never build phase *N* before phase *N-1* has a working, tested
demonstration, and never jump ahead (e.g. don't touch Kubernetes/chaos/
observability work while the local MQTT→gateway pipeline is still shaky):

1. Java edge simulator
2. MQTT communication
3. Java gateway
4. Heartbeat/failure detection
5. Persistent telemetry
6. Kafka streaming
7. Containerization (Docker)
8. Kubernetes deployment
9. Automatic recovery (operator)
10. Observability (Prometheus/Grafana)
11. Chaos experiments
12. CI/CD
13. GitOps (optional)

For every phase: state the objective and files that will change, implement
the smallest working version, run tests, run the application, demonstrate
the behavior, record evidence, update documentation — only then move to the
next phase. Never claim a feature is complete without having actually run
and tested it.

## Architecture / data flow

```
Simulated Edge Nodes (Java, constrained + naive variants)
        │ MQTT
        ▼
   MQTT Broker
        │
        ▼
   Java Gateway (heartbeat monitoring, validation, routing)
        │                       │
        ▼                       ▼ (failure events)
Kafka / Kafka Streams   ────────┘
        │
   ┌────┴────┐
   ▼         ▼
Time-Series   Recovery Controller (Java Operator)
   DB              │
   │               ▼
   ▼           Kubernetes → Replacement Node
Grafana
```

Failure/recovery feedback loop — the core system behavior, must stay intact
end to end: `Device → Telemetry → Monitoring → Failure detected → Recovery
event → Controller → Replacement workload → Healthy fleet restored`.

Device health state machine (owned by the gateway):
`ONLINE → SUSPECTED → OFFLINE → RECOVERING → ONLINE`. Use configurable
heartbeat thresholds and a count of consecutive missed heartbeats rather
than a single miss, to avoid false positives.

Kafka topics: `telemetry.raw`, `telemetry.processed`, `device.events`,
`device.failures`, `device.recovery`. Introduce Kafka only after MQTT →
Gateway already works end to end — don't route every internal operation
through Kafka just because it exists.

MQTT topic convention: `fleet/{deviceId}/telemetry`, `/heartbeat`,
`/status`, `/events`.

**Recovery must be idempotent** — the same failure event arriving twice must
never create two replacements. Track recovery state explicitly (device,
replacement identity, recovery id).

## Repository layout

One module per top-level directory, each independently buildable; a shared
`common/` module carries DTOs and topic/schema constants so modules don't
duplicate the wire format.

| Path | Purpose |
|---|---|
| `edge-device/` | Simulated device. Will contain `constrained/` (small heap, object reuse, bounded queues, controlled threads, efficient serialization) and `naive/` (no resource discipline) variants — the pair being experimentally compared |
| `gateway/` | MQTT-facing Java gateway: heartbeat monitoring, failure detection, device state machine, health API, forwards to Kafka |
| `stream-processor/` | Kafka Streams topology: windowed aggregation, rolling averages/anomaly thresholds, fleet-level stats |
| `recovery-operator/` | Java Kubernetes operator (Java Operator SDK preferred, plain K8s Java client fallback) that consumes failure events and provisions idempotent replacements |
| `common/` | Shared DTOs, wire-format schema, MQTT/Kafka topic constants, small utilities — deliberately thin, not a dumping ground |
| `infrastructure/docker/` | Per-module Dockerfiles + root `docker-compose.yml` for the full local stack |
| `infrastructure/kubernetes/` | Manifests for kind/Minikube: Deployments, ConfigMaps, probes, RBAC. Workload labels (`app=edge-device`, `device-id=...`, `fleet-id=...`) are how the recovery operator identifies/replaces workloads |
| `infrastructure/helm/` | Optional, only if it simplifies later GitOps/ArgoCD phase |
| `infrastructure/monitoring/` | Prometheus scrape config, Grafana dashboards (fleet health, failed devices, MTTR, telemetry rate, gateway load) |
| `experiments/` | `configs/`, `scripts/`, `results/{raw,processed}/` — see reproducibility contract below |
| `docs/decisions/` | ADRs (numbered `ADR-NNN-*.md`) for major architecture decisions |
| `docs/architecture/`, `docs/api/`, `docs/experiments/` | Architecture docs, gateway API docs, experiment writeups — written to describe what exists, not what's planned |
| `tests/integration`, `tests/e2e` | Cross-module suites — see testing requirements below |
| `tests/unit` | Reserved for cross-module unit-level suites; currently empty. Per-module unit tests live in each module's `src/test/java` |

Each module's own `README.md` is the source of truth for that module's
current implementation status; keep it updated as phases land there.

## Experiment reproducibility contract

`experiments/results/` is intentionally *not* gitignored — raw and processed
data must be committed. Every experiment run must record: machine specs,
JVM version, heap limit, device count, publishing interval, experiment
duration, MQTT config, Kafka config, Kubernetes config, and failure mode.
Charts are generated from collected data, never from assumptions.

## Testing requirements

- **Unit** (each module's own `src/test/java`, *not* `tests/unit`):
  telemetry validation, heartbeat logic, failure state transitions,
  recovery decision logic, configuration validation. Unit tests live
  beside the code they cover so `mvn test` in a module actually verifies
  that module — moving them to a top-level directory would break the
  independently-buildable modules ADR-001 is built around. `tests/unit`
  stays for genuinely cross-module unit-level suites, and is currently
  empty; see `tests/unit/README.md`.
- **Integration** (`tests/integration`): MQTT→Gateway, Gateway→Kafka,
  Kafka→storage, failure event→recovery.
- **E2E** (`tests/e2e`): the project's core reproducible demo — start
  device → send telemetry → verify gateway receives it → kill device →
  verify heartbeat-timeout detection → verify failure event → verify
  recovery controller reacts → verify replacement telemetry resumes →
  record recovery duration.

## Things to never do on this project

Build everything in one giant step; add unnecessary microservices; add
AI/ML for buzzword reasons; use Kubernetes before the local MQTT→gateway
pipeline works; silently swallow MQTT/Kafka exceptions; hide configuration
in source code (use env vars / application config); leave TODO placeholders
in core functionality; rewrite working modules unnecessarily; change
architecture without recording why (add an ADR).

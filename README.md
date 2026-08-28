# Self-Healing Edge/IoT Fleet Platform

[![CI](https://github.com/aizen299/self-healing-iot/actions/workflows/ci.yml/badge.svg)](https://github.com/aizen299/self-healing-iot/actions/workflows/ci.yml)

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
| `infrastructure/gitops/` | Argo CD: the AppProject that draws the boundary, the app-of-apps, and `bootstrap.sh` |
| `infrastructure/helm/` | Reserved for a Helm chart and deliberately still empty — see ADR-016 |
| `infrastructure/monitoring/` | Prometheus scrape config, Grafana dashboards/provisioning |
| `experiments/` | Reproducible experiment configs, scripts, and raw/processed results (see `experiments/README.md`) |
| `docs/` | Architecture docs, API docs, MQTT/Kafka topic specs, ADRs, testing strategy, experiment methodology |
| `tests/unit`, `tests/integration`, `tests/e2e` | Cross-module test suites |
| `.github/workflows/` | CI: tests against a real broker, shell-script checks, image build and smoke test ([ADR-014](docs/decisions/ADR-014-continuous-integration-shape.md)) |

Each module directory has its own `README.md` describing its responsibility
and current implementation status.

## Status

**All thirteen phases complete.** A device that dies is detected, replaced,
and back online without anyone touching it; Prometheus and Grafana show it
happening; Phase 11 recorded the first real results; Phase 12 puts a gate in
front of every change; and Phase 13 reconciles the platform from git — while
deliberately keeping its hands off the fleet. Every module is implemented,
tested, and runnable, and the whole suite passes against a real broker.

Phase 7 was taken **before** Phase 6: both Kafka and a real time-series
database need a server, and the phase that supplies servers came after both
([ADR-008](docs/decisions/ADR-008-containerisation-before-kafka.md)). The
numbers still identify the phases — they are no longer the order of work.

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

Telemetry and health events are persisted to an embedded store, queryable
over HTTP for device history and fleet aggregates — including the recovery
timings Pillar B's MTTR is computed from
([ADR-007](docs/decisions/ADR-007-telemetry-storage.md)).

The gateway also forwards to Kafka, where a Kafka Streams topology derives
windowed fleet statistics. **Nothing on the detection path touches Kafka** —
a broker between a device failing and the gateway noticing would add a
failure mode to the component that exists to detect them
([ADR-009](docs/decisions/ADR-009-kafka-streaming.md)).

The whole stack runs on a local kind cluster, where a device is a pod. The
device pods are **bare Pods with `restartPolicy: Never`**, which is not how
you would deploy a service and is exactly the point: a Deployment would
restart a crashed device in about a second, and the MTTR this project
reports would be a measurement of the kubelet's restart loop rather than of
the detection-and-recovery loop that is the research subject. A device that
dies stays dead until the recovery operator replaces it
([ADR-010](docs/decisions/ADR-010-kubernetes-deployment-shape.md)).

**The loop closes.** The operator consumes `device.failures`, clones a
replacement pod for the dead device, and announces what it did on
`device.recovery`. Recovery is idempotent because the replacement's name is
derived from the failure it answers, so a redelivered event asks the API
server to create a pod that already exists and is refused — a guarantee that
survives the operator crashing, which an in-memory ledger would not
([ADR-011](docs/decisions/ADR-011-recovery-operator-shape.md)).

**The fleet is observable.** The gateway and the operator each expose
Prometheus text on their own endpoint, hand-written rather than taken from a
client library, and a provisioned Grafana dashboard shows fleet state,
detection and recovery in one place. Scrape the gateway through its admin
port, never through the main one: readiness withdraws the main port during a
broker outage, which is exactly when the dashboard matters
([ADR-012](docs/decisions/ADR-012-observability-shape.md)). Nothing on the
dashboard is a result — a screenshot is precisely the artefact that gets
mistaken for one.

**The recovery loop has been measured.** Phase 11 injected 20 pod-loss
failures into a running cluster and recorded what happened:
**20/20 recovered**, with a **median MTTR of 1332.5 ms** (p90 1602 ms), of
which the operator's own share — decide, then get the API server to accept a
replacement — is 6.4%. The rest is a JVM starting and reconnecting. Two runs
before it were discarded for a broken apparatus rather than a bad result. This
([pillar-b-recovery.md](docs/experiments/pillar-b-recovery.md),
[ADR-013](docs/decisions/ADR-013-chaos-and-where-results-come-from.md)).

**And the thesis itself is measured.** Under one 64 MB cap, doing verifiably
identical work — same seed, byte-identical payloads, the same 30,003 readings
at the same 500/s — the constrained variant triggered **zero garbage
collections** across five 60-second runs where the naive baseline triggered
four every time, for **2.6× less CPU** and **1.6× less resident memory**. The
throughput row is the control that licenses the rest: this is not a fast
implementation beating a slow one, it is the same work for less machine
([pillar-a-constrained-vs-naive.md](docs/experiments/pillar-a-constrained-vs-naive.md)).
Pillar C — scalability against device count — is **deliberately not
measured**: its recovery-latency half needs one device per pod, and a per-pod
JVM's baseline against this host's 8 GB would produce a curve describing the
laptop rather than the system. The reasoning is in
[`docs/experiments/README.md`](docs/experiments/README.md), stated in the open
rather than left looking pending.

**Every change now passes a gate.** CI builds and tests against a real broker
on each pull request, checks the shell scripts that make up the experiment
apparatus, and builds the container images and smoke-tests the stack. The
check that matters is not that the build passed but that the *suite ran
complete*: the MQTT suites skip themselves when no broker is listening — the
whole MQTT wire path and heartbeat detection — and a skipped test reports
success. CI fails on a skip, quoting the reason the test gave, so a broker
that never started can no longer produce a green run that covered nothing
([ADR-014](docs/decisions/ADR-014-continuous-integration-shape.md)).

CI produces no numbers. Shared runners of unstated hardware cannot satisfy the
reproducibility contract, so there is no benchmark job and the smoke test
asserts behaviour rather than latency.

**The platform reconciles itself from git — and stops at the fleet.** Argo CD
manages the broker, gateway, Kafka, operator and monitoring: change a manifest
in this repository and the cluster follows; change one of the fields those
manifests declare by hand and Argo puts it back. The device pods are outside that boundary on purpose. A device
that fails is *supposed* to be missing from the cluster while still declared in
git, and a controller with self-heal would restore it in about a second — from
the wrong component, making the recorded MTTR a measurement of Argo's sync loop
rather than the recovery loop. So no Application's path includes the device
manifests, and the AppProject refuses to manage a Pod at all, which turns a
convention into a rule
([ADR-016](docs/decisions/ADR-016-gitops-boundary.md)).

## Development phases

Build order is strict — never start a phase before the previous one has a
working, tested demonstration. This numbering is canonical and matches
`CLAUDE.md`:

- [x] Phase 1 — Java edge simulator
- [x] Phase 2 — MQTT communication
- [x] Phase 3 — Java gateway
- [x] Phase 4 — Heartbeat / failure detection
- [x] Phase 5 — Persistent telemetry
- [x] Phase 6 — Kafka streaming
- [x] Phase 7 — Containerization (Docker) — *taken before Phase 6, see ADR-008*
- [x] Phase 8 — Kubernetes deployment
- [x] Phase 9 — Automatic recovery (operator)
- [x] Phase 10 — Observability (Prometheus / Grafana)
- [x] Phase 11 — Chaos experiments
- [x] Phase 12 — CI/CD
- [x] Phase 13 — GitOps (optional)

### Evaluation workstream

The research evaluation is **not** a single late phase — it is a
cross-cutting workstream, because the thesis is the measurement, not the
feature set. It attaches to the build phases as follows:

| Pillar | Measured | Depends on |
|---|---|---|
| A — **measured** ([writeup](docs/experiments/pillar-a-constrained-vs-naive.md)) — constrained vs. naive Java under an identical heap cap | GC behaviour, resident memory, CPU, throughput | Phase 1 (both variants exist); measured on the shared harness under `-Xmx64m` |
| B — **measured** ([writeup](docs/experiments/pillar-b-recovery.md)) — automated failure detection and recovery | MTTR, recovery success rate | Phases 4 and 9; measured in Phase 11 |
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

Argo CD `v3.4.8` (core install) arrived with Phase 13; `helm` is installed but unused (ADR-016).
`kind` 0.32.0 and `kubectl` v1.36.1 arrived with Phase 8.

## How to run

On Kubernetes — builds the images, side-loads them into a kind cluster, and
waits for the pipeline:

```bash
./infrastructure/kubernetes/deploy.sh
```

Or the whole stack in containers:

```bash
docker compose up --build
```

Either way the gateway answers on the same port:

```bash
curl -s http://127.0.0.1:18080/health
```

Or locally, without Docker:

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

## Continuous integration

Every pull request runs the build and the full test suite against a real
Mosquitto broker; ShellCheck and `actionlint` over the scripts and the
workflow; `kubeconform` over the Kubernetes manifests; and an image build plus
a smoke test of the containerised stack. The same gate runs locally:

```bash
docker compose up -d --wait broker
```

```bash
mvn -B verify && python3 .github/scripts/assert-suite-complete.py
```

The second command is the part that is easy to skip and shouldn't be. `mvn`
exits 0 when tests are skipped, and the MQTT suites skip themselves when no
broker is reachable — so a green build alone does not mean the MQTT path was
tested. The assertion fails unless every module reported and nothing was
skipped, and it quotes the reason each skipped test gave. It also rejects a
report left behind by an earlier run, since surefire never deletes one.

Images are published to GHCR only when a `v*` tag is pushed, with immutable
version and commit tags and no `:latest`. Merging to main publishes nothing;
there is no environment to continuously deploy to, and
[ADR-014](docs/decisions/ADR-014-continuous-integration-shape.md) records why
that is a decision rather than an omission.

Note that the smoke test cannot run while the kind cluster is up — both
publish the gateway on host 18080.

## Documentation

See `docs/` for architecture, API/topic specs, database schema,
testing strategy, experiment methodology, and results as they are
produced. Architecture Decision Records live in `docs/decisions/`.

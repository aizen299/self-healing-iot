# Self-Healing Edge/IoT Fleet Platform

[![CI](https://github.com/aizen299/self-healing-iot/actions/workflows/ci.yml/badge.svg)](https://github.com/aizen299/self-healing-iot/actions/workflows/ci.yml)

A simulated fleet of resource-constrained IoT devices that publish over MQTT,
are monitored by a Java gateway, stream through Kafka, persist to a
time-series store, and are **automatically replaced by a Java Kubernetes
operator when they die** — with the whole loop measured rather than asserted.

## The research question

> Applying J2ME-era resource-conscious engineering discipline to modern Java
> under a constrained heap, and experimentally comparing a disciplined
> implementation against a naive baseline under an identical resource cap.

Not literal J2ME — that is deprecated. J2ME-*style* discipline on a modern
JVM: object reuse, bounded buffers, hand-rolled encoding, controlled threads.

**All three pillars are measured.** Every figure below came from a recorded
run in [`experiments/results/`](experiments/results/), reproducible from the
committed apparatus. No number appears anywhere in this project unless a run
produced it.

| Pillar | Result | Writeup |
|---|---|---|
| **A** — constrained vs. naive under one 64 MB cap | Identical work (byte-identical payloads, 30,003 readings at 500/s). Constrained: **0 GC collections**. Naive: 4, every run. **2.6× less CPU**, 1.6× less resident memory. | [pillar-a](docs/experiments/pillar-a-constrained-vs-naive.md) |
| **B** — failure detection and recovery | 20 injected pod failures, **100% recovered**. MTTR median **1332.5 ms** (p90 1602 ms). The operator's share: 6.4%. | [pillar-b](docs/experiments/pillar-b-recovery.md) |
| **C** — scalability, 10 → 50 devices | **Zero telemetry lost** (3001/3001 at 50 devices, on QoS 0). Detection **flat at ~4128 ms**. Per-device cost falls throughout. | [pillar-c](docs/experiments/pillar-c-scalability.md) |

Pillar C's one gap: recovery latency *versus device count* needs one device per
pod, which this 8 GB host cannot hold. Named in the writeup and in the run's
own metadata, not left to be inferred.

## How it works

```
Edge devices (constrained | naive)          50 devices, one JVM or one pod each
        │ MQTT, one connection per device (ADR-004)
        ▼
   Mosquitto broker
        │
        ▼
   Java gateway ──────────────┐            validates, tracks state, detects failure
        │                     │
        ▼                     ▼
  Kafka + Streams      device.failures
        │                     │
        ▼                     ▼
  H2 / Grafana        Recovery operator ──► Kubernetes API ──► replacement pod
```

**The loop that matters:** device dies → gateway detects → failure event →
operator creates a replacement → telemetry resumes. Measured end to end as
Pillar B.

Two independent detection paths, because neither covers the other (ADR-006):
the broker's **Last Will** catches a device that dies or drops its network; a
**heartbeat timeout** catches one that stays connected but wedges. Telemetry is
deliberately *not* proof of life, or the second case would be invisible.

Device health: `ONLINE → SUSPECTED → OFFLINE → RECOVERING → ONLINE`.

## Quick start

Everything needs the pinned JDK. The `java` on most machines is not it:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

| Goal | Command |
|---|---|
| Build and test | `mvn clean test` |
| Run 50 devices, no broker needed | `FLEET_DEVICE_COUNT=50 java -Xmx64m -cp edge-device/target/classes:common/target/classes io.fleet.edge.Main` |
| Whole stack in containers | `docker compose up --build` |
| Whole stack on Kubernetes | `./infrastructure/kubernetes/deploy.sh` |
| Reproduce a pillar | `./experiments/scripts/run-pillar-{a,c}-experiment.sh` |

The gateway answers on **18080** (8080 is usually taken):

```bash
curl -s http://127.0.0.1:18080/health
```

`/health` is 200 while the process lives (liveness); `/ready` is 503 until the
broker connection is up (readiness); `/metrics` is Prometheus exposition —
scrape it on **18081**, never 18080, because readiness withdraws the latter
during exactly the outage a dashboard is for.

## Layout

| Path | What |
|---|---|
| `edge-device/` | The simulator, and the `constrained/` vs `naive/` pair Pillar A compares |
| `gateway/` | MQTT ingest, validation, device registry, failure detection, HTTP API |
| `stream-processor/` | Kafka Streams: windowed aggregation, fleet stats |
| `recovery-operator/` | Consumes failures, creates replacement pods. No K8s client library — JDK `HttpClient` and ~300 lines (ADR-011) |
| `common/` | DTOs, wire format, topic constants |
| `infrastructure/` | `docker/`, `kubernetes/`, `gitops/` (Argo CD), `monitoring/` |
| `experiments/` | Configs, runners, and committed raw + processed results |
| `docs/decisions/` | 16 ADRs — every non-obvious choice and why |
| `docs/experiments/` | The three pillar writeups |

## Decisions worth knowing before changing anything

- **Device pods are bare Pods with `restartPolicy: Never`** (ADR-010). A
  Deployment would restart a dead device in ~1 s, so the operator would never
  act and the reported MTTR would measure the kubelet.
- **GitOps stops at the fleet** (ADR-016). Argo reconciles the platform; the
  device manifests are in no Application's path, and the AppProject refuses to
  manage a `Pod` at all — because a failed device is *supposed* to be missing
  from the cluster while still declared in git.
- **The gateway is a singleton.** It holds the registry in memory, locks the
  store, and uses one MQTT client id. Scaling it makes replicas evict each
  other and declare healthy devices failed.
- **Kafka is a downstream copy, never the system of record** (ADR-009).
  Nothing on the detection path touches it.
- **CI produces no numbers.** Shared runners cannot satisfy the reproducibility
  contract, so there is no benchmark job (ADR-014).

## The gate

CI runs on every pull request, and the check that matters is not "the build
passed" but **"the suite ran complete"** — `mvn` exits 0 when tests skip, and
the MQTT suites skip themselves when no broker is listening. Run it locally:

```bash
docker compose up -d --wait broker
```

```bash
mvn -B verify && python3 .github/scripts/assert-suite-complete.py
```

275 tests, zero skipped. The assertion fails on any skip, any stale report, or
any module that did not report, and quotes the reason each test gave.

## Reproducibility contract

`experiments/results/` is deliberately not gitignored — raw and processed data
are committed. Every run records machine specs, JVM, heap cap, device count,
tick interval, duration, transport config, and failure mode. Processing is a
pure function of the recording: re-deriving a summary from the raw directory
produces byte-identical output.

Runs that did not qualify were discarded rather than published, and each
writeup says which and why — a broken apparatus, a missing completion time, a
sample taken at the wrong moment. That list is part of the result.

## Toolchain

OpenJDK **21.0.12.1** (HotSpot, G1) — pinned by ADR-002 and enforced by
`maven-enforcer-plugin`, which **rejects GraalVM**: its escape analysis can
erase the allocation differences Pillar A exists to measure. Maven 3.9.16,
Mosquitto 2.1.2, kind 0.32.0, Argo CD v3.4.8.

## Documentation

`docs/decisions/` for the ADRs, `docs/api/` for the MQTT/HTTP/Kafka contracts,
`docs/experiments/` for the results. Each module's own `README.md` is the
source of truth for that module.

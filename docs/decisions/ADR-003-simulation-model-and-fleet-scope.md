# ADR-003: Hybrid simulation model and a 50-device fleet scope

## Status
Accepted — 2026-08-23

## Context
Two questions had to be answered before any simulator code was written,
because both determine the structure of `edge-device/`: how large the
fleet is, and whether a "device" is an operating-system process or an
object inside a shared JVM.

The project skill suggests scalability runs up to 250–500 devices. The
development machine has 8 GB of RAM (see `experiments/environment-baseline.md`).
A minimal JVM costs roughly 60–100 MB resident once metaspace, code
cache, thread stacks, and GC structures are counted — an order-of-magnitude
planning estimate, not a measurement. Fifty separate device JVMs is
therefore on the order of 3.5 GB before the gateway, Kafka, a time-series
database, Prometheus, Grafana, or a Kubernetes control plane, on a host
where the operating system already wants 3–4 GB. One process per device
does not fit at the scales the skill proposes.

The two models are also not equivalent as measurement instruments. A
shared-JVM harness can reach high device counts cheaply but cannot
attribute heap to an individual device and does not exercise the
container lifecycle. Separate processes are faithful to deployment and
are what Kubernetes recovery actually operates on, but they price out
quickly.

This is a personal semester project with no industrial deployment
target, so fleet size is a free parameter rather than a requirement
inherited from a production system.

## Decision

**Fleet scope is 50 devices.** The scalability ladder is 10 → 25 → 50.
This is a *scoping decision*, declared in advance — not a measured limit
of the hardware, and it must never be reported as one.

**The simulation model is hybrid**, selected per research pillar:

| Pillar | Model | Rationale |
|---|---|---|
| A — constrained vs. naive Java | Multi-device harness, single JVM, fixed `-Xmx` | The comparison is about allocation discipline under a heap cap. One JVM per variant makes the cap unambiguous and keeps the measurement free of process and network noise. |
| B — failure detection and recovery (MTTR) | One process/pod per device, ~3–10 devices | MTTR is measured per device. Demonstrating and timing recovery never required 50 concurrent pods, and the container lifecycle is the thing under test. |
| C — fleet scalability | Multi-device harness | Reaches 50 devices comfortably on this host; the gateway, not the device count, is the component under load. |

Both variants must run the **same workload**: an identical, deterministic,
seeded sensor sequence, and byte-identical serialized payloads. Only the
implementation discipline may differ. A unit test asserts payload
equality between the variants, because if the two emit different bytes
the experiment silently compares different workloads rather than
different implementations.

## Consequences
- Positive: the harness makes Pillar A a stronger result than a
  single-device micro-benchmark would. "The constrained variant sustains
  50 devices in a fixed heap where the naive variant exhausts it at N" is
  a claim about engineering discipline, which is the thesis; a
  microsecond difference on one device is not.
- Positive: 50 devices is reachable on this hardware, so every reported
  number comes from a run that actually completed rather than from
  extrapolation.
- Negative: harness runs cannot attribute heap to a single device, only
  to the fleet as a whole. Acceptable, because Pillar A's unit of
  analysis is the implementation, not the device.
- Negative: Pillars B and C are measured under different models, so
  their numbers are not directly comparable. Every recorded run must
  state which model produced it; `experiments/` metadata treats the model
  as a required field.
- Revisit if: the project later wants a scalability claim beyond what
  this host sustains. That would need different hardware and a
  re-baseline, not an extrapolation from these runs.

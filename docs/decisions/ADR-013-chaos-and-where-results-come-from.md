# ADR-013: Chaos without a chaos framework, and where a result comes from

## Status
Accepted — 2026-08-25

## Context
Phase 11 turns the recovery loop from something that has been *demonstrated*
into something that has been *measured*. Until now this project had no
recorded results at all: every number in every README was labelled a
demonstration, deliberately, because the reproducibility contract says a
figure is a result only when a recorded run produced it.

Three decisions had to be made before the first run.

## Decision

### No chaos framework

Chaos Mesh, Litmus and friends are the obvious answer to "inject failures into
Kubernetes". This project injects them with `kubectl delete pod
--grace-period=0 --force` and, where a fault has to be inside the device, with
the `FailureMode` enum that has existed since Phase 1.

The framework would install a controller, a set of CRDs and its own RBAC into
a cluster whose whole point is that a *specific* operator, with three verbs in
one namespace, is the only thing that touches pods. It would also become a
second mechanism capable of deleting a device — and when a device disappears,
"which component removed it" stops being obvious. That is a bad property for
the experiment whose subject is exactly what happens when a device disappears.

The faults this project needs are: a pod that vanishes, a device that stops
heartbeating while staying connected, a device that floods, a link that drops
without a DISCONNECT, and a broker or Kafka that goes away. Four of those are
already implemented in `FailureInjector`, deterministically, keyed on a
reading count rather than wall-clock time so a run reproduces on any host. The
other two are `kubectl scale`.

This is the third time the project has declined a library on the same
grounds — ADR-011 for the Kubernetes client, ADR-012 for the metrics
registry — and the limit is the same: take the framework when there is a fault
it can inject that this project cannot, such as CPU pressure, packet loss or
clock skew inside a container.

### A result comes from the system's own records, cross-checked from outside

Two independent views of every recovery are recorded, and they are never
merged:

- **Internal.** `device.recovery` carries the gateway's
  `recoveryDurationMillis` and the operator's `detectionToReplacementMillis`.
  These are the measurements, taken by the components that own the events
  being timed.
- **External.** The runner records wall-clock time from `kubectl delete`
  returning to the gateway's HTTP API reporting `ONLINE`, polled on a 250 ms
  sleep — slightly under 4 Hz, since each poll pays for its own HTTP round
  trip. The interval is stated rather than a rate because the rate is a
  consequence, and this number is load-bearing in the explanation of the gap
  between the two views.

The external number is always larger — it includes a poll interval and an API
round trip, and it starts before detection has happened. It is not a better or
worse measurement; it is a different one, and its job is to make a
disagreement between the system's account of itself and an outside observer's
*visible*. A single blended figure would hide exactly the case worth catching:
a gateway that reports fast recoveries while the fleet is in fact slow to come
back.

**MTTR is the gateway's number.** The operator's is a component of it,
starting at the same instant and ending earlier, and the two must never be
added — a rule that now exists in the code, the metric names, a test, the
dashboard, and here.

### Processing is a pure function of the recording

`summarise.py` reads a raw run directory and writes processed output. It holds
no state, contacts nothing, and fills in no value that was not recorded — a
field that was not measured stays empty rather than becoming a zero, because a
zero in a latency column is a claim and an empty cell is the absence of one.

Running it again on the same raw directory produces byte-identical output.
That is what makes a committed result *checkable* rather than merely present:
anyone can re-derive the summary from the raw records and see whether it
matches.

Raw and processed are both committed, and `experiments/results/` is
deliberately not gitignored.

### A run that fails is still a run

The runner exits non-zero when an iteration does not recover, and records the
run regardless. A recovery-success-rate of 100% means nothing if the runs that
were not 100% were quietly deleted.

The one thing that does justify discarding a run is a broken apparatus, and
Phase 11 discarded two runs on exactly that ground before recording one.

The first recorded `"publishing interval: None"`, because the script read
three ConfigMap keys of which two did not exist. It was re-taken after the
script was changed to capture each process's own startup header — which
reports effective configuration, including values that came from a code
default and appear in no ConfigMap at all.

The second survived that fix and was still short two contract fields, both
found by review rather than by the script noticing: `toolchain.java` was the
empty string, because `java -version` writes to stderr and the capture read
stdout; and no duration was recorded anywhere, because `metadata.json` is
written before the first failure is injected and was never updated afterwards.

The numbers in both runs were real. That is exactly why they were discarded:
plausible numbers with an incomplete record are more dangerous than obviously
broken ones, because nothing about them invites a second look. Back-filling
metadata after the fact is what makes a reproducibility record worthless, so
the only honest options are to re-take the run or to publish nothing.

The lesson is recorded here rather than only fixed: **an apparatus that
records a required field as empty must fail, not shrug.** The preflight now
refuses to start when it cannot reach the endpoint the measurement loop
depends on, for the same reason — a run whose apparatus is broken should not
be able to produce a confident-looking 0%.

## Consequences

- **Recovery experiments run at 3 devices, one per pod, and cannot run in the
  shared harness.** There is nothing for the operator to replace in the shared
  harness, so no MTTR exists there. An MTTR figure and a fleet-size figure in
  this project come from different vehicles, and neither substitutes for the
  other. Every run's `metadata.json` records which vehicle produced it.
- **The runner needs a cluster deployed with `--recovery`** and refuses to
  start otherwise, or against a fleet that is already a device down.
- **`kubectl delete --force` is the pod-loss fault, and the `--force` is
  load-bearing.** A graceful stop lets the device publish a retained
  `SHUTDOWN`, which the gateway correctly reads as a deliberate stop rather
  than a failure (ADR-006) — and no recovery follows, so the experiment would
  measure nothing.
- **The in-device faults are not interchangeable with pod loss.** A device
  that crashes inside its pod leaves the pod `Running`, so the operator's
  liveness check correctly returns `NOT_NEEDED` unless
  `OPERATOR_REPLACE_LIVE_DEVICES` is on. That is a different scenario with a
  different recovery path, and mixing the two in one figure would produce an
  average of two unrelated things.

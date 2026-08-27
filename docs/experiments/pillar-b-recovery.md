# Pillar B — automated failure detection and recovery

**First recorded results.** Everything below came from
`experiments/results/raw/b1-pod-loss-20260827T144232Z/` and was derived by
`experiments/scripts/summarise.py`. Re-run the summariser on that directory
and it reproduces these figures exactly; nothing here is an estimate.

## What was measured

Twenty device pods were force-deleted, one at a time, round-robin across
`device-001`–`device-003`, with the fleet allowed to settle between samples.
Each iteration was left to recover on its own — nothing in the loop tells the
operator anything, and nothing recreates the pod except the operator reacting
to a failure event on `device.failures`.

| Setting | Value |
|---|---|
| Fleet model | one device per pod, bare Pods, `restartPolicy: Never` (ADR-010) |
| Devices | 3 |
| Device variant / heap | `constrained` / `-Xmx64m` |
| Tick interval | 1000 ms — publish interval and expected heartbeat, one value (ADR-006) |
| Detection thresholds | suspect at 2, offline at 4 missed heartbeats |
| Recovery confirmation | 2 heartbeats |
| Failure mode | `pod-loss` (`kubectl delete --grace-period=0 --force`) |
| Iterations | 20 (run duration 215 s) |

## Results

**Recovery success rate: 20/20 = 100%.** Every operator outcome was
`REPLACED`; none was `FAILED` or `NOT_NEEDED`.

**MTTR — failure detection to confirmed heartbeats,** the gateway's
`recoveryDurationMillis`:

| n | min | median | p90 | max | mean |
|---|---|---|---|---|---|
| 20 | 1089 ms | 1332.5 ms | 1602 ms | 1981 ms | 1373.5 ms |

**Operator half — detection to the API server accepting the replacement pod.**
A component of the number above, never to be added to it:

| n | min | median | p90 | max | mean |
|---|---|---|---|---|---|
| 20 | 48 ms | 80.5 ms | 115 ms | 263 ms | 87.9 ms |

## What the numbers say

**The operator is not the bottleneck.** Deciding what to do and getting the
API server to accept a replacement took a mean of 88 ms — **6.4% of MTTR**,
between 3.7% and 13.6% on any individual sample. The remaining ~94% is the
replacement pod becoming a device: container start, JVM boot, MQTT connect,
and then heartbeats.

That matters for the thesis. It says the recovery *mechanism* — a Kafka
trigger, four API calls, no operator framework (ADR-011) — is essentially free
at this scale, and that MTTR is set by how long a JVM takes to start and by
how patient the gateway is before it believes a device is back.

**The confirmation policy sets a floor.** The gateway requires 2 heartbeats at
a 1000 ms tick before it will call a device recovered, so roughly a second of
every measurement is a deliberate configuration choice rather than a system
limit. The fastest recovery observed was 1089 ms, which is about that floor.
**MTTR here is not the smallest number this system could produce** — it is the
number this configuration produces, and a run with a shorter tick or a
single-heartbeat confirmation would report a smaller one without anything
having got faster.

**The external cross-check agrees.** Wall-clock time from `kubectl delete`
returning to the gateway's API reporting `ONLINE`, polled on a 250 ms sleep
from outside the cluster (slightly under 4 Hz once each poll's own HTTP round
trip is counted), exceeded MTTR on **20 of 20** samples by 461–834 ms (median
622 ms) — the poll interval, the API round trip, and the detection that
happens before the gateway's own clock starts. Zero polls failed, and the
summary's four independent counts of the run all agree.

## What this run does not show

**Only one of the two detection paths was exercised.** Every row recorded
`missedHeartbeats: 0`, which means the gateway learned of every failure from
the broker's Last Will rather than from a heartbeat timeout. Force-deleting a
pod severs the connection, and the will fires immediately. The heartbeat-
timeout path — the one that exists for a device that stays connected and
wedges (ADR-006) — is untested by this experiment and would be materially
slower by construction, since it cannot fire before 4 missed heartbeats have
elapsed.

**Three devices, not fifty.** Recovery cannot be measured in the shared
harness at all, because there is no pod there for the operator to replace, so
these figures come from the one-device-per-pod vehicle at the scale it is
scoped to (see `experiments/environment-baseline.md`). Nothing here says how
MTTR behaves as the fleet grows.

**One machine, one run.** No claim is made about Kubernetes recovery in
general, or about a different device count, tick interval, or confirmation
policy.

**The JVM under measurement is the containers', not the host's.** Every
component runs on `eclipse-temurin:21` (OpenJDK 21.0.12) and the run records
that. The `java` on this machine's PATH happens to be a GraalVM, which ADR-002
bans outright because its escape analysis would erase the allocation
differences Pillar A measures — it is recorded separately in `metadata.json`
under `hostJavaOnPath` precisely so it cannot be mistaken for the runtime that
produced these numbers.

## Reproducing it

```bash
./infrastructure/kubernetes/deploy.sh --recovery
./experiments/scripts/run-recovery-experiment.sh experiments/configs/b1-pod-loss.env
```

The runner refuses to start against a fleet that is already a device down, and
exits non-zero if any iteration fails to recover — recording the run either
way. See [ADR-013](../decisions/ADR-013-chaos-and-where-results-come-from.md)
for why the chaos is injected without a chaos framework and why the internal
and external measurements are kept apart.

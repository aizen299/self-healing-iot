# Pillar C: the pipeline against fleet size

**Run `c1-fleet-scalability-20260828T075544Z`.** Raw data in
[`experiments/results/raw/`](../../experiments/results/raw/c1-fleet-scalability-20260828T075544Z/),
processed output in
[`experiments/results/processed/`](../../experiments/results/processed/).
This is a recorded result and may be cited as one.

## What was measured

The real path — fleet → Mosquitto → gateway, all host JVMs — at 10, 25 and 50
devices, three repetitions each, with the device count as the only thing that
moves.

| Parameter | Value |
|---|---|
| Device counts | 10, 25, 50 (ADR-003's fleet scope) |
| Tick | 1000 ms, the production rate |
| Run duration | 60 s, ×3 repetitions per count |
| Variant | constrained (Pillar A settled which the fleet runs) |
| Fleet heap | `-Xmx64m` |
| Gateway heap | `-Xmx256m` |
| Fault | `HEARTBEAT_STOP` after 30 readings, **every device** |
| Detection policy | OFFLINE after 4 missed heartbeats |
| JVM | OpenJDK 21.0.12.1, HotSpot |
| Machine | Apple M3, 8 cores, 8 GB |
| Elapsed | 703 s |

The fault is a heartbeat stop rather than a crash. A device that goes quiet
while staying connected is the case a Last Will structurally cannot catch
(ADR-006), so it exercises the detection path that actually costs the gateway
something — and because every device stops at the same reading count, a
50-device run presents the gateway with fifty simultaneous failures rather
than one.

## Results

Median across three repetitions.

| Metric | 10 devices | 25 devices | 50 devices | 10→50 |
|---|---|---|---|---|
| Fleet throughput (readings/s) | 10.0 | 25.0 | 50.0 | 5.0× |
| Telemetry published | 601 | 1501 | 3001 | 5.0× |
| Telemetry accepted | 601 | 1501 | 3001 | 5.0× |
| **Delivered** | **1.000** | **1.000** | **1.000** | — |
| Failures detected | 10 | 25 | 50 | 5.0× |
| **Detection latency (ms)** | **4129** | **4125** | **4128** | **1.00×** |
| Fleet CPU (s) | 1.22 | 2.03 | 3.19 | 2.6× |
| Fleet resident (MB) | 67.9 | 71.4 | 79.8 | 1.2× |
| Gateway CPU (s) | 2.27 | 3.29 | 4.65 | 2.0× |
| Gateway resident (MB) | 96.7 | 96.7 | 118.0 | 1.2× |

Derived — whole-fleet figures divided by the count, an interpretation rather
than a measurement:

| Per device | 10 | 25 | 50 |
|---|---|---|---|
| Fleet CPU (ms) | 122.0 | 81.2 | 63.8 |
| Gateway CPU (ms) | 227.0 | 131.6 | 93.0 |
| Gateway resident (MB) | 9.67 | 3.87 | 2.36 |

## What the numbers say

**Nothing was lost.** 3001 readings published and 3001 accepted at 50 devices,
in every repetition, at all three sizes. That is worth stating because
telemetry is QoS 0 (ADR-004) — fire and forget, with no delivery guarantee at
all — so a shortfall would have been entirely permissible and is what a reader
should expect to have to worry about. Within this fleet's designed scope, the
pipeline does not drop readings.

**Detection does not degrade.** Five times the fleet and five times the
simultaneous failures, and the gateway still notices in ~4128 ms. The
detection policy's floor is 4000 ms — four missed heartbeats at a 1000 ms
tick — so the gateway is spending about 128 ms above the theoretical minimum
at 10 devices and about the same at 50. All 150 devices across all nine runs
were detected; none was missed.

**Cost grows, but slower than the fleet.** Five times the devices costs 2.6×
the fleet CPU and 2.0× the gateway CPU, and only 1.2× the resident memory on
either side. Per-device cost falls throughout — the gateway's share drops from
227 ms to 93 ms and from 9.7 MB to 2.4 MB per device — which is fixed cost
being amortised over more work rather than anything scaling well in itself.

**The system is nowhere near its limit.** The gateway used 4.65 s of CPU over
a run of roughly 70 s: about 7% of one core, on eight. This experiment
describes a system with a great deal of headroom at its designed scope. It
does not describe where that headroom runs out.

## What this run does not show

**Where it breaks.** No saturation point was found because none was
approached. The curve covers 10 → 50 devices, which is ADR-003's fleet scope,
and says nothing about 500.

**Recovery latency against device count.** The half of Pillar C that needs one
device per pod. A per-pod JVM costs roughly 64 MB of baseline before doing any
work, and this host has 8 GB with a Docker VM already taking about half, so a
curve drawn here would describe the laptop. Phase 11 was scoped to three
devices for the same reason (ADR-013). The gap is recorded as a structured
`scope.notMeasured` field in the run's own `metadata.json`, not only in this
paragraph.

**Anything about a different tick.** The rate is fixed at the production
1000 ms while the device count moves. A fleet of 10 at 100 ms is the same
message rate as a fleet of 100 at 1000 ms, and this run does not say whether
the gateway experiences them alike.

**One machine, one broker, one gateway.** Mosquitto and both JVMs share eight
cores. Nothing here separates the gateway's cost from contention with the
fleet process sitting beside it.

## A measurement trap worth recording

The first version of this experiment sampled the gateway's counters five
seconds before the fleet stopped, and reported that the gateway held **71% of
published telemetry at 50 devices** — consistently, across every repetition.
That looked like a real finding about QoS 0 under load.

It was an artefact of when the sample was taken. The gateway was behind, not
losing: given eight seconds after the fleet stopped, it had every reading. A
second sample after the drain is what distinguishes the two, and without it
the run would have published a 29% loss rate that does not exist.

The mid-run figures are still recorded in `runs.jsonl` under `ingest`, beside
the post-drain `ingestFinal`, because the gap between them is the ingest lag
and is worth having. But the delivery ratio is computed from the drained
sample, and a run that cannot take one reports the ratio as absent rather than
guessing.

## Reproducing it

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

```bash
./experiments/scripts/run-pillar-c-experiment.sh
```

The runner refuses to start without `JAVA_HOME` (this machine's PATH `java` is
a GraalVM, which ADR-002 excludes), refuses if its gateway port is already
taken, and starts and stops the compose broker itself. Processing is a pure
function of the recording:

```bash
python3 experiments/scripts/summarise-pillar-c.py experiments/results/raw/c1-fleet-scalability-20260828T075544Z
```

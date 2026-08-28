# c1-fleet-scalability-20260828T075544Z

Pillar C. 3 repetitions at each of 10, 25, 50 devices, 1000 ms tick, 60 s per run, constrained variant.

- **Fleet** `-Xmx64m`, **gateway** `-Xmx256m`, over MQTT to tcp://127.0.0.1:1883
- **Fault**: HEARTBEAT_STOP after 30 readings, every device; OFFLINE after 4 missed heartbeats
- **JVM**: openjdk version "21.0.12.1" 2026-08-18
- **Machine**: Apple M3, 8 cores
- **Source**: `274cc00edb06`

## Runs

9 runs recorded, all completed.

## Median at each fleet size

| Metric | 10 devices | 25 devices | 50 devices |
|---|---|---|---|
| Fleet throughput (readings/s) | 10.0 | 25.0 | 50.0 |
| Fleet CPU (s) | 1.22 | 2.03 | 3.19 |
| Fleet resident (MB) | 67.9 | 71.4 | 79.8 |
| Fleet GC collections (count) | 1.0 | 0.0 | 0.0 |
| Gateway CPU (s) | 2.27 | 3.29 | 4.65 |
| Gateway resident (MB) | 96.7 | 96.7 | 118.0 |
| Telemetry published (count) | 601 | 1501 | 3001 |
| Telemetry accepted (count) | 601 | 1501 | 3001 |
| Delivered (fraction) | 1.000 | 1.000 | 1.000 |
| Failures detected (count) | 10 | 25 | 50 |
| Detection latency (ms) | 4129 | 4125 | 4128 |

## Derived: cost per device

Whole-fleet figures divided by the device count. Derived from the rows above, not separately measured.

| Metric | 10 devices | 25 devices | 50 devices |
|---|---|---|---|
| Fleet CPU per device (ms) | 122.0 | 81.2 | 63.8 |
| Gateway CPU per device (ms) | 227.0 | 131.6 | 93.0 |
| Gateway resident per device (MB) | 9.67 | 3.87 | 2.36 |

## Reconciliation

- runs recorded: 9
- per device count: 10=3, 25=3, 50=3
- expected per device count: 3
- incomplete: 0

Detection samples per run (devices the gateway had declared OFFLINE when sampled, against the fleet size):

- 10 devices: rep1=10/10, rep2=10/10, rep3=10/10
- 25 devices: rep1=25/25, rep2=25/25, rep3=25/25
- 50 devices: rep1=50/50, rep2=50/50, rep3=50/50

## Scope

Measured: throughput against device count; fleet-side CPU, resident memory and GC against device count; gateway-side CPU and resident memory against device count; heartbeat-detection latency against device count.

**Not measured**: recovery latency against device count. needs one device per pod; a per-pod JVM costs ~64 MB of baseline and this host has 8 GB with a Docker VM taking half, so the curve would describe the machine. Phase 11 was scoped to three devices for the same reason (ADR-013).


# edge-device

Simulated IoT/edge device. Generates telemetry (temperature, vibration,
battery level, location) with a configurable publishing interval and
deterministic, configurable failure modes, and publishes it through the
`TelemetrySink` seam.

**Status:** Phase 1 complete. Both variants and the fleet harness are
implemented and tested. There is no MQTT yet — Phase 2 adds an MQTT
implementation of `TelemetrySink` without changing device code.

## The two variants

The pair being compared by Pillar A of the research. Both run an
identical, deterministic workload and emit **byte-identical payloads**;
only the implementation discipline differs. `VariantPayloadEqualityTest`
enforces that — if it ever fails, the experiment is comparing two
different workloads and its results are void.

| | `constrained/` | `naive/` |
|---|---|---|
| Reading object | none | a `Telemetry` record per reading |
| Serialization | hand-rolled fixed-point encoder into a reused buffer | `String.format` |
| Boxing | none | six primitives boxed into a varargs array per reading |
| Byte conversion | none — encodes straight to bytes | `String.getBytes` |
| Topic | built once per device | rebuilt per reading |
| Buffer | fixed 256 B, overflow throws | grows as needed |
| Threads | one shared scheduler thread | one per device |

The naive variant is deliberately *not* bad code. It is the ordinary,
idiomatic way this would be written without thinking about allocation.
The research question is what that ordinary style costs under a heap
cap — not whether obviously wasteful code is wasteful.

## Configuration

Everything comes from the environment, so a run is fully described by its
environment block and can be replayed from `experiments/configs/`.

| Variable | Default | Meaning |
|---|---|---|
| `FLEET_VARIANT` | `CONSTRAINED` | `CONSTRAINED` or `NAIVE` |
| `FLEET_DEVICE_COUNT` | `50` | Fleet size (scoped by ADR-003) |
| `FLEET_DEVICE_ID_PREFIX` | `device` | Produces ids like `device-007` |
| `FLEET_PUBLISH_INTERVAL_MS` | `1000` | Per-device publish period |
| `FLEET_RUN_DURATION_SECONDS` | `30` | Run length |
| `FLEET_FAILURE_MODE` | `NONE` | `NONE`, `CRASH`, `MESSAGE_FLOOD` |
| `FLEET_FAIL_AFTER` | `0` | Readings before the failure fires |
| `FLEET_FLOOD_MULTIPLIER` | `10` | Readings per tick once flooding |
| `FLEET_SEED` | `42` | Base seed; each device derives its own |
| `FLEET_BASE_LAT` / `FLEET_BASE_LON` | `52.52` / `13.405` | Fleet centre |

Failures trigger on a **reading count, not elapsed time**, so the same
failure fires at the same point regardless of host speed.

`NETWORK_INTERRUPTION` arrives with MQTT in Phase 2 and `HEARTBEAT_STOP`
with heartbeat monitoring in Phase 4. They are absent rather than stubbed,
because a placeholder in core functionality is worse than a missing one.

## Running

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn -pl edge-device -am clean package

FLEET_VARIANT=constrained FLEET_DEVICE_COUNT=50 FLEET_RUN_DURATION_SECONDS=10 \
  java -Xmx64m -cp edge-device/target/classes:common/target/classes io.fleet.edge.Main
```

The summary printed at the end is a **demonstration, not a result**.
Figures become results only when produced by a run recorded under
`experiments/results/` with its full configuration, per the
reproducibility contract.

## Design notes

- Devices are passive: they own neither a thread nor a clock. The harness
  decides when a reading happens and supplies the timestamp, which keeps
  the threading policy a property of the variant and lets tests drive the
  clock.
- The measurement sink counts and discards, so Phase 1 measures the cost
  of *producing* telemetry with no broker or network noise in the numbers.
- No runtime dependencies beyond `common`. A logging framework on the hot
  path would allocate and confound the Pillar A measurements.
- Bounded *queues* are not here yet. Phase 1 publishes synchronously, so a
  queue would buffer nothing; it lands in Phase 2, where a broker can be
  slow or unavailable and back-pressure becomes real.

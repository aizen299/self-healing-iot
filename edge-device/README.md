# edge-device

Simulated IoT/edge device. Generates telemetry (temperature, vibration,
battery level, location) with a configurable publishing interval and
deterministic, configurable failure modes, and publishes it through the
`TelemetrySink` seam.

**Status:** Phases 1–2 complete. Both variants, the fleet harness, and
MQTT publishing are implemented and tested. Devices publish to a real
broker with one connection each; see
[`docs/api/mqtt-topics.md`](../docs/api/mqtt-topics.md).

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
| `FLEET_SINK` | `COUNTING` | `COUNTING` (no broker needed) or `MQTT` |
| `FLEET_DEVICE_COUNT` | `50` | Fleet size (scoped by ADR-003) |
| `FLEET_DEVICE_ID_PREFIX` | `device` | Produces ids like `device-007` |
| `FLEET_PUBLISH_INTERVAL_MS` | `1000` | Per-device publish period |
| `FLEET_RUN_DURATION_SECONDS` | `30` | Run length |
| `FLEET_FAILURE_MODE` | `NONE` | `NONE`, `CRASH`, `MESSAGE_FLOOD`, `NETWORK_INTERRUPTION` |
| `FLEET_FAIL_AFTER` | `0` | Readings before the failure fires |
| `FLEET_FLOOD_MULTIPLIER` | `10` | Readings per tick once flooding |
| `FLEET_INTERRUPT_DURATION_MS` | `5000` | How long a network outage lasts |
| `FLEET_SEED` | `42` | Base seed; each device derives its own |
| `FLEET_BASE_LAT` / `FLEET_BASE_LON` | `52.52` / `13.405` | Fleet centre |

MQTT settings apply only when `FLEET_SINK=MQTT`:

| Variable | Default |
|---|---|
| `MQTT_BROKER_URL` | `tcp://127.0.0.1:1883` |
| `MQTT_CLIENT_ID_PREFIX` | `fleet` |
| `MQTT_QOS` | `0` (telemetry; presence is always QoS 1) |
| `MQTT_KEEPALIVE_SECONDS` | `60` |
| `MQTT_CONNECTION_TIMEOUT_SECONDS` | `10` |
| `MQTT_CLEAN_SESSION` | `true` |
| `MQTT_AUTOMATIC_RECONNECT` | `true` |
| `MQTT_RETAINED_STATUS` | `true` |

Failures trigger on a **reading count, not elapsed time**, so the same
failure fires at the same point regardless of host speed.

`NETWORK_INTERRUPTION` drops the connection *without* a DISCONNECT packet,
so the broker fires the device's Last Will exactly as a severed link would
— it is handled in the MQTT sink rather than in device logic, because the
fault is in the transport. It requires `FLEET_SINK=MQTT`; configuring it
against the counting sink is rejected at startup rather than silently
doing nothing.

`HEARTBEAT_STOP` arrives with heartbeat monitoring in Phase 4. It is
absent rather than stubbed, because a placeholder in core functionality is
worse than a missing one.

## Running

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn clean package
```

Without a broker — the default, and what Pillar A uses:

```bash
FLEET_VARIANT=constrained FLEET_DEVICE_COUNT=50 FLEET_RUN_DURATION_SECONDS=10 \
  java -Xmx64m -cp edge-device/target/classes:common/target/classes io.fleet.edge.Main
```

Against a real broker. Start Mosquitto first, and put the Paho jar on the
classpath:

```bash
/opt/homebrew/opt/mosquitto/sbin/mosquitto -p 1883
```

```bash
CP=edge-device/target/classes:common/target/classes:$HOME/.m2/repository/org/eclipse/paho/org.eclipse.paho.client.mqttv3/1.2.5/org.eclipse.paho.client.mqttv3-1.2.5.jar
FLEET_SINK=mqtt FLEET_DEVICE_COUNT=3 FLEET_RUN_DURATION_SECONDS=10 java -cp "$CP" io.fleet.edge.Main
```

Watch it arrive:

```bash
mosquitto_sub -h 127.0.0.1 -t 'fleet/+/telemetry' -v
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
- One runtime dependency: the Eclipse Paho MQTT client (240 KB, no
  transitive dependencies). Still no logging framework — it would allocate
  on the hot path and confound the Pillar A measurements.
- **Each device owns its MQTT connection.** A Last Will belongs to a
  connection, so a shared client would give the whole fleet one will and
  the broker could only announce "the fleet went away" — never
  "device-017 went away", which is the signal Phase 4 needs.
- **Pillar A is measured through the counting sink, not MQTT.** Paho
  accepts only a whole `byte[]`, so publishing copies the payload and the
  constrained variant's zero-allocation property does not survive the
  hop. The sink copies unconditionally so both variants do identical work
  if anyone does measure through it.
- Bounded *queues* are still not here. Publishing is synchronous and
  failures surface immediately as counted sink errors rather than being
  buffered, which keeps loss visible. Offline buffering is a deliberate
  later decision, not an oversight.

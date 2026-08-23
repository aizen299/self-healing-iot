# gateway

Java MQTT gateway. Subscribes to device topics, validates and deserializes
telemetry, tracks per-device state, and exposes a health/status API.

**Status:** Phases 3–4 complete — ingestion, validation, the device
registry, the HTTP API, and automatic failure detection. Forwarding to
Kafka arrives in Phase 6.

Two runtime dependencies — the Paho MQTT client and Jackson's streaming
parser — and the JDK's own HTTP server rather than a web framework. See
[ADR-005](../docs/decisions/ADR-005-gateway-dependencies-and-http.md).

## What it does today

Subscribes to `fleet/+/telemetry` and `fleet/+/status` at QoS 1 and, for
each message:

1. Routes by topic, counting anything unrecognisable rather than failing —
   a shared broker carries other publishers' traffic.
2. Parses telemetry, rejecting malformed payloads.
3. Rejects a reading whose body claims a different `deviceId` than its
   topic, rather than guessing which to believe.
4. Validates ranges via `TelemetryValidator`.
5. Updates the device registry and the counters.

Nothing thrown in a message callback escapes. Paho would log it and carry
on, and the gateway would look healthy while silently dropping every
message of that shape.

**Failures are counted apart, not lumped together.** Each kind points at a
different fault, and a single counter would hide which is happening:

| Counter | What it suggests |
|---|---|
| `telemetryMalformed` | A producer speaking the wrong format |
| `telemetryInvalid` | A sensor or simulation producing impossible values |
| `unroutableMessages` | Another publisher using the fleet topic space |
| `invalidPresence` | One of our devices publishing an unknown presence value |
| `handlerErrors` | A bug in the gateway — should always be zero |

`handlerErrors` exists because Paho catches anything escaping a message
callback, logs it, and carries on. Without an explicit boundary and counter
the gateway would report itself healthy while silently dropping every
message of some shape.

## Failure detection

Two paths, because neither covers the other
([ADR-006](../docs/decisions/ADR-006-failure-detection.md)):

| Failure | Connection | Last Will | Heartbeat timeout |
|---|---|---|---|
| Power loss, kill, network cut | drops | **fires** | would also fire, later |
| Wedged process, blocked loop | **stays up** | never fires | **fires** |

The second row is why this phase exists. A device that stays connected and
keeps publishing telemetry while its liveness path has wedged produces no
Last Will at all — and **telemetry is deliberately not treated as proof of
life**, or that fault would be undetectable.

Devices walk `UNKNOWN → ONLINE → SUSPECTED → OFFLINE → RECOVERING → ONLINE`:

- **SUSPECTED** exists because heartbeats travel at QoS 0, so one lost
  message is expected traffic rather than a symptom. A policy configured to
  condemn on a single miss is rejected at startup.
- **RECOVERING** is probation, not "being recovered": a failed device must
  deliver `GATEWAY_RECOVERY_CONFIRMATIONS` heartbeats before it is trusted.
  A replacement provisioned in Phase 9 enters through this same path.
- A device in **UNKNOWN** is never declared failed, however long it is
  silent — which keeps retained-presence ghosts out of the recovery path.

Transitions worth acting on are published to `fleet/{id}/events` at QoS 1.
`ONLINE → SUSPECTED` is not among them.

## HTTP API

| Endpoint | Returns |
|---|---|
| `GET /health` | Liveness and fleet-wide counters |
| `GET /devices` | Every known device, ordered by id |
| `GET /devices/{id}` | One device, or 404 |

```bash
curl -s http://127.0.0.1:8080/health
curl -s http://127.0.0.1:8080/devices/device-001
```

### `devicesKnown` is not the fleet size

Retained presence outlives the device that published it (ADR-004), so a
retained `OFFLINE` proves a device existed once — not that it exists now.
A gateway started against a broker with history will report devices it has
never heard a reading from.

`/health` therefore reports three different numbers, and each device
carries a `presenceOnly` flag:

- `devicesKnown` — every device id seen, including retained ghosts
- `devicesReporting` — devices that have actually sent a reading
- `devicesOnline` — devices the broker last reported as connected

Phase 4 must reconcile against `devicesReporting`, not `devicesKnown`, or
every stale retained entry becomes a phantom failure to recover.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `MQTT_BROKER_URL` | `tcp://127.0.0.1:1883` | Broker to subscribe to |
| `GATEWAY_CLIENT_ID` | `fleet-gateway` | Must be unique on the broker |
| `GATEWAY_SUBSCRIPTION_QOS` | `1` | Presence matters, so QoS 1 |
| `GATEWAY_HTTP_HOST` | `127.0.0.1` | Bind address |
| `GATEWAY_HTTP_PORT` | `8080` | `0` picks a free port |
| `GATEWAY_RUN_DURATION_SECONDS` | `0` | `0` runs until interrupted |
| `GATEWAY_HEARTBEAT_INTERVAL_MS` | `1000` | **Must match the fleet's `FLEET_PUBLISH_INTERVAL_MS`** |
| `GATEWAY_SUSPECT_AFTER_MISSES` | `2` | Minimum 2; one miss must never condemn |
| `GATEWAY_OFFLINE_AFTER_MISSES` | `4` | Must exceed the suspect threshold |
| `GATEWAY_RECOVERY_CONFIRMATIONS` | `2` | Heartbeats needed to leave probation |
| `GATEWAY_MONITOR_INTERVAL_MS` | `250` | How often the silence sweep runs |
| `MQTT_KEEPALIVE_SECONDS` | `60` | |
| `MQTT_CONNECTION_TIMEOUT_SECONDS` | `10` | |
| `MQTT_OPERATION_TIMEOUT_SECONDS` | `10` | Ceiling on any blocking client call |
| `MQTT_CLEAN_SESSION` | `true` | |

## Running

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn clean package
/opt/homebrew/opt/mosquitto/sbin/mosquitto -p 1883
```

```bash
M2=$HOME/.m2/repository
CP="gateway/target/classes:common/target/classes:$M2/org/eclipse/paho/org.eclipse.paho.client.mqttv3/1.2.5/org.eclipse.paho.client.mqttv3-1.2.5.jar:$M2/com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar"
java -cp "$CP" io.fleet.gateway.Main
```

Then start a fleet with `FLEET_SINK=mqtt` (see `edge-device/README.md`)
and watch the counters move.

The summary printed on shutdown is a **demonstration, not a result** —
figures count only when produced by a run recorded under
`experiments/results/` with its configuration attached.

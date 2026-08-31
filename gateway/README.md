# gateway

Java MQTT gateway. Subscribes to device topics, validates and deserializes
telemetry, tracks per-device state, and exposes a health/status API.

**Status:** complete — ingestion, validation, the device registry, the HTTP
API, automatic failure detection, persistent history, Prometheus exposition,
and forwarding to Kafka. Runs on Docker and on Kubernetes.

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
| Power loss, kill, network cut | drops | **fires — immediate** | would also fire, later |
| Wedged process, blocked loop | **stays up** | never fires | **fires** |

Both paths drive health. A fired will declares the device failed on
receipt — the event carries `missedHeartbeats: 0`, which is how you tell
the fast path from the timeout.

A device that shuts down **cleanly** publishes `SHUTDOWN` rather than
`OFFLINE` and is retired, not failed; otherwise stopping the fleet on
purpose would look like a fleet-wide failure.

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

### One gateway, and why it cannot be two

The gateway is a singleton by construction. It holds the device registry in
memory, takes an exclusive lock on the embedded H2 file, and connects to the
broker under a fixed `GATEWAY_CLIENT_ID`.

Scaling it does not share the load. MQTT client ids are unique per broker by
definition, so when the second gateway connects the broker disconnects the
first, which reconnects and disconnects the second, and so on. While each is
disconnected it is not receiving heartbeats — so it declares healthy devices
failed, and the recovery operator is asked to replace pods that are running
perfectly well. That is the observed outcome of one `kubectl scale
deployment/gateway --replicas=3`: three false failures in under a minute.

The Deployment therefore pins `replicas: 1` and `strategy: Recreate`, and both
are load-bearing. `ConnectionFlapDetector` covers the case where someone
overrides them anyway: after three dropped connections inside a minute it logs
what is happening and what to check, because Paho reports an eviction and a
broker restart with exactly the same message.

## Persistence

Telemetry and health events are stored in an embedded H2 database
([ADR-007](../docs/decisions/ADR-007-telemetry-storage.md)). Embedded
because containers do not arrive until Phase 7 and history should not wait
for them; H2 specifically because it is 2.8 MB of pure Java with no native
libraries, which keeps the gateway small and portable to the arm64
containers of Phase 7.

It sits behind a `TelemetryStore` interface, so a server-backed
time-series database can replace it later without the gateway changing —
the same seam that let MQTT land in Phase 2 without a rewrite.

- **Readings are batched**; at fleet rate, committing each one would make
  the store the bottleneck in Phase 8's throughput numbers.
- **Health events are written through** — rare, individually meaningful,
  and the input to MTTR.
- **Every query flushes first**, so a caller never reads a history that is
  still partly in memory.
- **Failed devices are derived from the event history**, not a status
  column, so the answer survives a gateway restart.
- **Retention is off by default.** A production fleet prunes; this
  project's reproducibility contract says raw data is kept.

A store failure is never fatal — losing history is bad, losing detection is
worse. But that means a run could finish with holes while every figure over
it looked normal, so **the gaps travel with the data**:

- The store counts readings *lost*, not failures, and records each loss
  durably with a timestamp and reason.
- `/stats` and `/history` return an `integrity` block beside the figures —
  `complete`, `droppedWrites`, and a blunt summary string.
- The run summary prints `history: complete`, or
  `INCOMPLETE — N readings lost; not a valid experiment record`.

A window that is not complete cannot support a result, and it says so
itself rather than relying on someone remembering to check a log.

## HTTP API

| Endpoint | Returns |
|---|---|
| `GET /health` | Liveness and fleet-wide counters. Always 200 while the process is alive |
| `GET /ready` | Readiness. 200 when the broker connection is up, **503** when it is not |
| `GET /devices` | Every known device, ordered by id |
| `GET /devices/{id}` | One device, or 404 |
| `GET /history?device=<id>&from=<ms>&to=<ms>` | Stored readings for a device |
| `GET /stats?from=<ms>&to=<ms>` | Fleet average, telemetry rate, failures, mean recovery |
| `GET /metrics` | Prometheus text exposition — the only route that is not JSON |

`/metrics` is Phase 10's. It renders what `GatewayMetrics` and
`DeviceRegistry` already count, plus five numbers off the JVM's MXBeans, and
stores nothing of its own. Scrape it through the **admin** Service (18081),
not the readiness-gated one — see
[ADR-012](../docs/decisions/ADR-012-observability-shape.md). It also reads
nothing from the store, so unlike `/history` and `/stats` it cannot answer
503.


`/health` and `/ready` are separate because they answer different
questions, and Kubernetes asks both. `/health` is 200 whenever the process
is alive, whatever the broker is doing — the right liveness answer, since a
broker outage should not restart the gateway. That also makes it useless as
a readiness probe: it could never fail, so a load balancer would keep
routing to a gateway that had lost the broker and was recording nothing.
`/ready` is the one that can say no. See
[ADR-010](../docs/decisions/ADR-010-kubernetes-deployment-shape.md).

`/history` and `/stats` read the store, not the registry — the registry
keeps only the latest reading per device, which is what the store exists
to avoid. They answer **503** rather than 500 when the store is
unavailable: the gateway is still detecting failures, only the history is
missing.

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
| `GATEWAY_STORE_ENABLED` | `true` | Persist telemetry and events |
| `GATEWAY_STORE_PATH` | `./data/fleet` | Database file, or `mem` for in-memory |
| `GATEWAY_STORE_BATCH_SIZE` | `200` | Readings buffered before a batch insert |
| `GATEWAY_STORE_FLUSH_INTERVAL_MS` | `1000` | Longest a buffered reading may wait |
| `GATEWAY_STORE_RETENTION_HOURS` | `0` | `0` never prunes |
| `GATEWAY_STORE_PRUNE_INTERVAL_MINS` | `60` | How often pruning runs |
| `GATEWAY_STORE_ALLOW_REMOTE` | `false` | H2 mixed mode; opens a TCP port when on |
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

# MQTT topic specification

Describes what the system publishes today (Phase 2). Topics consumed by
the gateway are added in Phase 3; heartbeats in Phase 4.

Broker: Mosquitto 2.1.2, `tcp://127.0.0.1:1883` by default. Rationale for
the client, QoS, and presence semantics is in
[ADR-004](../decisions/ADR-004-mqtt-client-and-delivery-semantics.md).

## Topics

| Topic | Direction | QoS | Retained | Status |
|---|---|---|---|---|
| `fleet/{deviceId}/telemetry` | device → broker | 0 | no | **live** |
| `fleet/{deviceId}/status` | device → broker | 1 | **yes** | **live** |
| `fleet/{deviceId}/heartbeat` | device → broker | — | — | Phase 4 |
| `fleet/{deviceId}/events` | device → broker | — | — | Phase 4 |

`{deviceId}` matches the ids the simulator generates, e.g. `device-001`.
Constants live in `io.fleet.common.Topics` so no module builds a topic
string by hand.

## `fleet/{deviceId}/telemetry`

One JSON object per reading. Field order is part of the format — the
constrained and naive device variants serialize by different mechanisms
and must produce byte-identical output.

```json
{"deviceId":"device-001","ts":1787484895182,"temp":19.90,"vib":1.01,"batt":99.95,"lat":52.5235,"lon":13.4083,"status":"OK"}
```

| Field | Type | Precision | Meaning |
|---|---|---|---|
| `deviceId` | string | — | Stable device identifier |
| `ts` | integer | — | Reading time, epoch milliseconds |
| `temp` | number | 2 dp | Degrees Celsius |
| `vib` | number | 2 dp | Vibration, arbitrary units from 0 |
| `batt` | number | 2 dp | Battery percentage, 0–100 |
| `lat` | number | 4 dp | Latitude, −90 to 90 |
| `lon` | number | 4 dp | Longitude, −180 to 180 |
| `status` | string | — | `OK`, `DEGRADED`, or `CRITICAL` |

Precisions come from `io.fleet.common.TelemetryFormat`, which both
variants read, so the two cannot drift apart.

**QoS 0 and its consequence for Phase 4.** Readings are periodic and
individually disposable, so telemetry is fire-and-forget. Failure
detection must therefore never treat a single missing reading as evidence
of failure — that is why the design calls for consecutive missed
heartbeats rather than one.

## `fleet/{deviceId}/status`

Connection-level presence. Payload is the literal text `ONLINE` or
`OFFLINE` (`io.fleet.common.Presence`). Distinct from the `status` field
inside telemetry, which is the device's opinion of its own sensors —
this topic is the broker's account of whether the device is connected at
all.

Three transitions, and the difference between them is what makes the
signal trustworthy:

| Event | Publisher | Payload |
|---|---|---|
| Device connects | device | `ONLINE` |
| Device crashes or loses its network | **broker**, via Last Will | `OFFLINE` |
| Device shuts down cleanly | device, then a real DISCONNECT | `OFFLINE` |

A clean shutdown sends a DISCONNECT packet, which suppresses the will.
An orderly stop therefore does **not** look like a failure. A device that
loses power, is killed, or loses its network never sends that packet, so
the broker publishes the will on its behalf — this is the primary failure
signal Phase 4 consumes.

Retained, so a subscriber joining later immediately learns the state of
every device rather than waiting for a transition that may never come.

**Caveat for consumers:** retained messages outlive the device that set
them. A retained `OFFLINE` for `device-042` does not prove `device-042`
currently exists — only that it did once. The gateway must reconcile
retained presence against its own device registry rather than trusting it
as a fleet inventory.

## Observing the fleet

```bash
mosquitto_sub -h 127.0.0.1 -t 'fleet/+/telemetry' -v
mosquitto_sub -h 127.0.0.1 -t 'fleet/+/status' -v
```

Clear a stale retained presence entry by publishing an empty retained
payload to the same topic:

```bash
mosquitto_pub -h 127.0.0.1 -t 'fleet/device-001/status' -r -n
```

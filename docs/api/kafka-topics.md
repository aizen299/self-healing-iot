# Kafka topics

Five topics, fixed. Kafka is a **downstream copy, never the system of
record**, and nothing on the failure-detection path touches it — see
[ADR-009](../decisions/ADR-009-kafka-streaming.md).

Every record is keyed by device id, which is what puts a device's readings
and its failures on the same partition and keeps them in order relative to
each other.

| Topic | Written by | Read by |
|---|---|---|
| `telemetry.raw` | gateway | stream processor |
| `telemetry.processed` | stream processor | — |
| `device.events` | gateway | — |
| `device.failures` | gateway | recovery operator |
| `device.recovery` | gateway **and** recovery operator | — |

## `telemetry.raw`

Every accepted reading, forwarded as **the exact bytes that arrived**, so
the wire format has one encoder in the whole system. The format is
[`mqtt-topics.md`](mqtt-topics.md)'s telemetry payload.

## `telemetry.processed`

One summary per device per closed window.

```json
{"deviceId":"device-001","windowStart":1787587250000,"windowEnd":1787587260000,
 "readings":6,"meanTemperature":25.47,"maxVibration":4.38,"minBattery":98.7,
 "degradedReadings":1,"criticalReadings":0}
```

`maxVibration` and `minBattery` are `null` when a window saw no valid
readings — the empty aggregate's sentinels are infinities, which are not
valid JSON.

## `device.events`

Every health transition the gateway announces.

```json
{"deviceId":"device-002","event":"DEVICE_OFFLINE","from":"SUSPECTED","to":"OFFLINE",
 "at":1787556961873,"missedHeartbeats":4,"recoveryDurationMillis":-1}
```

`missedHeartbeats: 0` on a failure means the broker's Last Will fired rather
than a heartbeat timing out — that is how the fast detection path is told
from the slow one. `recoveryDurationMillis` is `-1` on anything that is not
a recovery.

The codec is `common/DeviceEventCodec`, shared because the format has two
readers. Unknown fields are skipped, so a field can be added without every
reader being redeployed first; missing fields are refused.

## `device.failures`

A **strict subset** of `device.events`: `DEVICE_OFFLINE` only, same format.

Separate so the recovery operator cannot see anything it should not act on.
Filtering a firehose would work until somebody changed the filter, and this
consumer deletes pods.

## `device.recovery`

**Two producers, two unrelated schemas.** A consumer must switch on the
`kind` field before reading anything else.

A record **with** `kind: recovery-action` is the operator saying what it
did:

```json
{"kind":"recovery-action","deviceId":"device-002","recoveryId":"703117455b",
 "outcome":"REPLACED","replacementPod":"edge-device-device-002-r-703117455b",
 "detectedAt":1787591112345,"actedAt":1787591112505,
 "detectionToReplacementMillis":160}
```

`outcome` is one of `REPLACED`, `ALREADY_RECOVERED`, `NOT_NEEDED`, `FAILED`.
Every outcome is published, not only the successes — a topic carrying only
good news cannot support a recovery-success-rate figure.

A record **without** `kind` is a gateway health transition, in the
`device.events` format above, published when a replacement is confirmed
heartbeating:

```json
{"deviceId":"device-002","event":"DEVICE_RECOVERED","from":"OFFLINE","to":"ONLINE",
 "at":1787591114196,"missedHeartbeats":0,"recoveryDurationMillis":1851}
```

### Which number is MTTR

**The gateway's `recoveryDurationMillis`.**

| Number | Starts | Ends |
|---|---|---|
| `detectionToReplacementMillis` | failure detected | the API server accepts the replacement pod |
| `recoveryDurationMillis` | failure detected | the replacement's heartbeats are confirmed |

They start at the same instant and the second **contains** the first, so
adding them double-counts. `detectionToReplacementMillis` is `null` for any
outcome that replaced nothing: for those the subtraction is still arithmetic
but not a measurement, and averaging it across the topic would be badly
wrong.

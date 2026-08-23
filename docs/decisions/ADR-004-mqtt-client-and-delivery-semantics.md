# ADR-004: MQTT client, QoS, and presence semantics

## Status
Accepted — 2026-08-23

## Context
Phase 2 puts device telemetry on a real broker. Four things had to be
decided before writing the sink: which client library, what quality of
service, how a device announces that it is alive or gone, and whether
devices share a connection.

## Decision

### Eclipse Paho MQTT 3.1.1 client (`org.eclipse.paho.client.mqttv3` 1.2.5)

Chosen over the MQTT 5 Paho client and over the HiveMQ client. It is the
most widely deployed Java MQTT client, it supports everything the
failure-detection story needs (Last Will, QoS, keep-alive, automatic
reconnect), and it has **no transitive dependencies** — a 240 KB jar. On
a project whose thesis is resource-conscious engineering, a client that
drags in a dependency tree would be difficult to justify in the writeup.

MQTT 5 offers reason codes and session expiry that would make failure
diagnosis richer. That is a real advantage and a plausible later upgrade,
but the v5 Java client is less battle-tested and Phase 4 does not need
either feature to detect a missing device. Deferred rather than rejected.

### One connection per device

Devices do not share an MQTT client. This is forced by the Last Will:
a will belongs to a *connection*, so a shared client would give the whole
fleet one will, and the broker could only ever announce "the fleet
disconnected" — never "device-017 disconnected", which is the signal
Phase 4 exists to consume. Per-device connections also let a simulated
network fault take down one device without touching its neighbours.

The cost is 50 broker connections for a 50-device fleet. Mosquitto
handles that without difficulty at this scale.

### QoS 0 for telemetry, QoS 1 for presence

Telemetry is **QoS 0** (at most once). Readings are periodic, individually
disposable, and high volume; a lost reading is replaced by the next one a
second later. Paying for acknowledgements per reading would buy delivery
guarantees the data does not need and would distort throughput
measurements with broker round-trips. This is also the conventional
choice for IoT telemetry.

Presence messages are **QoS 1** (at least once). Losing a presence
transition is not self-correcting the way a lost reading is: a dropped
OFFLINE would leave the fleet's recorded state permanently wrong until
the device next connects.

Note the interaction with Phase 4: because telemetry is QoS 0, failure
detection must not treat a single missing reading as evidence of failure.
That is already the plan — consecutive missed heartbeats, not one.

### Retained presence on `fleet/{deviceId}/status`

Each device publishes retained `ONLINE` on connect, registers retained
`OFFLINE` as its Last Will, and on a clean shutdown publishes retained
`OFFLINE` before sending a proper DISCONNECT.

Retained, so a subscriber that connects later immediately learns the
state of every device instead of waiting for the next transition — which
for a healthy device might be never. The gateway in Phase 3 depends on
this to build its initial view of the fleet.

The clean-shutdown path matters as much as the failure path: sending a
real DISCONNECT suppresses the will, so an orderly stop does not
masquerade as a device failure. A sink that is never closed would produce
exactly that false signal on every run.

## Consequences
- Positive: the broker itself reports per-device disappearance, so Phase 4
  gets a failure signal that does not depend on the gateway noticing an
  absence of data.
- Positive: a simulated network interruption is genuinely indistinguishable
  from a real one at the broker, because it drops the socket without a
  DISCONNECT packet rather than faking an event.
- Negative: retained presence outlives a run. Restarting the fleet with
  different device ids leaves stale retained entries in the broker, and a
  subscriber will see them. Acceptable for a local broker, but the Phase 3
  gateway must not assume every retained status corresponds to a device
  that still exists.
- Negative: Paho accepts only a whole `byte[]`, so publishing copies the
  payload. The constrained variant hands over a slice of its reused
  buffer, so its zero-allocation property does not survive the MQTT hop.
  This is why Pillar A is measured through the counting sink, which is
  also the default — the comparison must measure telemetry *production*,
  not the client library. The sink copies unconditionally so that both
  variants do identical work if anyone does measure through it.
- Revisit if: the project needs per-message delivery guarantees, richer
  error reporting, or session expiry — all of which point to MQTT 5.

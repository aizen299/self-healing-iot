# ADR-009: What goes on Kafka, and what deliberately does not

## Status
Accepted — 2026-08-23

## Context
Phase 6 introduces Kafka. The project's own rule is that Kafka arrives only
after MQTT → gateway already works end to end, and it does: telemetry is
ingested, validated, persisted, and failures are detected without a broker
anywhere in the picture.

That rule exists alongside a warning — do not "route every internal
operation through Kafka just because it exists". The interesting decision
in this phase is therefore not how to use Kafka. It is what to keep away
from it.

## Decision

### Kafka is a downstream copy, never the system of record

The gateway keeps its registry, its detection loop, and its store. What
goes to Kafka is what something downstream needs to consume.

Concretely: **nothing on the detection path touches Kafka.** A device
failing and the gateway noticing are separated by a heartbeat timeout and
nothing else. Putting a broker, a consumer group, and partition lag between
those two events would add a failure mode to the one part of the system
that exists to detect failure modes.

The stream processor is a separate service for the same reason. It derives
fleet statistics; it cannot slow down or break detection, because detection
does not wait for it.

### Forwarding is optional and never fatal

`GATEWAY_KAFKA_ENABLED` defaults to **false**. The gateway ingests,
detects, and persists perfectly well without a broker, and needing one to
run the gateway would be a step backwards from Phase 5.

When enabled, sends are asynchronous and failures are counted in the
callback rather than thrown. This is the same priority the store already
has: losing a downstream copy is bad, losing detection is worse. Producer
timeouts are bounded rather than left at their two-minute defaults,
because the producer is called from the MQTT callback thread — the thread
that also runs detection.

### Telemetry is forwarded as the exact bytes that arrived

The MQTT payload is already the wire format, so `telemetry.raw` republishes
it verbatim. There is no second encoder, which means there is nothing that
could drift from the first — the guarantee `WireFormatRoundTripTest`
protects between the device and the gateway extends to Kafka for free.

This is also why the parser moved from `gateway` to `common`, and why
`common` gained its first dependency. The format now has two readers, and a
duplicated parser is exactly how two components quietly stop agreeing. The
constrained edge device never loads Jackson: its encoder is hand-rolled
precisely to avoid a JSON library on the hot path.

### Failures get their own topic

`device.failures` is a strict subset of `device.events`. The duplication is
deliberate: Phase 9's recovery controller cares about exactly one kind of
event, and a consumer that should only ever act on failures should not be
able to see anything else by accident. Filtering a firehose correctly is a
thing you can get wrong; subscribing to the right topic is not.

### Everything is keyed by device id

That is what puts a device's readings and its failures on the same
partition and keeps them ordered relative to each other. Any windowed
aggregation depends on it.

### The topology is built separately from its runner

`FleetTopology` produces a `Topology`; `Main` runs it. That split is what
lets `TopologyTestDriver` drive the aggregation with a controllable clock,
so window boundaries and late arrivals are tested exactly rather than
waited for. Testing this through a live broker would be slower, flakier,
and would mostly test Kafka.

The running aggregate is immutable and carries a running **sum** rather
than a running average — averaging averages loses the count and gives the
wrong answer the moment windows merge. Its serde is a fixed binary layout,
because Streams persists aggregates to a changelog and restores them after
a restart: that format is durable state, and changing it is a migration
rather than a refactor.

### A malformed record is dropped and counted, not fatal

One bad producer must not stop the fleet's statistics being computed, and
an uncaught exception in a topology takes the stream thread down with it.

## Consequences
- Positive: the failure-detection path is unchanged by this phase. Kafka
  can be down, misconfigured, or absent and detection still works.
- Positive: `telemetry.raw` is byte-identical to what went over MQTT, so
  the wire format has exactly one encoder in the whole system.
- Positive: Phase 9 can subscribe to `device.failures` alone.
- Negative: Kafka is the heaviest service in the stack by a wide margin —
  392 MB resident against a 1 GB limit, versus 3 MB for the MQTT broker. On
  an 8 GB host it should be left out unless the phase under test needs it.
- Negative: `common` is no longer dependency-free. One 572 KB streaming
  parser, unloaded by the edge device, in exchange for a single definition
  of how to read the wire format.
- Negative: single-node, replication factor 1, `acks=1`. Correct for one
  broker on a laptop and wrong for anything else; the Kubernetes phase will
  have to revisit all three.
- Revisit if: something downstream needs ordering guarantees across
  devices, which keying by device id deliberately does not provide.

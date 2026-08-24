# stream-processor

Kafka Streams topology over the fleet's telemetry. Reads `telemetry.raw`,
groups by device, and emits a summary per device per window to
`telemetry.processed`.

**Status:** Phase 6 complete. Windowed aggregation and status counts.

## What a window contains

```json
{"deviceId":"k6-001","windowStart":1787504880000,"windowEnd":1787504890000,
 "readings":4,"meanTemperature":29.81,"maxVibration":3.43,"minBattery":99.80,
 "degradedReadings":0,"criticalReadings":0}
```

## What this is deliberately not

**Nothing here is on the failure-detection path.** The gateway detects
failures from heartbeat timing and the broker's Last Will; putting a
consumer group and partition lag between a device failing and anybody
noticing would add a failure mode to the one component that exists to
detect them. See [ADR-009](../docs/decisions/ADR-009-kafka-streaming.md).

This module derives statistics. It can lag, restart, or be absent entirely
and detection is unaffected.

## Design notes

| Choice | Reason |
|---|---|
| Topology built separately from its runner | Lets `TopologyTestDriver` drive the aggregation with a controllable clock, so window boundaries and late arrivals are tested exactly rather than waited for |
| Running **sum**, not running average | Averaging averages loses the count and gives the wrong answer the moment windows merge |
| Immutable aggregate | Streams may retain and re-serialise an aggregate between updates; mutating in place is how an aggregation quietly goes wrong after a changelog restore |
| Fixed binary serde | The aggregate is persisted to a changelog and restored after a restart, so the format is durable state — changing it is a migration, not a refactor |
| Malformed records dropped and counted | One bad producer must not stop the fleet's statistics, and an uncaught exception takes the stream thread down with it |
| Keyed by device id | Puts a device's readings on one partition, in order, which the windowing depends on |
| `suppress(untilWindowCloses)` | Without it the aggregate is forwarded on every input record, so `telemetry.processed` carries a changelog of partial windows at the input rate rather than one summary per window |
| One parse per record | The first version validated in a `flatMapValues` and parsed again in the aggregator, decoding every record twice on the topology's only hot path |

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker to read from and write to |
| `STREAM_APPLICATION_ID` | `fleet-stream-processor` | Also names the internal topics |
| `STREAM_WINDOW_SECONDS` | `10` | Aggregation window |
| `STREAM_GRACE_SECONDS` | `5` | How late a record may arrive and still count |

## Running

```bash
docker compose up broker kafka gateway stream-processor
```

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server 127.0.0.1:9092 --topic telemetry.processed --from-beginning
```

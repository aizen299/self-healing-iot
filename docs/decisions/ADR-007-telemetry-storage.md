# ADR-007: Embedded storage now, a server-backed store later

## Status
Accepted — 2026-08-23

## Context
Phase 5 has to give the fleet a durable history: telemetry over time, and
the failure and recovery events that Pillar B's MTTR is computed from.

The obvious answer is a time-series database — InfluxDB, TimescaleDB — and
that is what the design calls for. But every one of them is a server, and
containerisation is Phase 7. The build order exists to stop exactly this
kind of reach-ahead, and there is a practical reason behind the rule here:
the Docker daemon is not even running on this machine, so a phase that
depended on it could not be demonstrated at all.

The 8 GB ceiling (ADR-003) applies too. The gateway already shares that
budget with a broker, and will soon share it with Kafka, Prometheus, and
Grafana. Its own footprint is something Phase 8 measures.

## Decision

### A `TelemetryStore` seam, embedded implementation behind it

The same pattern that carried MQTT in: name the interface, implement what
this phase can honestly run, and let a later phase add an implementation
without the gateway changing. `TelemetrySink` made Phase 2 additive rather
than a rewrite, and this is the same bet.

Phase 7 can add a server-backed store behind this interface when the
container stack exists. Nothing above the interface has to know.

### H2, not SQLite

Both are embedded and both were available. H2 is **2.8 MB of pure Java**;
the SQLite JDBC driver is 14 MB because it bundles native libraries for
every platform it supports.

Pure Java matters twice over. It keeps the gateway small in a stack that
has to fit in 8 GB, and it removes a platform variable from Phase 7: the
containers will be linux/arm64 while development is macOS/arm64, and a
native library is one more thing that can differ between the machine where
an experiment is designed and the one where it runs.

H2 is not a time-series database, and this ADR does not pretend it is. It
is a relational store with a schema and indexes designed for time-series
queries, which is enough for the questions this project asks.

### The query set is deliberately closed

`TelemetryStore` exposes named queries — device history, fleet average,
telemetry rate, failed devices, recoveries, mean recovery — rather than a
generic query interface. These are the questions the research asks, so the
schema can be indexed for the queries that exist rather than for queries
someone might invent. There are exactly two telemetry indexes, one per
access pattern, because each is paid for on every insert and inserts are
the hot path.

### An identity primary key, not `(device_id, ts)`

The natural key looks right and is wrong here: `MESSAGE_FLOOD` emits
several readings inside one millisecond, so a unique constraint on the
timestamp would reject precisely the traffic a flood experiment exists to
record.

### Telemetry is batched; events are written through

Readings are buffered and inserted in batches, because at fleet rate the
per-statement round trip would make the store rather than the pipeline the
bottleneck in Phase 8's throughput numbers.

Health transitions bypass the buffer. They are rare, individually
meaningful, and the input to MTTR — losing one to a crash would cost far
more than losing a reading. Every query flushes first, so a caller never
reads a history that is still partly in memory.

### Retention is off by default

A production fleet prunes. This project's reproducibility contract says
raw experiment data is committed and kept, so silently deleting history
would be the wrong default here. Pruning exists and is configurable;
`GATEWAY_STORE_RETENTION_HOURS=0` means never.

### Failed devices are derived from the event history

Not from a status column. The live registry is rebuilt from nothing when
the gateway restarts, but the record of what happened is not — so asking
the store which devices are failed gives an answer that survives a
restart, which is the point of having a store at all.

## Consequences
- Positive: persistence works today, on this machine, with no daemon and
  no container. The demonstration ran end to end and the database is 52 KB
  on disk.
- Positive: the gateway gains one 2.8 MB dependency and no native code.
- Positive: MTTR has a durable home before Phase 9 produces the recoveries
  to put in it.
- Negative: H2 lacks what a real time-series database offers —
  downsampling, retention policies, compression, continuous aggregates. At
  this fleet size and duration none of it is needed, but a longer or larger
  study would feel the absence.
- Negative: one connection guarded by a monitor. Fine for one batched
  writer and occasional readers; it would need a pool before the store
  served concurrent analytical queries.
- Negative: a store failure is counted and reported but never fatal — the
  gateway keeps detecting failures with a broken store. That is the right
  priority, but it means a run can complete "successfully" with an
  incomplete history, so `store errors` must be checked before any recorded
  experiment is trusted.
- Revisit if: Phase 7 brings up a container stack, at which point a
  server-backed store behind the same interface becomes cheap — or if a
  study runs long enough that retention and downsampling stop being
  optional.

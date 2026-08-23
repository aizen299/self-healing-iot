# stream-processor

Kafka Streams topology over `telemetry.raw`, `device.events`,
`device.failures`, and `device.recovery`. Implements windowed
aggregation, rolling averages/anomaly thresholds, and fleet-level
statistics, and writes processed telemetry for time-series storage.

**Status:** not yet implemented (target: Phase 6; the time-series
persistence it writes into lands first, in Phase 5).

# gateway

Java MQTT gateway. Subscribes to device topics, validates and
deserializes telemetry, tracks per-device last-seen timestamps, detects
heartbeat timeouts, drives the device state machine
(`ONLINE -> SUSPECTED -> OFFLINE -> RECOVERING -> ONLINE`), publishes
failure events, exposes a health/status API, and forwards telemetry
downstream to Kafka.

**Status:** not yet implemented (target: Phase 3 for MQTT ingestion,
Phase 4 for heartbeat/failure detection).

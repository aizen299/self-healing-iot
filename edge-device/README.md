# edge-device

Simulated IoT/edge device. Generates telemetry (temperature, vibration,
battery level, location) and heartbeats over MQTT, with a configurable
publishing interval and deterministic, configurable failure modes
(`CRASH`, `HEARTBEAT_STOP`, `NETWORK_INTERRUPTION`, `MESSAGE_FLOOD`).

Two implementations will live here:

- `constrained/` — small heap, controlled allocation, object reuse,
  bounded queues, controlled thread usage, efficient serialization.
- `naive/` — straightforward implementation with no resource discipline,
  used as the experimental baseline.

**Status:** not yet implemented (target: Phase 1; the constrained-vs-naive
comparison is Pillar A of the evaluation workstream — see the root
`README.md` — not a separate build phase).

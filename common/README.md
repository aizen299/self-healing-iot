# common

Shared code used across modules: telemetry/event DTOs, wire-format
definitions, MQTT/Kafka topic name constants, and small utilities. Kept
deliberately thin — this is not a dumping ground for unrelated shared
logic. No dependencies.

**Status:** Phases 1–3 complete for the telemetry path. Kafka topic
constants arrive in Phase 6; gateway-facing event DTOs in Phases 3–4.

## Contents

| Type | Purpose |
|---|---|
| `Telemetry` | A reading. Immutable, so one allocation per reading — used by the naive variant, tests, and the gateway from Phase 3. The constrained variant deliberately bypasses it. |
| `DeviceIdentity` | Static device identity, established once at construction. |
| `DeviceStatus` | Device-reported health (`OK`/`DEGRADED`/`CRITICAL`) plus the shared `classify` rule. Distinct from the gateway's heartbeat-derived state machine, which arrives in Phase 4. |
| `TelemetryFormat` | The wire format's decimal precisions. |
| `SensorModel` | Deterministic, allocation-free sensor simulation. |
| `TelemetrySink` | The publish seam. Implemented in-memory for measurement and over MQTT for the real pipeline. |
| `TelemetrySinkFactory` | Supplies a sink per device and owns their lifetime. Per-device rather than shared because an MQTT Last Will belongs to a connection. |
| `Presence` | Connection-level `ONLINE`/`OFFLINE`, published retained on `fleet/{id}/status`. Distinct from `DeviceStatus`. |
| `TelemetryValidator` | Range and well-formedness checks, used by the gateway from Phase 3. |
| `Topics` | MQTT topic names following `fleet/{deviceId}/...`. |
| `FleetException` and subtypes | Checked exception hierarchy. |
| `ConfigurationException`, `Env` | Shared, fail-loud environment parsing so every module reports a bad value the same way. |

## Two deliberate choices

**`SensorModel` is the experimental control.** Both edge-device variants
drive an identically seeded instance, so they process exactly the same
sequence of readings and any measured difference is attributable to
implementation discipline rather than to the data. It exposes primitive
accessors after `advance()` instead of returning an object, so generating
a reading costs no allocation, and it uses its own xorshift rather than
`java.util.Random`, whose shared-state CAS would be a confounder on the
constrained hot path.

**`Topics` allocates, visibly.** Its methods build a new String per call.
That cost is left in the open rather than hidden behind an internal cache
because the naive variant calls them per reading while the constrained
variant calls them once per device — caching here would erase a real
difference between the two implementations that Pillar A is meant to
observe.

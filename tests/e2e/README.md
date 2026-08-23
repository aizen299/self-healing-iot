# tests/e2e

End-to-end scenario: start device -> send telemetry -> verify gateway
receives it -> kill device -> verify heartbeat-timeout detection ->
verify failure event -> verify recovery controller reacts -> verify
replacement telemetry resumes -> record recovery duration.

**Status:** empty until Phase 9 (recovery operator) is in place; this
is the project's core reproducible demo scenario.

# tests/unit

Unit tests: telemetry validation, heartbeat logic, failure state
transitions, recovery decision logic, configuration validation.

**Status:** intentionally empty, and likely to stay that way.

Unit tests live beside the code they cover, in each module's
`src/test/java`, rather than here. Maven runs a module's tests as part of
building that module, so moving them out would mean `mvn test` in
`edge-device/` verified nothing — breaking the independently-buildable
modules that ADR-001 is built around.

This directory remains for genuinely cross-module unit-level suites, if
any turn out to be needed. `tests/integration` and `tests/e2e` are
different: those cross module boundaries by definition and do belong at
the top level.

Phase 1 unit tests are in `common/src/test/java` and
`edge-device/src/test/java`.

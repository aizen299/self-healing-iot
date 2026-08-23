# recovery-operator

Java-based Kubernetes controller (Java Operator SDK preferred, plain
Kubernetes Java client as fallback) that consumes failure events and
provisions/restarts replacement device workloads. Recovery must be
idempotent — the same failure event arriving twice must not create two
replacements — and recovery state (device, replacement identity,
recovery id) must be tracked explicitly.

**Status:** not yet implemented (target: Phase 9).

# tests/integration

Integration tests across module boundaries: MQTT -> Gateway,
Gateway -> Kafka, Kafka -> storage, failure event -> recovery.

**Status:** MQTT → Gateway is covered as of Phase 3, along with the
wire-format round trip between the device's hand-rolled encoder and the
gateway's parser — a boundary neither module's own tests can guard.
Gateway → Kafka and Kafka → storage follow in Phase 6.

A Maven module (`integration-tests`) with test-scoped dependencies on the
modules it exercises. Tests skip themselves when no broker is listening.

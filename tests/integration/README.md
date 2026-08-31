# tests/integration

Integration tests across module boundaries: MQTT -> Gateway,
Gateway -> Kafka, Kafka -> storage, failure event -> recovery.

**Status:** MQTT → Gateway and heartbeat-timeout detection are covered, along
with the wire-format round trip between the device's hand-rolled encoder and
the gateway's parser — a boundary neither module's own tests can guard.
Gateway → Kafka and Kafka → storage are covered by the gateway's and stream
processor's own suites, against mock clients.

A Maven module (`integration-tests`) with test-scoped dependencies on the
modules it exercises. Tests skip themselves when no broker is listening.

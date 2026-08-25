# API documentation

| Document | Contract |
|---|---|
| [`mqtt-topics.md`](mqtt-topics.md) | The MQTT wire format: topics, payloads, QoS, retention, and Last Will |
| [`gateway-http.md`](gateway-http.md) | The gateway's read-only HTTP API, including the `/metrics` exposition |
| [`kafka-topics.md`](kafka-topics.md) | What each Kafka topic carries, and who writes it |

The recovery operator's own `/metrics` is documented in
[`recovery-operator/README.md`](../../recovery-operator/README.md); it has no
other HTTP surface.

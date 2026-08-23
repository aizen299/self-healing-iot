# infrastructure/docker

Dockerfiles per module and a root-level `docker-compose.yml` (see
repository root) for running the full local stack: MQTT broker, gateway,
Kafka, time-series database, Prometheus, Grafana.

**Status:** not yet implemented (target: Phase 7). Until then the local
MQTT broker runs natively via Homebrew Mosquitto — Docker is deliberately
deferred so early phases stay light on an 8 GB machine.

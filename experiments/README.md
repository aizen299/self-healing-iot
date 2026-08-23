# Experiments

Reproducibility contract for every experiment run in this project (see
project skill, sections 14-15). Nothing here yet — this directory is
scaffolding for the evaluation workstream, which runs alongside the
build phases rather than after them (see the root `README.md` for the
pillar-to-phase mapping).

## Layout

- `configs/` — experiment configuration files (device count, publishing
  interval, failure mode, JVM heap limit, MQTT/Kafka/Kubernetes config).
- `scripts/` — scripts that run an experiment end-to-end from a config
  and write results.
- `results/raw/` — raw, unprocessed output from experiment runs.
- `results/processed/` — cleaned/aggregated data derived from `raw/`,
  plus any generated charts.

## Required metadata per experiment

Every experiment run must record: machine specs, JVM version, heap
limit, device count, publishing interval, experiment duration, MQTT
configuration, Kafka configuration, Kubernetes configuration, and
failure mode. No numeric result is reported anywhere in this project
(docs, README, presentation) unless it was produced by an actual run
recorded here.

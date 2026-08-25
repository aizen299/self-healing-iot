# Experiments

Reproducibility contract for every experiment run in this project. The
evaluation workstream runs alongside the build phases rather than after them
(see the root `README.md` for the pillar-to-phase mapping); Phase 11 is where
Pillar B is exercised systematically and the first recorded results land here.

## Running one

```bash
./experiments/scripts/run-recovery-experiment.sh experiments/configs/b1-pod-loss.env
```

Needs a cluster deployed with `--recovery`. The runner refuses to start
otherwise, and refuses to start against a fleet that is already a device down
— a run beginning from an unknown state measures recovery from an unknown
state.

It writes `results/raw/<run-id>/` and then calls `summarise.py`, which derives
`results/processed/<run-id>.csv` and a summary. Re-running the summariser on
the same raw directory reproduces the same output exactly: the processing is a
pure function of what was recorded, which is what makes committed results
checkable rather than merely present.

The runner exits non-zero if any iteration failed to recover, and records the
run anyway. A recovery-success-rate of 100% means nothing if the runs that
were not 100% were deleted.

## Layout

- `configs/` — experiment configuration files (device count, publishing
  interval, failure mode, JVM heap limit, MQTT/Kafka/Kubernetes config).
- `scripts/` — scripts that run an experiment end-to-end from a config
  and write results.
- `results/raw/` — raw, unprocessed output from experiment runs.
- `results/processed/` — cleaned/aggregated data derived from `raw/`,
  plus any generated charts.

## Where the numbers come from

Two independent views of every recovery, kept apart on purpose:

| Source | Measures | File |
|---|---|---|
| The system's own records on `device.recovery` | the gateway's `recoveryDurationMillis` (**this is MTTR**) and the operator's `detectionToReplacementMillis` | `raw/<run-id>/recovery.jsonl` |
| The runner, polling the gateway's HTTP API from outside the cluster | wall-clock from `kubectl delete` to the gateway reporting `ONLINE` | `raw/<run-id>/iterations.jsonl` |

The external view should always exceed MTTR — it includes the poll interval
and starts before detection. It exists so that a disagreement between the
system's account of itself and an outside observer's is *visible* rather than
averaged into one number.

**The two internal durations must never be added.** The operator's ends when
the API server accepts the replacement pod; the gateway's starts at the same
instant, ends when heartbeats are confirmed, and already contains it.

## Required metadata per experiment

Every experiment run must record: machine specs, JVM version, heap
limit, device count, publishing interval, experiment duration, MQTT
configuration, Kafka configuration, Kubernetes configuration, and
failure mode. No numeric result is reported anywhere in this project
(docs, README, presentation) unless it was produced by an actual run
recorded here.

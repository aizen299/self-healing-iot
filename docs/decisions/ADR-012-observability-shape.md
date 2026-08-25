# ADR-012: Hand-rolled exposition, and what a dashboard is allowed to claim

## Status
Accepted — 2026-08-25

## Context
Phase 10 makes the fleet observable. The obvious reading of that is "add
Micrometer, add Prometheus, add Grafana", and two of those three are right.
The questions worth deciding are narrower:

1. What produces the metrics — a client library, or the counters this project
   already keeps.
2. Which of the gateway's two Services Prometheus scrapes, given that one of
   them disappears during an outage.
3. What a dashboard is permitted to assert, in a project whose reproducibility
   contract says no number is a result unless a recorded run produced it.

## Decision

### The exposition format is written by hand, in `common`

`PrometheusText` is about 100 lines and writes `# HELP`, `# TYPE`, and
samples. `MetricsExporter` (gateway) and `OperatorMetricsExporter` read the
counters that already exist and render them.

Micrometer would bring a registry, a naming convention, and a second place a
metric can be declared. Every number on this dashboard was already being
counted before Phase 10 started: `GatewayMetrics` has held
`failuresDetected`, `recoveriesObserved` and the rest since Phase 4, and the
operator has held its outcome counters since Phase 9. The library's job would
have been to hold counters this project already holds, and to expose them in
a format whose entire specification is four line shapes.

This is the same argument ADR-011 made about the Kubernetes client, and it has
the same limit: take the library the moment there is something for it to do
that is not already done. Percentile estimation over sliding windows, a
push-gateway, or exemplars would each be that moment.

The exporters are **readers, not registries**. They increment nothing and
store nothing; every value is read at scrape time. A metrics object that kept
its own copy of a count would be a second definition of that count, and the
two would drift.

### JVM series carry the `fleet_` prefix too

`fleet_jvm_heap_used_bytes`, not `jvm_memory_used_bytes`. The latter name
belongs to the Micrometer and simpleclient schemas, and a community dashboard
imported against it would find four numbers where it expects several dozen —
rendering panels that are wrong rather than panels that are empty.

These are the *gateway's* and the *operator's* JVMs. Pillar A — constrained
versus naive Java under an identical heap cap — is measured on the **device**
JVM by the experiment harness, and nothing scraped here contributes to it.

### Prometheus scrapes `gateway-admin`, not `gateway`

Phase 8 gave the gateway two Services on the same pod: `gateway`, which
readiness withdraws, and `gateway-admin`, which sets
`publishNotReadyAddresses: true`.

`/ready` returns 503 while the MQTT connection is down, which is correct — 
nothing should route telemetry to a gateway that is recording nothing. With
one replica, that also means the `gateway` Service has no endpoints at all
during a broker outage. Scraping through it would take the dashboard down at
precisely the moment somebody went looking at it, and the graph would show a
gap where it should show `fleet_gateway_broker_connected 0`.

The same reasoning gives the operator a `publishNotReadyAddresses: true`
Service even though it has no readiness probe today: if one is ever added, it
must not be able to silently remove the scrape.

`/metrics` also reads nothing from the store. `/history` and `/stats` run SQL
and answer 503 when the store is unavailable; a scrape endpoint that could
fail with the store would go dark for a second reason nobody wants.

### Static scrape targets, no service discovery

`kubernetes_sd_configs` needs a ServiceAccount with list and watch on pods and
endpoints. There are three targets and the manifests fix their names. Service
discovery is for a cluster where targets appear without anyone writing them
down; this is not one.

### The dashboard is a file, and the file is the dashboard

`infrastructure/monitoring/grafana/dashboards/fleet.json` is provisioned with
`allowUiUpdates: false`. A dashboard edited in the browser lives in Grafana's
SQLite database, where no diff can see it and no clean checkout reproduces it.

It has exactly one copy in the repository. `91-grafana.yaml` deliberately does
*not* embed a YAML transcription of it — `deploy.sh` builds the ConfigMap from
the JSON file — because two copies of a twenty-panel dashboard disagree the
first time one is edited. The volume is marked `optional: true` so that a
plain `kubectl apply -f monitoring/` yields Grafana with an empty Fleet folder
rather than a pod stuck on a missing ConfigMap.

### The two durations stay apart, and the dashboard says so

`fleet_recovery_duration_millis` is the gateway's histogram: detection to
confirmed heartbeats. That is MTTR.

`fleet_operator_detection_to_replacement_millis` is a summary, and its name is
the whole point. It ends when the API server accepts the replacement pod; it
starts at the same instant as the gateway's number and is *already contained
in it*. A dashboard that summed them would roughly double the reported MTTR.

Neither the metric name nor any panel title contains the string "MTTR" for the
operator's number — a test asserts that — while the `# HELP` line says
"must not be added" in full, because the HELP line is what a dashboard author
actually reads.

### Nothing on the dashboard is a result

A text panel on the dashboard itself says so, and so does `deploy.sh`'s
closing output. Grafana shows live operational values over a six-hour
retention window on an `emptyDir`. The reproducibility contract is unchanged:
a number is a result when a recorded run under `experiments/results/` produced
it, with machine specs, JVM version, heap limit, device count and the rest
alongside it.

This is not pedantry about wording. A screenshot of a Grafana stat panel is
exactly the kind of artefact that ends up in a report as though it were
measured, and the panel has no idea what heap the devices were running under.

## Consequences

- **Two more services, and they are not free.** `prom/prometheus:v3.1.0` is
  402 MB and `grafana/grafana:11.5.1` is 707 MB — Grafana alone is larger than
  Kafka. Monitoring is therefore a flag (`--monitoring`, and a Compose
  profile), not part of `base/`, for the same reason Kafka is.
- **The operator gained an HTTP port.** It had none. It still has no probes,
  and now that is a decision rather than an absence: a scrape endpoint is not
  a health check, this one answers 200 whether or not Kafka is reachable, and
  a liveness probe reading it would restart the recovery path during a broker
  outage.
- **`kind-cluster.yaml` gained two port mappings**, which means an existing
  cluster must be deleted and recreated to reach Grafana on 13000 — kind
  cannot add `extraPortMappings` to a running node. `deploy.sh --down` then
  `deploy.sh --monitoring`.
- **A metric added to `GatewayMetrics` does not appear on the dashboard by
  itself.** Someone has to write the sample line and the panel. That is the
  cost of not having a registry, and it is paid once per metric.
- **`GatewayMetrics.meanRecoveryMillis()` changed.** It divided the duration
  total by `recoveriesObserved`, which includes recoveries that contributed no
  duration — so it under-reported whenever the gateway's and the operator's
  clocks disagreed. It now divides by the number of durations actually
  recorded. The `/health` figure moves as a result.
- Anonymous Grafana with the Admin role is fine for a single-node cluster on a
  laptop and is not fine anywhere else. It is called out in both manifests.

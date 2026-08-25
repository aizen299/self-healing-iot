# infrastructure/monitoring

Prometheus scrape configuration and Grafana provisioning. **Status:**
implemented in Phase 10. See
[ADR-012](../../docs/decisions/ADR-012-observability-shape.md) for why the
exposition is hand-rolled and why Prometheus scrapes `gateway-admin` rather
than `gateway`.

```
prometheus/prometheus.yml                     Compose scrape config
grafana/provisioning/datasources/             the Prometheus datasource
grafana/provisioning/dashboards/              the file-based dashboard provider
grafana/dashboards/fleet.json                 the dashboard itself
```

The Kubernetes overlay in `../kubernetes/monitoring/` carries its own copy of
`prometheus.yml` inside a ConfigMap, because the scrape targets are the one
thing that genuinely differs between the two deployments — container names in
Compose, Service DNS in the cluster. Everything else, the dashboard included,
is read from this directory by both.

## Running it

```bash
docker compose --profile monitoring up
```

```bash
./infrastructure/kubernetes/deploy.sh --recovery --monitoring
```

Grafana is on **13000** and Prometheus on **19090** in both cases, opening on
the fleet dashboard with anonymous access. The ports are shifted from 3000 and
9090 for the same reason 8080 became 18080: Jenkins holds 8080 on this
machine, and one convention beats two.

`--monitoring` composes with the other flags. Without `--recovery` the
operator's Service does not exist, so Prometheus shows that target **DOWN**
and the operator panels say *No data* — not zero. `deploy.sh` prints a note
when the two flags are used apart. The gateway panels are unaffected.

Adding `--monitoring` to an **existing** cluster needs the cluster recreated —
`kind` cannot add `extraPortMappings` to a running node:

```bash
./infrastructure/kubernetes/deploy.sh --down && ./infrastructure/kubernetes/deploy.sh --recovery --monitoring
```

## What the dashboard shows

Four rows, twenty panels.

| Row | Answers |
|---|---|
| Fleet health | how many devices, in which state, and **which one** is down |
| Failure detection and recovery | failures, recoveries, MTTR distribution, operator outcomes |
| Telemetry | accepted/heartbeat rates, rejections by cause, Kafka drops, store errors |
| Gateway load | heap, GC time, threads — for the gateway *and* the operator |

Three things the panels are careful about, all of which have bitten before:

- **`fleet_devices_known` counts ghosts.** Retained presence outlives the
  device that set it (ADR-004), so a record can exist for a device the gateway
  has never heard from. `fleet_devices_reporting` is the real fleet size.
- **The two durations must never be added.** `fleet_recovery_duration_millis`
  (gateway) is detection to confirmed heartbeats — that is MTTR.
  `fleet_operator_detection_to_replacement_millis` ends when the API server
  accepts the pod, starts at the same instant, and is already contained in the
  gateway's number.
- **`ALREADY_RECOVERED` is not an error.** It is a redelivered failure event
  creating nothing, which is the observable proof that recovery is idempotent
  (ADR-011).

## Nothing here is a result

Grafana shows live operational values, over a six-hour retention window on an
`emptyDir`. A number becomes a result when a recorded run under
`experiments/results/` produced it, with machine specs, JVM version, heap
limit, device count, publishing interval and failure mode recorded alongside.
A screenshot of a stat panel is not that, and the dashboard says so on its own
text panel.

## Editing the dashboard

`grafana/dashboards/fleet.json` is the dashboard. It is provisioned with
`allowUiUpdates: false`, so an edit made in the browser is not kept — an
edit that survives only inside Grafana's SQLite is invisible to a diff and
absent from a clean checkout.

To change it, edit the JSON and redeploy. `deploy.sh` rebuilds the
`grafana-dashboards` ConfigMap from this file and restarts Grafana, which
reads it at startup.

## Adding a metric

There is no registry, by design (ADR-012), so a new metric is two edits:

1. Count it where it happens — `GatewayMetrics`, or the operator's own fields.
2. Render it in `MetricsExporter` or `OperatorMetricsExporter`.

`MetricsExporterTest` asserts that every declared `# TYPE` has at least one
sample under it, so a declaration without a series fails the build rather than
showing up as a permanently empty panel.

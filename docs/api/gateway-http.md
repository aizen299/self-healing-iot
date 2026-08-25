# Gateway HTTP API

Read-only, no authentication. Served by the JDK's own `HttpServer` rather
than a framework ([ADR-005](../decisions/ADR-005-gateway-dependencies-and-http.md)).
JSON everywhere except `/metrics`, which answers the Prometheus text
exposition format.

Bound to `127.0.0.1:8080` by default; the container images set
`GATEWAY_HTTP_HOST=0.0.0.0`, Compose publishes it on **18080**, and the kind
cluster maps NodePort 30080 to the same 18080 — Jenkins holds 8080 on the
development machine.

Any method other than `GET` returns **405**. An unknown path under
`/devices` returns **404** with the path echoed back.

## `GET /health` — liveness and fleet counters

Always **200** while the process is alive, whatever the broker is doing.

```json
{"status":"UP","brokerConnected":true,
 "devicesKnown":3,"devicesReporting":3,"devicesOnline":3,
 "health":{"UNKNOWN":0,"ONLINE":3,"SUSPECTED":0,"OFFLINE":0,"RECOVERING":0},
 "heartbeatsAccepted":39,"failuresDetected":0,"telemetryAccepted":39}
```

Three device counts, because they answer different questions:

| Field | Counts |
|---|---|
| `devicesKnown` | Everything the gateway has heard of, including retained-presence ghosts from earlier runs |
| `devicesReporting` | Devices that have actually sent something — the real fleet |
| `devicesOnline` | Devices the gateway currently judges healthy |

`meanRecoveryMillis` is `-1` when no recovery has been observed, rather than
`0` — which would read as instantaneous recovery.

## `GET /ready` — readiness

**200** when the gateway holds a broker connection, **503** when it does not.

```json
{"status":"READY","brokerConnected":true}
```

Separate from `/health` because they answer different questions and
Kubernetes asks both. `/health` can never fail while the process lives, so
it cannot serve as a readiness probe: a Service would keep routing to a
gateway that had lost its broker and was recording nothing. Conversely
`/ready` must not be a liveness probe, or a broker outage would restart
every gateway in a loop when the correct behaviour is to stay up, keep
serving history, and reconnect.

On Kubernetes that has a consequence worth knowing: readiness withdraws the
Service endpoint, and the gateway runs as a single replica, so during a
broker outage the normal route to it goes away too. A second Service
(`gateway-admin`, host port 18081) publishes the pod whether or not it is
ready, so `/health` and `/history` stay reachable exactly when they are most
wanted. See
[ADR-010](../decisions/ADR-010-kubernetes-deployment-shape.md).

## `GET /devices` — every known device

Ordered by id. `GET /devices/{id}` returns one, or **404**.

```json
{"deviceId":"device-002","health":"ONLINE","presence":"ONLINE",
 "healthChangedAtMillis":1787556933776,"offlineSinceMillis":0,
 "lastHeartbeatAtMillis":1787556960784,"lastTelemetryAtMillis":1787556960784,
 "telemetryAccepted":28,"telemetryRejected":0,"presenceOnly":false,
 "lastTelemetry":{"ts":1787556960778,"temp":27.31,"vib":3.01,"batt":98.5,
                  "lat":52.5213,"lon":13.4047,"status":"OK"}}
```

`health` is the gateway's own judgement and is what recovery acts on;
`presence` is what the broker last reported. They differ on purpose — a
device whose Last Will has fired is `OFFLINE` in both, but a device that
stays connected while its heartbeat path has wedged is `ONLINE` by presence
and `OFFLINE` by health ([ADR-006](../decisions/ADR-006-failure-detection.md)).

`presenceOnly: true` marks a device known only from retained presence — a
ghost. Those stay `UNKNOWN` and are never declared failed.

## `GET /history?device=<id>&from=<ms>&to=<ms>` — stored readings

## `GET /stats?from=<ms>&to=<ms>` — fleet aggregates

Both read the store rather than the registry, and both carry an
`integrity` block:

```json
{"integrity":{"complete":true,"droppedWrites":0,"dropEvents":0,
              "lastDropAtMillis":0,"summary":"complete"}}
```

| Field | Meaning |
|---|---|
| `complete` | Whether the store has lost anything since startup |
| `droppedWrites` | **Readings lost.** The number a result has to be checked against |
| `dropEvents` | How many separate failures caused them — one outage or many |
| `lastDropAtMillis` | When the most recent loss happened, `0` if none |
| `summary` | The same judgement in words, as the run summary prints it |

A window that is not complete cannot support a result. The gaps travel with
the data deliberately: a store failure is never fatal — losing detection
would be worse than losing history — so the only defence against quoting an
incomplete window is that every answer says so
([ADR-007](../decisions/ADR-007-telemetry-storage.md)).

`/history` caps results at 5000 rows: the whole result is materialised and
serialised in memory before a byte is written, and the gateway's footprint
is something the experiments measure.

## `GET /metrics` — Prometheus exposition

`Content-Type: text/plain; version=0.0.4; charset=utf-8`. Written by hand
rather than by a client library
([ADR-012](../decisions/ADR-012-observability-shape.md)): every number here
was already counted by `GatewayMetrics` or `DeviceRegistry`, and the exporter
reads them at scrape time without storing anything of its own.

**Scrape this through 18081, not 18080.** Readiness withdraws the pod from the
`gateway` Service during a broker outage, and with one replica that leaves no
endpoint at all — the metrics would go dark at exactly the moment they mattered.
`gateway-admin` sets `publishNotReadyAddresses: true`. For the same reason
`/metrics` reads nothing from the store, so it cannot answer 503 the way
`/history` and `/stats` can.

| Metric | Type | Notes |
|---|---|---|
| `fleet_devices_known` | gauge | **Includes retained-presence ghosts.** Not fleet size |
| `fleet_devices_reporting` | gauge | Devices the gateway has actually heard from |
| `fleet_devices{state}` | gauge | Every state is emitted, including the zeroes |
| `fleet_device_up{device_id}` | gauge | 1 when `ONLINE` — the "which one" panel |
| `fleet_telemetry_accepted_total` | counter | |
| `fleet_telemetry_rejected_total{reason}` | counter | `malformed` (would not parse) vs `invalid` (impossible values) |
| `fleet_heartbeats_accepted_total` | counter | Should track the telemetry rate (ADR-006) |
| `fleet_heartbeats_malformed_total` | counter | |
| `fleet_presence_events_total` | counter | |
| `fleet_presence_invalid_total` | counter | Our own device speaking an unrecognised protocol |
| `fleet_messages_unroutable_total` | counter | Someone else in the fleet topic space |
| `fleet_failures_detected_total` | counter | Pillar B |
| `fleet_recoveries_observed_total` | counter | Pillar B |
| `fleet_recovery_duration_millis` | histogram | **This is MTTR.** Detection to confirmed heartbeats |
| `fleet_gateway_broker_connected` | gauge | 1/0 |
| `fleet_gateway_connection_losses_total` | counter | |
| `fleet_gateway_handler_errors_total` | counter | Should stay zero |
| `fleet_gateway_monitor_errors_total` | counter | Non-zero means detection stalled |
| `fleet_gateway_event_publish_failures_total` | counter | |
| `fleet_gateway_store_errors_total` | counter | History is lossy when this moves |
| `fleet_gateway_kafka_forward_failures_total` | counter | Includes a full forwarder queue |
| `fleet_jvm_heap_used_bytes` | gauge | The **gateway's** JVM |
| `fleet_jvm_heap_max_bytes` | gauge | `-1` when undefined |
| `fleet_jvm_gc_collections_total{gc}` | counter | |
| `fleet_jvm_gc_time_millis_total{gc}` | counter | |
| `fleet_jvm_threads` | gauge | |

Histogram bounds are 500 ms, 1 s, 2 s, 5 s, 10 s, 30 s, 60 s, `+Inf`.
`fleet_recovery_duration_millis_count` counts only the recoveries that
produced a measurable duration, which is fewer than
`fleet_recoveries_observed_total` when the gateway's clock and the operator's
disagree — a `_count` larger than the `+Inf` bucket would be an inconsistent
histogram.

The JVM series carry the `fleet_` prefix on purpose. `jvm_memory_used_bytes`
would claim compatibility with the Micrometer schema a community dashboard
expects, and these are five numbers off the MXBeans. Pillar A's
constrained-versus-naive comparison is measured on the **device** JVM by the
experiment harness, not scraped here.

The recovery operator exposes its own `/metrics` on port 8080 with
`fleet_operator_*` series. Its
`fleet_operator_detection_to_replacement_millis` is a *component* of MTTR, not
MTTR — it ends when the API server accepts the pod, and the gateway's
histogram already contains it. **The two must never be added.**

Nothing served here is a result. Only a run recorded under
`experiments/results/` supports a reported number.

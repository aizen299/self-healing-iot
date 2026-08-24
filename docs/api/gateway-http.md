# Gateway HTTP API

Read-only, JSON, no authentication. Served by the JDK's own `HttpServer`
rather than a framework ([ADR-005](../decisions/ADR-005-gateway-dependencies-and-http.md)).

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

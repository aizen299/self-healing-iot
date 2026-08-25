# recovery-operator

Consumes `device.failures` and provisions replacement device pods on
Kubernetes, announcing what it did on `device.recovery`. The last arrow in
the loop the project is built around:

```
device → telemetry → monitoring → failure detected → recovery event
  → controller → replacement workload → healthy fleet restored
```

**Status:** Phase 9 complete. Failure consumption, idempotent replacement,
RBAC, and the recovery announcement are implemented and tested, and the
loop has been demonstrated end to end on kind.

Design decisions are in
[ADR-011](../docs/decisions/ADR-011-recovery-operator-shape.md).

## No operator framework, and no Kubernetes client

Neither the Java Operator SDK nor a client library. The SDK reconciles
**custom resources**; this controller's trigger is a Kafka topic, so using
it would mean inventing a CRD to have something to reconcile — an extra
resource and an extra hop inside the path whose latency Pillar B measures.

And the controller makes four API calls: list pods by label, read a pod,
create a pod, delete a pod. The JDK's `HttpClient` and the Jackson streaming
parser already in `common` cover that in about 300 lines, against a project
whose subject is resource-conscious engineering. Same call ADR-005 made for
the gateway's HTTP API.

`KubernetesApi` is an interface, so the boundary is cheap to cross. Take the
library the moment this needs watches, informers, leader election, or custom
resources — none of which a topic-triggered controller does.

## Idempotency

> The same failure event arriving twice must never create two replacements.

Kafka delivers at least once, so duplicates are routine rather than
exceptional. The guarantee cannot live in the operator's memory, because the
case that most needs it is the one where the operator just died.

So the recovery id is derived from the failure it answers — SHA-256 over
`deviceId@detectedAtMillis` — and the replacement's pod name from that. Two
deliveries compute the same name and **the API server refuses the second**.
The guarantee is a property of cluster state, which outlives the process.

| Situation | What happens |
|---|---|
| The same event redelivered | `ALREADY_RECOVERED`, nothing created |
| The same event after an operator restart | `ALREADY_RECOVERED`, from the cluster, not from memory |
| A *different* failure of the same device | A new recovery — a replacement that dies must be replaced |
| A device that came back on its own | `NOT_NEEDED`; a stale event must not kill a working device |
| A new failure while a replacement is still starting | `NOT_NEEDED`; otherwise every one adds another pod. Holds with `OPERATOR_REPLACE_LIVE_DEVICES=true` as well — that flag means "a Running pod does not block replacement", not "ignore a recovery in progress" |
| A pod whose node stopped reporting (`Unknown`) | Replaced. Treating it as present is how a dead node leaves the fleet permanently short |
| The cluster refuses | `FAILED`, counted, and the next event still handled |

## The replacement is cloned

Not templated. A spec written into this operator would be a second copy of
`base/40-devices.yaml` and the two would drift — the fleet would get a heap
flag that replacements silently did not.

It clones the failed pod's own manifest when that still exists (a crashed
pod with `restartPolicy: Never` does), and a living sibling when it does not
(a force-deleted pod leaves nothing to read). Only the name, the `device-id`
label, and `FLEET_DEVICE_INDEX_OFFSET` change. With nothing to clone it
reports and stops rather than inventing a spec.

## Two durations, neither of which is MTTR alone

| Number | Producer | Ends when |
|---|---|---|
| `detectionToReplacementMillis` | this operator | the API server accepts the replacement pod |
| `recoveryDurationMillis` | the gateway | the replacement's heartbeats are confirmed |

They start at the same instant and the gateway's contains this one, so
**they must never be added**. MTTR is the gateway's number. The operator's is
`null` whenever it would not be a measurement: an outcome that replaced
nothing, or a negative result, which means the two pods' clocks disagree.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Where failures arrive |
| `OPERATOR_GROUP_ID` | `fleet-recovery-operator` | Consumer group; also the mutual exclusion |
| `OPERATOR_NAMESPACE` | `fleet` | Namespace the device pods live in |
| `OPERATOR_DEVICE_APP_LABEL` | `edge-device` | Value of the `app` label device pods carry |
| `FLEET_DEVICE_ID_PREFIX` | `device` | **Must match the fleet's** |
| `OPERATOR_API_TIMEOUT_SECONDS` | `10` | Ceiling on any Kubernetes call |
| `OPERATOR_POLL_TIMEOUT_MS` | `1000` | How long a consumer poll waits |
| `OPERATOR_REPLACE_LIVE_DEVICES` | `false` | Replace a device whose pod is still running |
| `OPERATOR_RUN_DURATION_SECONDS` | `0` | `0` runs until interrupted |
| `OPERATOR_METRICS_PORT` | `8080` | Prometheus scrape port; `0` binds an ephemeral one. **Override it outside Kubernetes** — 8080 is Jenkins on the development machine, and this server binds `0.0.0.0` |

## Metrics

Phase 10 gave this process its first HTTP port, serving one route:
`GET /metrics`, in Prometheus text exposition format.

| Metric | Notes |
|---|---|
| `fleet_operator_recoveries_total{outcome}` | `REPLACED`, `ALREADY_RECOVERED`, `NOT_NEEDED`, `FAILED` |
| `fleet_operator_detection_to_replacement_millis` | Summary — `_sum` and `_count` |
| `fleet_operator_events_dropped_total{reason}` | `malformed`, `wrong_event_type` |
| `fleet_operator_commit_failures_total` | Each one means a redelivery, which is safe |
| `fleet_operator_publish_failures_total` | A recovery that happened but could not be announced |
| `fleet_jvm_heap_used_bytes`, `fleet_jvm_threads` | This process |

`ALREADY_RECOVERED` climbing is not a fault. It is a redelivered failure event
creating nothing, which is the observable proof that recovery is idempotent.

`fleet_operator_detection_to_replacement_millis` is **not MTTR** — see the
section above. No metric name here contains the string, and a test asserts it.

Still no probes. A scrape endpoint is not a health check: this one answers 200
whether or not Kafka is reachable, because an operator that cannot consume is
exactly what a dashboard needs to show, and a liveness probe reading it would
restart the recovery path during a broker outage.


## Running it

Needs Kafka, so `--recovery` implies `--kafka`:

```bash
./infrastructure/kubernetes/deploy.sh --recovery
```

Then kill a device and watch it come back on its own:

```bash
kubectl -n fleet delete pod edge-device-002 --grace-period=0 --force
```

```bash
kubectl -n fleet get pods -l app=edge-device -L device-id,recovery-id -w
```

`--grace-period=0 --force` matters: a graceful stop publishes a retained
`SHUTDOWN`, which the gateway correctly reads as a deliberate stop rather
than a failure (ADR-006), and no recovery follows.

## Design notes

- **One replica, no leader election.** `device.failures` has one partition,
  so exactly one group member is ever assigned it. Kafka already provides
  the exclusion a second replica would need.
- **Offsets commit after acting, never before.** Committing first turns a
  crash mid-recovery into a failure nobody ever handles.
- **`DEVICE_OFFLINE` only.** `device.failures` should carry nothing else, but
  this process deletes pods, and "the topic is supposed to" is not a safe
  basis for that.
- **Create before delete.** The other order is destructive: a create that
  fails once the stale pods are gone leaves nothing to clone, and with the
  whole fleet down there is no sibling either, so the device becomes
  unrecoverable by the operator's own hand.
- **Namespaced RBAC, three verbs** — `list`, `create`, `delete`. No `watch`,
  because the trigger is a topic; no `get`, because the list response already
  carries every pod's spec, which is also what removes a second API call per
  recovery.
- **A startup call to the API server**, so a missing `list` permission is a
  crash-loop visible in `kubectl get pods`. It verifies `list` and nothing
  else — `create` and `delete` are not exercised until a real failure, and
  the log says so rather than implying more.
- **A commit failure is not fatal.** Letting it escape the poll loop kills
  the process; what an uncommitted offset actually causes is redelivery,
  which is safe here by construction.

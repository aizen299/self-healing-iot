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
| A new failure while a replacement is still starting | `NOT_NEEDED`; otherwise every one adds another pod |
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
`null` for any outcome that replaced nothing — for those the subtraction is
arithmetic, not a measurement.

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
- **Namespaced RBAC, four verbs.** No `watch`: the trigger is a topic, so the
  operator never observes the cluster continuously.
- **A startup call to the API server**, so a missing RBAC rule is a
  crash-loop visible in `kubectl get pods` rather than a recovery that
  silently never happens.

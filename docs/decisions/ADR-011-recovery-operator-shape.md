# ADR-011: A Kafka-triggered controller, and where idempotency actually lives

## Status
Accepted — 2026-08-24

## Context
Phase 9 builds the recovery operator: the component that closes the loop the
whole project exists to demonstrate.

```
device → telemetry → monitoring → failure detected → recovery event
  → controller → replacement workload → healthy fleet restored
```

Three decisions had to be made, and the first two go against what the
project's own notes said to prefer.

## Decision

### Not the Java Operator SDK, and no Kubernetes client library

The repository layout says "Java Operator SDK preferred, plain K8s Java
client fallback". The SDK is the wrong shape here, and the reason is
structural rather than a matter of taste.

JOSDK is built to reconcile **custom resources**: you define a CRD, and the
SDK watches it and calls a reconciler when it changes. This controller's
trigger is a **Kafka topic** — the architecture has always had
`Kafka → Recovery Controller → Kubernetes`, and ADR-009 created
`device.failures` as a strict subset of `device.events` precisely so this
controller could subscribe to it and see nothing else. Using JOSDK would
mean inventing a CRD for something to reconcile, and something to write that
CRD from the Kafka topic: a whole extra resource and an extra hop, existing
to satisfy a tool choice rather than a requirement, and sitting inside the
path whose latency is Pillar B's measurement.

Having settled on a plain client, the question was which. The answer was
none. This operator makes **four** API calls — list pods by label, read one
pod, create a pod, delete a pod — against a documented REST API, from inside
the cluster, where the credentials are three files the kubelet has already
mounted. Fabric8 or the official client would each bring a dependency tree
larger than the rest of this project's runtime dependencies combined, into a
project whose subject is resource-conscious engineering. ADR-005 made the
same call for the gateway's HTTP API and the same reasoning applies:

> Three read-only endpoints do not justify pulling a framework and its
> dependency tree into a project whose subject is resource-conscious
> engineering.

So: JDK `HttpClient`, the Jackson streaming parser already in `common`, and
about 300 lines. No new dependency.

**The boundary is explicit.** The moment this needs watches, informers,
leader election, custom resources, or field-manager semantics, that is the
point to take the library — those are the parts where a client encodes
knowledge worth having, and none of them are needed by a controller whose
trigger is a topic. `KubernetesApi` is an interface for exactly that reason:
swapping the implementation is a day's work, and the tests drive a fake.

Two details worth recording because they are easy to get wrong by hand:

- The service account token is **re-read on every request**. Kubernetes
  projects bound tokens with an expiry and rotates the file in place; one
  read at startup stops working about an hour in, and the failure looks like
  the operator mysteriously losing permissions long after a successful
  deploy.
- The trust store contains the cluster CA and nothing else. An all-trusting
  trust manager is the usual shortcut and is wrong here: the operator holds a
  credential that can delete pods.

### Idempotency lives in the API server, not in the operator

The requirement is that "the same failure event arriving twice must never
create two replacements", with recovery state tracked explicitly. Kafka
delivers at least once, so duplicates are not an edge case — a consumer
rebalance or an operator restart before the offset commit produces one every
time.

An in-memory ledger cannot be the guarantee, because the case that most
needs it is the one where the operator has just died and forgotten
everything.

So the recovery id is **derived from the failure it answers** — SHA-256 over
`deviceId@detectedAtMillis` — and the replacement's pod name is derived from
that. Two deliveries of one event compute the same name, and the API server
refuses to create a pod that already exists. The guarantee is therefore a
property of the cluster's state, which survives the operator dying, rather
than of the operator's memory, which does not.

The ledger still exists, because the design calls for explicit state and
because the operator has to be able to say what it did. It is an
optimisation and a report, not the mechanism.

Consequences of deriving the id this way:

- A **random UUID or a timestamp would break it**: either would make the
  second delivery of one event look like a second failure.
- Two *genuinely distinct* failures of one device produce different ids and
  therefore two recoveries, which is right — a replacement that dies in turn
  must be replaced again, and deduplicating on device id alone would leave
  the fleet permanently one device short.

### Three things a correct-looking operator still gets wrong

Found by running it, and each has a test:

**A device that came back on its own must not be replaced.** A failure event
says what the gateway saw when it fired, not what is true now. Killing a
device that has since recovered turns a stale event into a real outage.

**A replacement that is still starting must not trigger another.** This one
is not covered by the deterministic-name guard, and the window is not small:
a replacement waits for the broker, then boots a JVM, and until it heartbeats
the gateway keeps declaring the device offline. Those are *genuinely
distinct* failure events with distinct recovery ids, so without a liveness
check every one would add another pod to a device that already has one on
the way up.

**Offsets are committed after acting, never before.** Committing first turns
an operator crash mid-recovery into a failure nobody ever handles: the event
is marked consumed and the device stays dead.

### The replacement is cloned, not templated

A pod spec written into the operator — or into a ConfigMap beside it — would
be a second copy of `base/40-devices.yaml`, and the two would drift: the
fleet would get a heap flag or a resource limit that replacements silently
did not. The operator clones the failed pod's own manifest if it still
exists (a crashed pod with `restartPolicy: Never` does), and any living
sibling if it does not (a force-deleted pod leaves nothing to read), changing
only the name, the `device-id` label, and `FLEET_DEVICE_INDEX_OFFSET`.

With nothing to clone it reports and stops rather than inventing a spec. A
fleet with no running device is a situation to be told about, not one to
paper over with a guess that silently differs from the real thing.

### One replica, and no leader election

`device.failures` has one partition, so exactly one member of the consumer
group is ever assigned it. Kafka is already providing the mutual exclusion a
second replica would need, and adding leader election would be a second
mechanism for a problem already solved.

## Consequences

- **The first RBAC in this project.** Namespaced, and limited to
  `get, list, create, delete` on pods — no `watch`, since the trigger is a
  topic. Phase 8 deferred RBAC for the honest reason that nothing then
  deployed talked to the API server; this is the workload that needed it.
- **`device.recovery` now has two producers with unrelated schemas.** The
  gateway publishes a `DEVICE_RECOVERED` health transition when a replacement
  is confirmed heartbeating; the operator publishes what it did about a
  failure. Records carry `kind: recovery-action` so a consumer can switch on
  something rather than sniff for fields.
- **Two durations exist and neither is MTTR on its own.** The operator's
  `detectionToReplacementMillis` ends when the API server accepts the pod;
  the gateway's `recoveryDurationMillis` ends when the replacement's
  heartbeats are confirmed. They start at the same instant and the gateway's
  contains the operator's, so **they must never be added**. MTTR is the
  gateway's number. The operator's is null for any outcome that replaced
  nothing, because for those the subtraction is arithmetic rather than a
  measurement.
- The health-transition codec moved to `common`. The format now has a second
  reader, which is the same reason the telemetry parser moved there in
  Phase 6.
- Killing a device is no longer a permanent loss, which changes what Phase 8's
  bare Pods mean in practice: they still stay dead as far as Kubernetes is
  concerned, and the operator is the only thing that brings them back. That
  is what makes the recovery path measurable.

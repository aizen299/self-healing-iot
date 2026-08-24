# ADR-010: Kubernetes deployment shape, and why the kubelet must not recover devices

## Status
Accepted — 2026-08-24

## Context
Phase 8 puts the stack on Kubernetes. The pipeline it deploys already works
in containers (Phase 7), so the interesting questions are not "how do I
write a Deployment". They are three specific ones this project's research
goals answer differently from a normal service deployment:

1. Which local Kubernetes, on a host with 8 GB and a Docker VM already
   holding roughly half of it.
2. What shape a *device* is, given that Compose runs the whole fleet in one
   JVM (ADR-003) and recovery needs devices to fail one at a time.
3. What restarts a failed device — because Phase 9's entire measurement is
   how long recovery takes, and Kubernetes will happily do it in a second
   if asked.

## Decision

### kind, not Minikube

kind runs its node as a container inside the Docker VM that is already
running. Minikube starts a second VM, and on this host the second VM comes
out of the same 8 GB that Pillar A's memory measurements are taken in.

One node, not three. A control plane plus two workers is more realistic and
costs several hundred MB for nothing this phase demonstrates: every
workload here is a singleton, and Phase 9 replaces a pod, not a node.

Consequence: images are built on the host and side-loaded with
`kind load docker-image`. There is no registry, so every manifest states
`imagePullPolicy: IfNotPresent`, and a code change needs an explicit
`rollout restart` — the pod spec does not change when an image is rebuilt
under the same tag, so nothing would otherwise happen.

### Two fleet shapes, for two different measurements

The shared-JVM harness is unchanged and is still what Pillar A measures.
Fifty JVMs do not fit on this host, and a per-pod JVM's own baseline
overhead would swamp the allocation difference between the constrained and
naive variants — the comparison would measure the JVM, not the code.

Kubernetes adds a second shape: **one device, one Pod**, at three devices.
Pillar B needs a device to be something that can die and be replaced
individually, and ADR-003 already scoped per-process runs to 3–10 devices
for exactly this.

These are the same simulation, not two that resemble each other.
`FLEET_DEVICE_INDEX_OFFSET` slices the fleet by index, and both the device
id and the sensor seed derive from the index, so `device-002` in its own
pod produces the readings `device-002` produces inside the harness. Without
the offset every pod would run `FLEET_DEVICE_COUNT=1` and therefore publish
as `device-001` — one device id, three contradicting publishers, and a
gateway with no way to tell.

### Devices are bare Pods with `restartPolicy: Never`

This is the decision that matters, and it is deliberately not what a
production deployment would do.

A Deployment restarts a crashed container in about a second, and its
ReplicaSet recreates a deleted pod just as fast. Either would mean:

- Phase 9's operator never gets to act on a failure, because the failure is
  already repaired by the time the event reaches it.
- The MTTR the research reports would be a measurement of the kubelet's
  restart backoff, not of `detect → event → decide → replace`.
- Recovery idempotency — the property Phase 9 has to demonstrate — could
  never be exercised, because two recoveries would never race.

So a device that dies stays dead until something decides to replace it:

```
device stops → gateway detects (Last Will, or heartbeat timeout)
  → failure event → recovery controller → replacement pod → healthy fleet
```

That is Pillar B's measurement path, and every arrow in it has to be one
this system owns.

**This is a research instrument, not a reliability pattern.** A system
whose goal was uptime should absolutely let Kubernetes restart the pod. The
whole point here is to measure an application-level recovery loop, which
means nothing underneath it may quietly perform the recovery first.

For the same reason device pods carry **no liveness probe**. The gateway
decides whether a device is alive, from heartbeats and the broker's Last
Will (ADR-006). A kubelet probe would be a second failure detector racing
the one being measured.

### Rollouts are `Recreate`, not `RollingUpdate`

Three singletons, three independent reasons, each sufficient on its own:

| Workload | Why an overlap breaks it |
|---|---|
| gateway | H2 takes an exclusive file lock on the store, so the incoming pod comes up with history silently disabled — the "gaps travel with the data" failure ADR-007 was amended to make visible. Separately, two MQTT clients cannot share `GATEWAY_CLIENT_ID`; the broker drops the older one, and for the overlap the fleet is monitored by a pod that is about to be deleted |
| broker | Devices and the gateway can land on different brokers, and a Last Will registered with the old one fires to nobody — the fast detection path silently stops working for the length of the rollout |
| stream-processor | One broker with one partition per topic, so a second instance is assigned nothing and idles while the group rebalances twice. (Not, as an earlier draft of the manifest claimed, a RocksDB lock — `state.dir` is never configured, so the pods share no state directory and there is nothing to lock) |

### Readiness is a real question, so the gateway had to be able to answer it

`/health` returns 200 whenever the process is alive, whatever the broker is
doing. As a readiness probe it could never fail, so the Service would keep
routing to a gateway that had lost its broker and was recording nothing.

Phase 8 therefore adds `GET /ready`: 200 when the MQTT connection is up,
503 when it is not, and a `brokerConnected` field on `/health` so the
distinction is visible to a human too.

The probes are split accordingly, and the split is the point:

- **readiness → `/ready`** — should traffic come here?
- **liveness → `/health`** — is this process wedged?

Probing `/ready` for liveness would restart every gateway in a loop during
a broker outage, when the correct behaviour is to stay up, keep serving
history, and reconnect.

Readiness has a cost that only shows up at one replica, and it was found by
running it: withdrawing the endpoint withdraws the *only* endpoint, so
during a broker outage the gateway is unreachable from outside the cluster —
at exactly the moment an operator wants `/health` and `/history`. The
process was fine throughout (200 on `/health`, zero restarts) and reachable
only through `kubectl exec`, which makes "keep serving history" true of the
process and false of the deployment.

A second Service, `gateway-admin`, with `publishNotReadyAddresses: true`
fixes that: one route that respects readiness for traffic, one that ignores
it for humans. It must never be the one application traffic uses.

### Kafka is applied separately

Kafka measured 392 MB against Mosquitto's 3 MB, and the Docker VM has under
4 GB to divide between kind's control plane and everything else. Nothing on
the detection path touches Kafka (ADR-009), so `base/` is a complete,
working system without it and `kafka/` is applied only when a phase needs
streaming.

## Consequences

- Killing a device pod is a genuine, unrecovered failure until Phase 9
  exists. `kubectl get pods` will show devices missing, and that is correct.
- Anyone reading these manifests as an example of how to deploy a service
  will be misled by the bare Pods. The comment in `40-devices.yaml` and this
  ADR are the mitigation.
- The gateway gained an endpoint. Compose's healthcheck moved to `/ready`
  too, so both deployments ask the same question.
- **Kafka must be ready before the gateway starts, and the deploy enforces
  the order.** The gateway builds its `KafkaProducer` once, at startup; a
  bootstrap address that does not resolve yet makes the constructor fail, and
  the gateway then forwards nothing for the life of the process. That is safe
  by ADR-009 and it is silent — observed as a stack where every pod was
  healthy, `telemetry.raw` did not exist, and one stderr line said why.
  `deploy.sh` now waits for the Kafka StatefulSet before rolling the gateway,
  and the log line carries the underlying cause rather than only
  "Failed to construct kafka producer". The single-attempt behaviour itself is
  a Phase 6 property of the forwarder and is left as it is; a Kafka restart
  under a running gateway still ends forwarding until the gateway restarts.

  > **No longer true, since the fix that followed Phase 9.** Leaving deploy
  > ordering to compensate for a process that could not reconnect was the
  > wrong altitude: the gateway now builds its producer on the forwarder's
  > sender thread and retries every 10 s until it succeeds, so a gateway that
  > starts before Kafka — or outlives a Kafka restart — resumes forwarding on
  > its own. `deploy.sh` still waits for Kafka, for the reasons in the next
  > bullet (the topics have to exist before Kafka Streams starts) rather than
  > for this one. See `common/src/main/java/io/fleet/common/LazyResource.java`.
- **Kafka's topics are created, not auto-created.** Kafka Streams refuses to
  start against a missing source topic, and
  `KAFKA_AUTO_CREATE_TOPICS_ENABLE` only creates a topic when a producer first
  writes to it — so whether the stream processor or the gateway's first
  reading wins is a race, and losing it kills the stream thread inside a
  container that stays Running. An init container creates all five topics
  with `--if-not-exists` before the topology starts.
- Turning Kafka on is applying `kafka/`, and turning it off is deleting it.
  The flag and the bootstrap address live together in a ConfigMap the
  gateway reads with an optional `configMapRef`, so nothing in `base/`
  contradicts it and nothing has to be patched after an apply. An earlier
  version set the flag imperatively and never set the address at all, which
  enabled forwarding to `localhost:9092`.
- Two deployment descriptions now exist — `docker-compose.yml` and these
  manifests — and can drift. They are kept honest by using the same images,
  the same environment variable names, and the same digests; the tick
  interval, which is the one value that must not drift, is a single
  ConfigMap key read by both sides rather than a template substitution.
- Authentication is still not configured on the broker, and the comment in
  `mosquitto.conf` promising it "belongs with the Kubernetes phase" is now
  overdue. It is deferred deliberately rather than forgotten: it needs
  credential support in both MQTT clients and a generated password file, and
  Phase 8's objective is the deployment. Tracked as the first item in
  `infrastructure/kubernetes/README.md`.

# infrastructure/kubernetes

The fleet on Kubernetes: a local [kind](https://kind.sigs.k8s.io) cluster
running the broker, the gateway, and three devices — one device per pod.

**Status:** Phase 9 complete. Broker, gateway, and a three-device fleet run
on kind with ConfigMaps, probes, resource limits, and RBAC. Kafka and the
stream processor are an optional overlay; the recovery operator is another,
and with it a killed device comes back on its own.

Design decisions are in
[ADR-010](../../docs/decisions/ADR-010-kubernetes-deployment-shape.md).

## Running it

```bash
./infrastructure/kubernetes/deploy.sh
```

That builds the images, side-loads them into the cluster (there is no
registry), applies `base/`, and waits for the pipeline. Then:

```bash
curl -s http://127.0.0.1:18080/health
```

```bash
kubectl -n fleet get pods
```

18080, not 8080 — Jenkins holds 8080 on this machine. That collision has
one more victim here: `kubectl` with no context configured falls back to
`http://localhost:8080`, so a missing context produces a Jenkins login page
wrapped in a Kubernetes error rather than "no cluster".

The broker is on **11883**, deliberately not 1883:

```bash
mosquitto_sub -h 127.0.0.1 -p 11883 -t 'fleet/+/telemetry' -v
```

On 1883 the cluster's broker impersonates the local development broker that
every earlier phase points at, and `mvn test` stops skipping the MQTT
integration tests and silently runs them against Kubernetes — leaving their
retained presence behind as ghost devices in a fleet the tests know nothing
about. It was found that way. A test suite whose behaviour depends on
whether a cluster happens to be running is not reproducible, which is the
one property this project cannot trade away.

Three flags, composable, each pulling in what it needs:

```bash
./infrastructure/kubernetes/deploy.sh --kafka        # + Kafka and the stream processor
./infrastructure/kubernetes/deploy.sh --recovery     # + the operator (implies --kafka)
./infrastructure/kubernetes/deploy.sh --monitoring   # + Prometheus and Grafana
```

Everything is published on a shifted port, because Jenkins started it:

| Host port | NodePort | What |
|---|---|---|
| 18080 | 30080 | Gateway, through the readiness-gated Service |
| 18081 | 30081 | Gateway, through `gateway-admin` — reachable when not ready |
| 11883 | 30883 | MQTT broker |
| 13000 | 30300 | Grafana (`--monitoring`) |
| 19090 | 30090 | Prometheus (`--monitoring`) |

Tear the whole thing down:

```bash
./infrastructure/kubernetes/deploy.sh --down
```

## What is deployed

| Object | Kind | Why that kind |
|---|---|---|
| `broker` | Deployment + NodePort Service | `Recreate`: two brokers behind one Service means a Last Will registered with the old one fires to nobody |
| `gateway` | Deployment + NodePort Service + PVC | `Recreate`: H2 holds an exclusive lock on the store, and two clients cannot share one MQTT client id |
| `edge-device-00N` | **bare Pods**, `restartPolicy: Never` | So that nothing but the Phase 9 operator can recover a device — see below |
| `gateway-admin` | NodePort Service, `publishNotReadyAddresses` | Keeps `/health` reachable while readiness has the gateway out of rotation |
| `fleet-config` | ConfigMap | The tick interval and broker URL both halves must agree on |
| `fleet-device-config` | ConfigMap | Everything identical across the device pods, so only the offset differs |
| `recovery-operator` | Deployment + ServiceAccount + Role (optional) | The only workload that talks to the API server, and the only one that mounts a token |
| `kafka` | StatefulSet (optional) | Kafka advertises a hostname; a Deployment's pod name changes on restart |

## The bare Pods are the point

A Deployment would restart a crashed device in about a second, and a
ReplicaSet would recreate a deleted one just as fast. Either would mean the
recovery operator never gets to act, and the MTTR this project reports
would be a measurement of the kubelet's restart loop instead of the
detection-and-recovery loop that is the research subject.

So devices are bare Pods that nothing in Kubernetes brings back. Kill one:

```bash
kubectl -n fleet delete pod edge-device-002 --grace-period=0 --force
```

`--grace-period=0 --force` matters: a graceful stop publishes a retained
`SHUTDOWN` and disconnects cleanly, which the gateway correctly reads as a
deliberate stop rather than a failure (ADR-006). Forcing it severs the
connection, so the broker fires the device's Last Will — a real failure.

Then watch the gateway notice:

```bash
curl -s http://127.0.0.1:18080/devices/device-002
```

Without the recovery overlay, nothing brings that pod back — and that is
correct. With it, the operator does, in a couple of seconds:

```bash
./infrastructure/kubernetes/deploy.sh --recovery
```

```bash
kubectl -n fleet get pods -l app=edge-device -L device-id,recovery-id -w
```

The replacement is named for the recovery that produced it
(`edge-device-device-002-r-703117455b`), which is also what makes recovery
idempotent — see
[ADR-011](../../docs/decisions/ADR-011-recovery-operator-shape.md).

**Read the bare Pods as a research instrument, not as a deployment
pattern.** A system whose goal was uptime should let Kubernetes restart the
pod; this one measures an application-level recovery loop, so nothing
underneath it may quietly do the recovering first.

## Kafka

Left out of the base stack. It measured 392 MB against Mosquitto's 3 MB,
and the Docker VM on this host has under 4 GB to divide between kind's
control plane and everything else. Nothing on the detection path touches it
(ADR-009), so `base/` is complete without it.

```bash
./infrastructure/kubernetes/deploy.sh --kafka
```

Order matters, and `deploy.sh` enforces it: the overlay creates the five
topics up front rather than relying on auto-creation, because Kafka Streams
will not start against a source topic that does not exist yet, and creating
them needs a live broker. The gateway is no longer part of that argument — it
builds its producer on its sender thread and retries every 10 s, so one that
starts before Kafka, or outlives a Kafka restart, picks up forwarding by
itself. It used to forward nothing for the life of the process.

Applying the overlay *is* the switch — the `fleet-kafka` ConfigMap carries
both the flag and the bootstrap address, and the gateway reads it with an
optional `configMapRef`. Turning it off means removing it, not editing a
flag:

```bash
kubectl delete -f infrastructure/kubernetes/kafka/ && kubectl -n fleet rollout restart deployment/gateway
```

## Monitoring

```bash
./infrastructure/kubernetes/deploy.sh --recovery --monitoring
```

Grafana on [13000](http://127.0.0.1:13000), anonymous, opening on the fleet
dashboard. Prometheus on [19090](http://127.0.0.1:19090) for when a panel says
"No data" and the question is whether the target is even up.

A separate overlay for the same reason Kafka is one: `prom/prometheus` is
402 MB and `grafana/grafana` is 707 MB, so between them they are heavier than
Kafka, and the base stack is a working self-healing fleet without a dashboard.

**Adding `--monitoring` to a cluster that already exists needs the cluster
recreated.** `kind` cannot add `extraPortMappings` to a running node, so
13000 and 19090 do not reach anything until the node is rebuilt:

```bash
./infrastructure/kubernetes/deploy.sh --down && ./infrastructure/kubernetes/deploy.sh --recovery --monitoring
```

Prometheus scrapes the gateway through **`gateway-admin`**, not `gateway`.
Readiness withdraws the pod from the latter during a broker outage and there
is only one replica, so scraping through it would take the dashboard down at
the exact moment someone went looking — the graph would show a gap where it
should show `fleet_gateway_broker_connected 0`
([ADR-012](../../docs/decisions/ADR-012-observability-shape.md)).

The dashboard is `infrastructure/monitoring/grafana/dashboards/fleet.json` and
that file is the only copy — `deploy.sh` builds the ConfigMap from it.
`91-grafana.yaml` deliberately does not carry a transcription. See
[infrastructure/monitoring](../monitoring/README.md).

Nothing on the dashboard is a result. Only a run recorded under
`experiments/results/` supports a reported number.

## Probes

The gateway answers two different questions, and Phase 8 is what forced the
distinction:

| Probe | Endpoint | Question |
|---|---|---|
| readiness | `/ready` | Should traffic come here? 503 until the broker connection is up |
| liveness | `/health` | Is this process wedged? 200 whatever the broker is doing |

At one replica, readiness withdrawing the endpoint withdraws the only one,
so during a broker outage the gateway drops off 18080 entirely. The
`gateway-admin` Service publishes the pod regardless of readiness for that
reason:

```bash
curl -s http://127.0.0.1:18081/health
```

`/health` alone could not serve as a readiness probe — it can never fail
while the process lives, so a Service would keep routing to a gateway that
was recording nothing. And `/ready` must not be the liveness probe, or a
broker outage would restart every gateway in a loop when the right
behaviour is to stay up and reconnect.

Device pods carry **no liveness probe** at all. The gateway decides whether
a device is alive; a kubelet probe would be a second failure detector
racing the one being measured.

## Measured footprint

The whole cluster — kind's control plane plus the fleet — is about
**1.0 GB** of the Docker VM's 3.8 GB:

| Pod | Used | Limit |
|---|---|---|
| gateway | 133 MB | 512 Mi |
| edge-device-001 | 65 MB | 192 Mi |
| edge-device-003 | 63 MB | 192 Mi |
| broker | 3 MB | 128 Mi |
| *kind control plane* | *~740 MB* | — |

The control plane costs more than everything it runs, which is why this is
one node and why Kafka is a separate overlay.

The per-device figure is the interesting one. A device in its own pod costs
about 64 MB before it has allocated anything of its own, against 95 MB for
*ten* devices sharing one JVM under Compose. That gap is the reason ADR-003
keeps the shared harness for Pillar A: a per-pod JVM's baseline would
swamp the allocation difference between the constrained and naive variants,
so the comparison would be measuring the JVM.

These figures are a demonstration, not a result. Only runs recorded under
`experiments/results/` with their full configuration count as those.

## Not here yet

- **Broker authentication.** `mosquitto.conf` has said since Phase 7 that
  auth "belongs with the Kubernetes phase, where there is somewhere to keep
  a secret", and that is still true and still not done. It needs
  username/password support in both MQTT clients plus a generated password
  file in a Secret; Phase 8's objective was the deployment. The broker
  accepts anonymous connections and is reachable only inside the cluster and
  on localhost.
- **Ingress, HPA, NetworkPolicy, multi-node.** None are needed by anything
  this project measures.
- **Persistent monitoring storage.** Prometheus and Grafana both write to an
  `emptyDir`, and Prometheus retains six hours. Deliberate: a dashboard is not
  evidence, and a run that matters is recorded under `experiments/results/`.
- **Alerting.** Prometheus has no rules and there is no Alertmanager. There is
  nobody to page; the recovery operator is the thing that acts on a failure.

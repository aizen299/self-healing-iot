# infrastructure/docker

Dockerfiles per module and the root `docker-compose.yml` for running the
local stack: MQTT broker, gateway, and a fleet of simulated devices.

**Status:** broker, gateway, fleet, Kafka, and the stream processor. A
time-series database, Prometheus, and Grafana join as their phases land.
The same images run on Kubernetes from Phase 8 —
[`infrastructure/kubernetes/`](../kubernetes/).

Kafka is the heaviest service by a wide margin. `docker compose up broker
gateway` is the light path and is enough for anything that does not need
streaming.

Taken **before** Phase 6 — see
[ADR-008](../../docs/decisions/ADR-008-containerisation-before-kafka.md).
Both Kafka and a real TSDB need a server, and the phase that supplies
servers came after both; solving that once with containers beats installing
four services on an 8 GB laptop.

## Running

```bash
docker compose up --build
```

```bash
curl -s http://127.0.0.1:18080/health
curl -s http://127.0.0.1:18080/stats
```

Published on **18080**, not 8080. Port 8080 is the most contended port on a
developer machine — it was already taken here, and Docker's `0.0.0.0` bind
lost to that process's `127.0.0.1` bind, so the stack looked healthy while
the host reached an unrelated server. The container still listens on 8080.

Bring up only what you need; Docker Desktop's VM already claims about half
of an 8 GB host:

```bash
docker compose up broker gateway
```

Verify everything builds and works, without leaving a stack running:

```bash
./infrastructure/docker/smoke-test.sh
```

## Why the images look like this

| Choice | Reason |
|---|---|
| Base images pinned by **digest** | A floating tag moves with every release, so two Phase 8 runs weeks apart would use different JVMs while both claimed to be pinned. Note the container's JVM is a *different build* from the host's pinned one — see the ADR-008 amendment; a Pillar A comparison must be entirely inside containers or entirely outside |
| Temurin build stage | The enforcer's JVM rules, GraalVM rejection included, pass inside the image as they do on the host |
| Dependencies beside the jar | A code change rebuilds one small layer rather than a fat jar, and the image records what the module actually depends on |
| Healthcheck on `/ready`, not `/health` | `/health` answers 200 whenever the process is alive, so as a healthcheck it could never fail. `/ready` is 503 until the broker connection is up, which is what a healthcheck is actually asking |
| `mem_limit` on every service | Java 21 sizes its heap from the cgroup; verified at 128 MB inside a 512 MB container rather than a share of 8 GB. An unbounded container would push this host into swap and corrupt Phase 8's memory numbers |
| Non-root user | Nothing here needs root, and the gateway is reachable from the network |
| One container for the whole fleet | ADR-003 scopes the simulation to a shared-JVM harness because 50 JVMs do not fit in this host's memory. Per-device containers arrive with Kubernetes, at a smaller device count, for the recovery demo |

## Measured footprint

The full stack including Kafka, about **800 MB** against limits totalling
2176 MB. Without Kafka and the stream processor it is about 240 MB:

| Service | Used | Limit |
|---|---|---|
| kafka | 392 MB | 1024 MB |
| stream-processor | 169 MB | 512 MB |
| gateway | 139 MB | 512 MB |
| fleet (10 devices) | 95 MB | 256 MB |
| broker | 3 MB | 128 MB |

Images are roughly 480 MB each, dominated by the JRE base. `jlink` or an
Alpine base would cut that considerably and is worth doing before anything
is pushed to a registry.

These figures are a demonstration, not a result — only runs recorded under
`experiments/results/` count as those.

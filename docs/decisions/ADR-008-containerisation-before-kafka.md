# ADR-008: Containerisation before Kafka, and how the images are built

## Status
Accepted — 2026-08-23

## Context
The build order in `CLAUDE.md` puts Kafka streaming at phase 6 and
containerisation at phase 7. Working through phase 5 and starting phase 6
made a pattern visible: both need a **server**, and the phase that supplies
servers comes after both.

Phase 5 wanted a time-series database and settled for an embedded store
(ADR-007) because Docker had not arrived. Phase 6 wants a Kafka broker, and
Kafka has no honest embedded equivalent — the options were installing it
natively on the development machine or deferring it.

The host has 8 GB (ADR-003). A native Kafka is roughly a gigabyte resident
and stays installed after the phase that needed it; Docker's VM already
claims about half the host on its own. Running one more permanent JVM
service on that machine is not free, and Phase 8 measures gateway memory on
it.

## Decision

**Take phase 7 before phase 6.** Containerise first, then run Kafka as a
container.

This is a deviation from a rule the project takes seriously, so the
reasoning matters. The rule exists to stop a phase being built on an
unstable foundation — "never build phase *N* before phase *N-1* has a
working, tested demonstration". The foundation here is the MQTT → gateway
pipeline with failure detection and persistence, and it is demonstrated and
tested. Phases 6 and 7 are adjacent and independent of each other: Kafka
does not need containers to be correct, and containers do not need Kafka.
Swapping two independent adjacent phases is not the reach-ahead the rule
guards against.

What the swap buys is that the "server before Docker" problem stops
recurring. Kafka, a real time-series database, Prometheus, and Grafana are
all servers, and all of them arrive in later phases. Solving it once with
containers is cheaper than solving it four times with native installs — and
it means a service can be stopped and removed rather than living
permanently on a laptop with 8 GB.

### The runtime image is the pinned JDK

The runtime is Temurin's HotSpot 21, the same family ADR-002 pins the host
to, and every base image is pinned **by digest** rather than by tag.

The digest matters more than it first appears — see the amendment below.
A floating `21-jre` tag moves with every Temurin release, so two Phase 8
runs weeks apart would use different JVM builds while both claimed to be
pinned, which is precisely the drift ADR-002 exists to prevent.

The build stage uses `maven:3.9-eclipse-temurin-21`, so the enforcer's JVM
rules — including the GraalVM rejection — pass inside the image exactly as
they do on the host.

### Memory limits on every service

Each service declares `mem_limit`, and Java 21's container support is left
on, so the JVM sizes its heap from the cgroup rather than from the host's
8 GB. Verified: the gateway's JVM reports a 128 MB default heap inside a
512 MB container rather than a share of 8 GB.

The limits are not decoration. An unbounded container on this host would
push it into swap, and Phase 8's memory numbers would become a property of
whatever else happened to be running.

### Dependencies beside the jar, not shaded into it

Each image copies `target/dependency/` as its own layer and the module jar
as another. A code change rebuilds one small layer instead of a fat jar, and
the image keeps an honest record of what the module actually depends on.

### One container for the whole fleet

Not one per device. ADR-003 scopes the simulation to a shared-JVM harness
precisely because 50 JVMs do not fit in this host's memory, and that
reasoning does not change when the JVM is in a container. Per-device
containers arrive with Kubernetes, at a much smaller device count, for the
recovery demonstration.

### Published on 18080, not 8080

Port 8080 was already held by an unrelated Jetty process on the development
machine. Docker's `0.0.0.0` bind lost to that process's `127.0.0.1` bind, so
the stack reported itself healthy while anything querying the host reached
somebody else's server and got a 403. The container keeps 8080; only the
published port moves.

## Consequences
- Positive: every remaining server-shaped dependency — Kafka, a real TSDB,
  Prometheus, Grafana — now has somewhere to run without being installed on
  the host.
- Positive: base images are pinned by digest, so a rebuilt image is the same
  image.
- Positive: the whole stack ran in about 204 MB against limits totalling
  896 MB, leaving headroom on an 8 GB host. A demonstration, not a result —
  only a run recorded under `experiments/results/` is that.
- Negative: the images are around 480 MB each, dominated by the JRE base.
  `jlink` or an Alpine base would cut that substantially and is worth doing
  before anything gets pushed to a registry.
- Negative: the phase numbering in `CLAUDE.md` and the README no longer
  matches the order the work happened in. Both now say so explicitly; the
  numbers still identify the phases, they are just not the sequence.
- Negative: Docker Desktop's VM claims roughly half the host's memory while
  running. Bringing up one service at a time is now a working constraint,
  not a preference.
## Amendment, 2026-08-23: the container JVM is not the host JVM

The first version of this ADR claimed the container and the host run the
same JVM, so measurements taken in one are comparable with the other. That
was wrong, and checking it took one command:

| | JVM |
|---|---|
| Host, pinned by ADR-002 | `21.0.12.1`, 2026-08-18 |
| Container, `eclipse-temurin:21-jre` | `21.0.12`, 2026-07-21 |

Already different builds, a month apart — and the tag would have kept
moving. The claim was not merely unproven; it was false at the moment it
was written.

Two things follow. Base images are now pinned by digest, so an image
rebuilt next month is the image built today. And the honest position is
narrower than the original claim: the host and the container run *closely
related but distinct* HotSpot 21 builds, so a containerised measurement is
comparable with another containerised measurement, and a host measurement
with another host measurement — but the two sets are not interchangeable
without saying so.

`experiments/environment-baseline.md` already requires the exact
`java -version` string in every recorded run, which is what makes this
tractable: the JVM is captured per run rather than assumed. Any Pillar A
comparison must be run entirely inside containers or entirely outside, and
must say which.

- Revisit if: the container images need to be published anywhere, at which
  point their size stops being a local inconvenience.

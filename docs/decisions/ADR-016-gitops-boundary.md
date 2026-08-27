# ADR-016: Where GitOps stops

## Status
Accepted — 2026-08-27

## Context
Phase 13 is the optional last phase: reconcile the cluster from git rather
than from whoever last ran `deploy.sh`.

The obvious way to do that is to point a GitOps controller at
`infrastructure/kubernetes/` and turn on automated sync. That would be wrong
here, and the reason is the same one that shaped ADR-010.

## Decision

### Argo CD, core install, pinned

Argo CD rather than Flux, for one reason that matters at this size: the
`Application` and `AppProject` custom resources make "what is managed" and
"what is allowed" two separate, readable files. The second one is doing real
work below.

The **core** install — no API server, no web UI, no Dex. A controller, a repo
server and a redis. This cluster has 3.8 GB and runs on the machine the
experiments measure in; the components that exist to serve a console are not
worth their memory. Measured before and after: the node went from 2.51 GB to
2.72 GB, so Argo cost about 210 MB.

Pinned to `v3.4.8` like every other version in this project. That is the
latest patch of the previous minor rather than the newest tag, which was
published the same day this was written and has no operational history.

The install is applied with `--server-side`. A client-side apply stores the
whole manifest in a `last-applied-configuration` annotation, and Argo's
ApplicationSet CRD is larger than the 262144-byte ceiling on annotations, so
it fails **after** creating most of the install — which reads as a partial
success rather than an error.

### App-of-apps, one Application per deploy.sh flag

`root.yaml` is the only thing bootstrap applies. Its source is `apps/`, which
holds four Applications matching the flags `deploy.sh` already has: platform,
kafka, recovery, monitoring. Adding a component to the platform is then a file
and a commit, and the diff that adds an Application is the diff that grants
it.

### The devices are not managed, and the project enforces it

**This is the decision.** A device is a bare Pod with `restartPolicy: Never`
(ADR-010) precisely so that a dead device stays dead until the recovery
operator replaces it. Phase 11 measured the interval between those two events
and called it MTTR.

A GitOps controller with `selfHeal: true` reads a Pod that is present in git
and absent from the cluster as drift, and fixes it — in about a second, from
a component that is not the operator. The recorded MTTR would then be a
measurement of Argo's sync loop. This is exactly ADR-010's argument against
using a Deployment, one level up: it is not that self-healing is bad, it is
that *something else* healing the fleet destroys the thing being measured.

So `40-devices.yaml` moved from `base/` to `fleet/`, and no Application's path
includes `fleet/`.

A directory convention is one careless edit from being gone, so the AppProject
also refuses to manage a Pod at all — `namespaceResourceBlacklist: [{group:
"", kind: Pod}]`. Nothing in the four managed directories is a bare Pod; every
workload there is a Deployment or a StatefulSet, which own their Pods
themselves. The rule costs the platform nothing and closes the one door that
matters.

It fired on the first bootstrap run, unplanned. The child Applications were
tracking `main`, where the device manifests were still under `base/`, and Argo
reported `resource :Pod is not permitted in project fleet` for all three. The
guard was verified by the accident it exists to prevent.

### No Helm chart

`infrastructure/helm/` was reserved for this phase from the start. It stays
empty. Argo applies a directory of plain manifests without help, and a chart
would add templating for one environment — the local kind cluster — with no
second environment to differ from it. The trigger for taking it: a second
cluster whose manifests differ from this one's by more than an image tag.

This is the fifth time this project has declined a tool on the same grounds:
the Kubernetes client library (ADR-011), the metrics registry (ADR-012), the
chaos framework (ADR-013), the CI-hosted benchmark (ADR-014), and now Helm.

## Verified

On the kind cluster running the full stack plus the recovery operator:

- **Reconciliation works.** A hand-made `kubectl scale deployment/gateway
  --replicas=3` was detected within ten seconds and reverted to the declared
  single replica about nineteen seconds after it was introduced.
- **The fleet is left alone.** Git declares `edge-device-001/002/003`; the
  live fleet contained an operator-built `device-002-r-0194b194ba`. Every
  Application reported `Synced` throughout.
- **The recovery loop is unaffected by Argo's presence.** Killing a device pod
  with `--force --grace-period=0` produced an operator replacement while
  `fleet-platform` never left `Synced`.

None of that produced a number, which is why there is no run under
`experiments/` for this phase. The MTTR figures in this project come from the
Phase 11 recorded run and from nowhere else.

## Consequences

- **`deploy.sh` no longer has the last word.** For anything under the four
  managed directories, a locally edited manifest applied by hand is drift and
  is reverted within seconds. Commit it, or `bootstrap.sh --uninstall` first.
  Images and the device pods are unaffected — `deploy.sh` still owns both.
- **Argo manages manifests, not images.** `kind` has no registry, so images
  still arrive by side-load. Phase 12's tag-gated GHCR publish is what a
  cluster that is not this laptop would pull from, and wiring that up is the
  work this phase leaves for a real deployment target.
- **The Grafana dashboard ConfigMap stays imperative.** `deploy.sh` builds it
  from the single JSON file; committing a generated copy would be a second
  transcription of a twenty-panel dashboard and the two would drift. So the
  monitoring Application manages Prometheus and Grafana but not the dashboard
  they display — a real gap, accepted for a real reason.
- **Argo's own CRs are not schema-validated in CI.** `kubeconform` has no
  schema for `Application` or `AppProject`, so CI parses them as YAML and
  nothing more. A typo inside an `Application` spec is caught by
  `kubectl apply` at bootstrap, not by a green pipeline.
- **Trying an unmerged branch bypasses the app-of-apps.** A root pointed at a
  branch still reads that branch's `apps/*.yaml`, which say
  `targetRevision: main` because they are the production manifests. So
  `bootstrap.sh --revision` applies the four Applications directly and creates
  no root. `main` is the only revision the full pattern runs at.
- **The gateway is not horizontally scalable, and now that is known.** The
  drift used to demonstrate reconciliation — scaling the gateway to three —
  put three pods on the broker sharing one `GATEWAY_CLIENT_ID`. MQTT brokers
  evict an existing session when a client reconnects with the same id, so the
  three replicas kicked each other off in a loop, the gateway missed
  heartbeats it should have received, and it declared all three devices failed
  while every one of them was healthy. Argo reverted the scale in nineteen
  seconds and the false failures were already recorded. That is a property of
  the gateway rather than of GitOps, it is not fixed here, and it deserves its
  own change: the gateway should either derive a per-replica client id or
  refuse to start a second instance.

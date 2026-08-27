# infrastructure/gitops

Argo CD reconciles the fleet **platform** from this repository. It does not
reconcile the **devices**, and that boundary is the whole design — see
[ADR-016](../../docs/decisions/ADR-016-gitops-boundary.md).

```bash
./infrastructure/gitops/bootstrap.sh
```

That is the only imperative step. After it, the way to change the platform is
a commit to `main`.

## Layout

| File | What it is |
|---|---|
| `bootstrap.sh` | Installs Argo CD (core) and applies the root Application. Also `--uninstall`. |
| `project.yaml` | The `fleet` AppProject: one repo, two namespaces, and **no Pods**. |
| `root.yaml` | The app-of-apps. Its source is `apps/`, so adding a component is a commit. |
| `apps/*.yaml` | One Application per `deploy.sh` flag — platform, kafka, recovery, monitoring. |

## The boundary

A device is a bare Pod with `restartPolicy: Never` (ADR-010). When one dies it
has to *stay* dead until the recovery operator replaces it, because the
interval between those two events is the MTTR this project measures.

A GitOps controller with self-heal turned on would close that gap in about a
second, from the wrong component, and the number would then describe Argo's
sync loop. So `fleet/40-devices.yaml` is in no Application's path.

That alone would be a convention, and a convention is one careless edit from
being gone. The AppProject also refuses to manage a Pod at all:

```yaml
namespaceResourceBlacklist:
  - group: ""
    kind: Pod
```

This is not theoretical. The first bootstrap run pointed the children at
`main`, where the device manifests were still under `base/`, and Argo tried to
sync them:

```
Pod/edge-device-001: SyncFailed - resource :Pod is not permitted in project fleet
Pod/edge-device-002: SyncFailed - resource :Pod is not permitted in project fleet
Pod/edge-device-003: SyncFailed - resource :Pod is not permitted in project fleet
```

Nothing in `base/`, `kafka/`, `recovery/` or `monitoring/` is a bare Pod, so
the rule costs the platform nothing and closes the one door that matters.

## What was demonstrated

On the kind cluster, with the full stack and the recovery operator running:

- **Reconciliation.** `kubectl scale deployment/gateway --replicas=3` was
  detected within ten seconds and reverted to the declared single replica
  about nineteen seconds after the drift was introduced.
- **The fleet is left alone.** Git declares `edge-device-001/002/003`. The
  live fleet at the time contained `device-002-r-0194b194ba`, a replacement
  the operator had built. Every Application reported `Synced` throughout — Argo
  had no opinion about a pod it does not manage.
- **The recovery loop still works with Argo installed.** Killing
  `edge-device-002` with `--force --grace-period=0` produced an operator
  replacement, while `fleet-platform` never left `Synced`. Argo did not
  recreate the pod git declares, and did not delete the one the operator made.

Those are demonstrations, not results. Nothing here produced a number, which
is why there is no run under `experiments/` for it — the recorded MTTR figures
come from Phase 11 and from nowhere else.

## Trying a branch

```bash
./infrastructure/gitops/bootstrap.sh --revision my-branch
```

This applies the four component Applications directly and skips the root. A
root pointed at a branch would still read that branch's `apps/*.yaml`, and
those say `targetRevision: main` — as they must, being the production
manifests — so the children would track `main` while the root tracked the
branch. `main` is the only revision the full app-of-apps runs at.

## Once this is running, deploy.sh no longer has the last word

`deploy.sh` still builds images, side-loads them into kind, and manages the
device pods. But for anything under `base/`, `kafka/`, `recovery/` or
`monitoring/`, a local edit applied by hand is drift: Argo puts the cluster
back to what the repository says, within seconds. Commit the change, or
`--uninstall` first.

Two things stay imperative on purpose:

- **Images.** Argo applies manifests; it does not build or load images. `kind`
  has no registry, so `deploy.sh` still side-loads. Phase 12 publishes images
  to GHCR on a version tag, which is what a cluster that is not this laptop
  would pull.
- **The Grafana dashboard ConfigMap.** `deploy.sh` builds it from the single
  JSON file. Committing a generated copy would be a second transcription of a
  twenty-panel dashboard, and the two would drift.

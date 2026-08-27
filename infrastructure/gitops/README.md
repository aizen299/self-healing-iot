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
| `bootstrap.sh` | Installs Argo CD (core) and applies the root Application. Also `--uninstall`. Refuses to start if the manifests name a different repository than your `origin`, if the revision is not on the remote, or if a different Argo CD is already installed — and exits non-zero if the Applications do not reach `Synced`. |
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
  detected and reverted to the declared single replica automatically, well
  inside a minute.
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

## Forking

The Applications name a repository, and Argo reconciles from **that** one — not
from wherever your working copy came from. On a fork those differ, so
`bootstrap.sh` compares the `repoURL` in these manifests against
`git remote get-url origin` and refuses to run when they disagree. Point
`repoURL` and the AppProject's `sourceRepos` at your own fork first; otherwise
a cluster with prune and self-heal on would be reconciling somebody else's
`main` and ignoring your commits.

## Trying a branch

```bash
./infrastructure/gitops/bootstrap.sh --revision my-branch
```

This applies the four component Applications directly and skips the root. A
root pointed at a branch would still read that branch's `apps/*.yaml`, and
those say `targetRevision: main` — as they must, being the production
manifests — so the children would track `main` while the root tracked the
branch. `main` is the only revision the full app-of-apps runs at.

## Uninstalling

`--uninstall` removes this project's Applications by name and the `fleet`
AppProject. It removes Argo CD itself only when this script was what installed
it, which it knows from a label set on the namespace at install time. An Argo
CD that was already on the cluster — with, presumably, other Applications in
it — is left alone along with its CRDs.

## Once this is running, deploy.sh no longer has the last word

`deploy.sh` still builds images, side-loads them into kind, and manages the
device pods. But under `base/`, `kafka/`, `recovery/` and `monitoring/`, Argo
now owns **the fields those manifests declare**: change one by hand and it is
put back on the next reconcile. Commit the change, or `--uninstall` first.

Fields the manifests do *not* declare are a different matter, and the
distinction is worth knowing. Argo's three-way merge leaves them to whichever
manager set them, so a `kubectl rollout restart` annotation survives — which is
why `deploy.sh`'s restarts still work — and so would a stray `kubectl set env`
or an injected sidecar. Self-heal is not an undo button for everything done by
hand; it is an undo button for the things the repository has an opinion about.

Two things stay imperative on purpose:

- **Images.** Argo applies manifests; it does not build or load images. `kind`
  has no registry, so `deploy.sh` still side-loads. Phase 12 publishes images
  to GHCR on a version tag, which is what a cluster that is not this laptop
  would pull.
- **The Grafana dashboard ConfigMap.** `deploy.sh` builds it from the single
  JSON file. Committing a generated copy would be a second transcription of a
  twenty-panel dashboard, and the two would drift.

#!/usr/bin/env bash
#
# Installs Argo CD and hands it the platform.
#
# This is the only imperative step in the GitOps phase, and it exists because
# something has to put the reconciler in the cluster before the reconciler can
# reconcile. After this runs, the way to change the platform is a commit.
#
# What it deliberately does not manage: the device fleet. See ADR-016 and the
# comment in project.yaml — a device that has failed is *supposed* to be
# missing from the cluster while it is present in git, and a controller that
# closed that gap would be the thing replacing devices instead of the operator
# whose replacement time this project measures.
set -euo pipefail

cd "$(dirname "$0")"

# Pinned, like every other version in this project. A release published hours
# ago has no operational history, so this is the latest patch of the previous
# minor rather than the newest tag on the day of writing.
ARGOCD_VERSION="v3.4.8"
ARGOCD_MANIFEST="https://raw.githubusercontent.com/argoproj/argo-cd/${ARGOCD_VERSION}/manifests/core-install.yaml"

CLUSTER="${CLUSTER:-fleet}"
CONTEXT="kind-${CLUSTER}"
NAMESPACE="argocd"
REVISION="${REVISION:-main}"

KUBECTL=(kubectl --context "$CONTEXT")

say() { printf '\n=== %s\n' "$1"; }
die() { echo "error: $1" >&2; exit 1; }

usage() {
  cat <<'USAGE'
usage: bootstrap.sh [--revision REF] [--uninstall]

  --revision REF   git ref the Applications track (default: main). Use a branch
                   name to try a change before it is merged; the committed
                   manifests always say main.
  --uninstall      remove the Applications and Argo CD, leaving the fleet running.
USAGE
}

UNINSTALL=false
while [ $# -gt 0 ]; do
  case "$1" in
    --revision) REVISION="${2:?--revision needs a git ref}"; shift 2 ;;
    --uninstall) UNINSTALL=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
done

"${KUBECTL[@]}" cluster-info >/dev/null 2>&1 \
  || die "no cluster at context $CONTEXT — run infrastructure/kubernetes/deploy.sh first"

# --- uninstall ------------------------------------------------------------
# Order matters: the root Application has a finalizer that deletes its
# children, so it goes first and is waited for. Removing the namespace while
# that finalizer is pending leaves Applications that cannot be deleted.
if [ "$UNINSTALL" = true ]; then
  say "removing the Applications"
  "${KUBECTL[@]}" -n "$NAMESPACE" delete application fleet-root \
    --ignore-not-found --wait --timeout=120s || true
  "${KUBECTL[@]}" -n "$NAMESPACE" delete application --all \
    --ignore-not-found --wait --timeout=120s || true

  say "removing Argo CD"
  "${KUBECTL[@]}" delete namespace "$NAMESPACE" --ignore-not-found --wait --timeout=180s
  # The CRDs are cluster-scoped and outlive the namespace.
  "${KUBECTL[@]}" delete crd applications.argoproj.io applicationsets.argoproj.io \
    appprojects.argoproj.io --ignore-not-found
  echo
  echo "Argo CD removed. The fleet is untouched — nothing Argo managed was a"
  echo "device, and prune only removes what it created."
  exit 0
fi

# --- install --------------------------------------------------------------
say "installing Argo CD ${ARGOCD_VERSION} (core)"
# core-install, not the full install: no API server, no UI, no Dex — a
# controller, a repo server and a redis. The cluster this runs on has 3.8 GB
# and is also the machine the experiments measure in, so the components that
# exist to serve a web console are not worth their memory here.
"${KUBECTL[@]}" create namespace "$NAMESPACE" --dry-run=client -o yaml \
  | "${KUBECTL[@]}" apply -f -

# --server-side is not optional here. A client-side apply stores the whole
# manifest in a last-applied-configuration annotation, and Argo's
# ApplicationSet CRD is larger than the 262144-byte ceiling on annotations —
# it fails with "metadata.annotations: Too long" after creating most of the
# install, which looks like a partial success rather than an error.
"${KUBECTL[@]}" -n "$NAMESPACE" apply --server-side --force-conflicts \
  -f "$ARGOCD_MANIFEST"

say "waiting for Argo CD"
"${KUBECTL[@]}" -n "$NAMESPACE" rollout status deployment/argocd-repo-server --timeout=300s
"${KUBECTL[@]}" -n "$NAMESPACE" rollout status statefulset/argocd-application-controller --timeout=300s

say "applying the project"
"${KUBECTL[@]}" apply -f project.yaml

if [ "$REVISION" = "main" ]; then
  say "applying the root Application"
  # The normal path. Argo reads apps/ from git and creates the four component
  # Applications itself, so the set of managed components is whatever the
  # repository says it is.
  "${KUBECTL[@]}" apply -f root.yaml
else
  say "applying the component Applications directly at $REVISION"
  # The app-of-apps is deliberately bypassed here, because it cannot reach an
  # unmerged branch. A root pointed at a branch still reads that branch's
  # apps/*.yaml, and those say `targetRevision: main` — as they must, since
  # they are the committed production manifests. The children would then track
  # main while the root tracked the branch.
  #
  # That is not a hypothetical: the first run of this script did exactly that,
  # and the children synced main, where the device Pods are still under base/.
  # The AppProject refused them — "resource :Pod is not permitted in project
  # fleet" — which is the guard working, but it is not the thing being tested.
  #
  # So a branch is tried by applying the components directly with the revision
  # substituted, and no root: one owner per Application, and nothing to fight
  # over. `main` is the only revision the full pattern runs at.
  echo "  (no root Application — see the comment in this script)"
  for app in apps/*.yaml; do
    sed "s|targetRevision: main|targetRevision: $REVISION|" "$app" \
      | "${KUBECTL[@]}" apply -f -
  done
fi

say "waiting for the Applications to sync"
# Four components, plus the root when there is one.
expected=4
[ "$REVISION" = "main" ] && expected=5

for _ in $(seq 1 90); do
  count=$("${KUBECTL[@]}" -n "$NAMESPACE" get applications \
    -o jsonpath='{.items[*].metadata.name}' 2>/dev/null | wc -w | tr -d ' ')
  synced=$("${KUBECTL[@]}" -n "$NAMESPACE" get applications \
    -o jsonpath='{.items[*].status.sync.status}' 2>/dev/null \
    | tr ' ' '\n' | grep -c '^Synced$' || true)
  [ "$count" -ge "$expected" ] && [ "$synced" -ge "$expected" ] && break
  sleep 2
done

"${KUBECTL[@]}" -n "$NAMESPACE" get applications \
  -o custom-columns=NAME:.metadata.name,SYNC:.status.sync.status,HEALTH:.status.health.status

cat <<'DONE'

Argo CD is reconciling the platform from git. To change it, commit.

  kubectl --context kind-fleet -n argocd get applications
  kubectl --context kind-fleet -n argocd get application fleet-platform -o yaml

The device pods are not managed here and will not be reconciled. That is the
point: a device that fails must stay failed until the recovery operator
replaces it, because the gap between those two events is the measurement.
DONE

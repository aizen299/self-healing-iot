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
PROJECT="fleet"
REVISION="${REVISION:-main}"

# How long the Applications get to reach Synced before this script calls the
# bootstrap failed. Overridable so the failure path itself can be exercised.
SYNC_TIMEOUT_SECONDS="${SYNC_TIMEOUT_SECONDS:-300}"

# Set on the namespace at install time so --uninstall can tell an Argo CD this
# script installed from one that was already here. Removing somebody else's
# controller is not a thing to guess at.
OWNED_LABEL="fleet.io/installed-by"
OWNED_VALUE="gitops-bootstrap"

KUBECTL=(kubectl --context "$CONTEXT")

say() { printf '\n=== %s\n' "$1"; }
die() { echo "error: $1" >&2; exit 1; }

usage() {
  cat <<'USAGE'
usage: bootstrap.sh [--revision REF] [--uninstall]

  --revision REF   git ref the Applications track (default: main). Use a branch
                   name to try a change before it is merged; the committed
                   manifests always say main.
  --uninstall      remove this project's Applications, and Argo CD itself only
                   if this script was what installed it.
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

# The Applications this script owns, derived from the files rather than listed
# twice. Everything that deletes or waits is scoped to exactly these.
COMPONENTS=()
for app in apps/*.yaml; do
  [ -e "$app" ] || die "no Application manifests in $PWD/apps — wrong directory?"
  COMPONENTS+=("fleet-$(basename "$app" .yaml)")
done
OURS=(fleet-root "${COMPONENTS[@]}")

# --- uninstall ------------------------------------------------------------
if [ "$UNINSTALL" = true ]; then
  say "removing this project's Applications"
  # By name, never --all. Another project's Applications may share this
  # namespace, and they are not ours to delete.
  #
  # The root goes first and is waited for: its finalizer deletes its children,
  # and tearing the namespace down while that is pending leaves Applications
  # that cannot be removed.
  for app in "${OURS[@]}"; do
    "${KUBECTL[@]}" -n "$NAMESPACE" delete application "$app" \
      --ignore-not-found --wait --timeout=120s || true
  done
  "${KUBECTL[@]}" -n "$NAMESPACE" delete appproject "$PROJECT" --ignore-not-found || true

  # Asked as a label selector rather than a jsonpath. A jsonpath key holding
  # both a slash and dots needs escaping that is easy to get subtly wrong —
  # the first version of this did, and silently answered "not ours" for a
  # namespace this script had labelled itself.
  owned=$("${KUBECTL[@]}" get namespace -l "$OWNED_LABEL=$OWNED_VALUE" \
    -o jsonpath='{.items[*].metadata.name}' 2>/dev/null || true)

  if [[ " $owned " == *" $NAMESPACE "* ]]; then
    say "removing Argo CD (this script installed it)"
    "${KUBECTL[@]}" delete namespace "$NAMESPACE" --ignore-not-found --wait --timeout=180s
    # Cluster-scoped, so they outlive the namespace. Only safe to remove
    # because the label above says no one else's Argo CD is using them.
    "${KUBECTL[@]}" delete crd applications.argoproj.io applicationsets.argoproj.io \
      appprojects.argoproj.io --ignore-not-found
    echo
    echo "Argo CD removed. The fleet is untouched — nothing Argo managed was a"
    echo "device, and prune only removes what it created."
  else
    echo
    echo "Argo CD was already on this cluster when bootstrap.sh first ran, so it"
    echo "is left alone along with its CRDs. Only this project's Applications and"
    echo "the '$PROJECT' AppProject were removed."
  fi
  exit 0
fi

# --- checks before anything is applied ------------------------------------
# The manifests name a repository, and Argo will reconcile from that one — not
# from whatever this working copy came from. On a fork those differ, and the
# cluster would run the upstream author's main with prune and self-heal on.
declared_repo=$(grep -m1 -oE 'https://github\.com/[^ ]+\.git' root.yaml)
origin_repo=$(git remote get-url origin 2>/dev/null || true)
normalise() { echo "${1%.git}" | sed -e 's|^git@github\.com:|https://github.com/|' -e 's|/$||'; }
if [ -n "$origin_repo" ] \
   && [ "$(normalise "$declared_repo")" != "$(normalise "$origin_repo")" ]; then
  die "these manifests reconcile from $declared_repo, but this working copy is $origin_repo.
       Argo would deploy the other repository's code and ignore your commits.
       Point repoURL at your fork in infrastructure/gitops/*.yaml first."
fi

# Argo clones over the network, so a branch that exists only on this machine
# can never be resolved. Without this the Applications sit unresolvable and the
# only symptom is a comparison error nobody reads.
if [ -n "$origin_repo" ]; then
  git ls-remote --exit-code --heads --tags origin "$REVISION" >/dev/null 2>&1 \
    || die "revision '$REVISION' does not exist on origin — push the branch first"
fi

# An Argo CD that was already here belongs to someone else. --force-conflicts
# below would seize its fields and silently swap its version.
existing=$("${KUBECTL[@]}" -n "$NAMESPACE" get statefulset argocd-application-controller \
  -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)
if [ -n "$existing" ] && [ "${existing##*:}" != "$ARGOCD_VERSION" ]; then
  die "Argo CD ${existing##*:} is already installed in namespace $NAMESPACE.
       This script installs $ARGOCD_VERSION with --force-conflicts, which would
       take ownership of it. Remove it first, or align the versions."
fi

# --- install --------------------------------------------------------------
say "installing Argo CD ${ARGOCD_VERSION} (core)"
# core-install, not the full install: no API server, no UI, no Dex — a
# controller, a repo server and a redis. The cluster this runs on is also the
# machine the experiments measure in, so the components that exist to serve a
# web console are not worth their memory here.
"${KUBECTL[@]}" create namespace "$NAMESPACE" --dry-run=client -o yaml \
  | "${KUBECTL[@]}" apply -f -

# Only claim ownership if this run is the one creating it. --overwrite would
# relabel a namespace that was already somebody else's.
if [ -z "$existing" ]; then
  "${KUBECTL[@]}" label namespace "$NAMESPACE" "$OWNED_LABEL=$OWNED_VALUE" 2>/dev/null || true
fi

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
  EXPECTED=("${OURS[@]}")
else
  say "applying the component Applications directly at $REVISION"
  # The app-of-apps is deliberately bypassed here, because it cannot reach an
  # unmerged branch. A root pointed at a branch still reads that branch's
  # apps/*.yaml, and those say `targetRevision: main` — as they must, since
  # they are the committed production manifests. The children would then track
  # main while the root tracked the branch.
  #
  # That is not a hypothetical: the first run of this script did exactly that,
  # and the children synced main, where the device Pods were still under base/.
  # The AppProject refused them — "resource :Pod is not permitted in project
  # fleet" — which is the guard working, but it is not the thing being tested.
  #
  # So a branch is tried by applying the components directly and patching the
  # revision, with no root: one owner per Application, nothing to fight over.
  # `main` is the only revision the full pattern runs at.
  echo "  (no root Application — see the comment in this script)"

  # A patch file rather than sed on the manifest. Text substitution silently
  # does nothing when the pattern drifts, and a ref may legally contain the
  # characters sed treats as syntax — `&` means "the matched text", `|` ends
  # the expression. json.dumps escapes whatever the ref actually is, and
  # `kubectl patch` either applies or fails.
  patch_file=$(mktemp)
  trap 'rm -f "$patch_file"' EXIT
  REVISION="$REVISION" python3 -c 'import json,os,sys
sys.stdout.write(json.dumps(
    {"spec": {"source": {"targetRevision": os.environ["REVISION"]}}}))' > "$patch_file"

  for app in apps/*.yaml; do
    "${KUBECTL[@]}" apply -f "$app"
    "${KUBECTL[@]}" -n "$NAMESPACE" patch application "fleet-$(basename "$app" .yaml)" \
      --type=merge --patch-file "$patch_file"
  done
  EXPECTED=("${COMPONENTS[@]}")
fi

say "waiting for the Applications to sync"
# Scoped to the Applications this script owns. Counting everything in the
# namespace would let somebody else's Synced app stand in for one of ours.
deadline=$(( $(date +%s) + SYNC_TIMEOUT_SECONDS ))
while :; do
  pending=()
  for app in "${EXPECTED[@]}"; do
    status=$("${KUBECTL[@]}" -n "$NAMESPACE" get application "$app" \
      -o jsonpath='{.status.sync.status}' 2>/dev/null || true)
    [ "$status" = "Synced" ] || pending+=("$app=${status:-absent}")
  done
  [ ${#pending[@]} -eq 0 ] && break
  [ "$(date +%s)" -ge "$deadline" ] && break
  sleep 2
done

"${KUBECTL[@]}" -n "$NAMESPACE" get applications \
  -o custom-columns=NAME:.metadata.name,SYNC:.status.sync.status,HEALTH:.status.health.status

# A bootstrap that did not converge must not report that it did. The first
# version of this script printed the banner below unconditionally, and its own
# first run ended with four Applications OutOfSync directly above the words
# "Argo CD is reconciling the platform from git". That is the failure this
# project designs against everywhere else — ADR-013's discarded runs, ADR-014's
# suite-complete gate — so it fails here too.
if [ ${#pending[@]} -ne 0 ]; then
  echo >&2
  echo "error: these Applications did not reach Synced within ${SYNC_TIMEOUT_SECONDS}s:" >&2
  for p in "${pending[@]}"; do echo "  $p" >&2; done
  echo >&2
  echo "  kubectl --context $CONTEXT -n $NAMESPACE get application ${pending[0]%%=*} -o yaml" >&2
  exit 1
fi

cat <<DONE

Argo CD is reconciling the platform from git. To change it, commit.

  kubectl --context $CONTEXT -n $NAMESPACE get applications
  kubectl --context $CONTEXT -n $NAMESPACE get application fleet-platform -o yaml

The device pods are not managed here and will not be reconciled. That is the
point: a device that fails must stay failed until the recovery operator
replaces it, because the gap between those two events is the measurement.
DONE

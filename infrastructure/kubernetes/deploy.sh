#!/usr/bin/env bash
#
# Build the images, side-load them into kind, and bring up the fleet.
#
# Idempotent: safe to re-run, and re-running is how you deploy a code change.
#
#   ./infrastructure/kubernetes/deploy.sh            base stack, no Kafka
#   ./infrastructure/kubernetes/deploy.sh --kafka    with Kafka and the stream processor
#   ./infrastructure/kubernetes/deploy.sh --down     delete the cluster
#
# There is no registry. Images are built on the host and pushed into the kind
# node with `kind load`, which is why every manifest sets
# imagePullPolicy: IfNotPresent — the default for a non-":latest" tag is
# IfNotPresent too, but stating it keeps a later tag change from silently
# turning it into Always and failing to pull an image that was never pushed.
set -euo pipefail

CLUSTER=fleet
NAMESPACE=fleet
TAG=0.1.0
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

WITH_KAFKA=false
for arg in "$@"; do
  case "$arg" in
    --kafka) WITH_KAFKA=true ;;
    --down)
      kind delete cluster --name "$CLUSTER"
      exit 0
      ;;
    *)
      echo "unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  say "creating the kind cluster"
  kind create cluster --config "$HERE/kind-cluster.yaml"
fi

# kubectl's fallback when no context is set is http://localhost:8080, which on
# this machine is Jenkins — it answers, so the failure looks like a puzzling
# authentication error rather than "you are not talking to Kubernetes".
kubectl config use-context "kind-$CLUSTER" >/dev/null

say "building images"
IMAGES=(gateway edge-device)
if [ "$WITH_KAFKA" = true ]; then
  IMAGES+=(stream-processor)
fi
for target in "${IMAGES[@]}"; do
  docker build -f "$ROOT/infrastructure/docker/Dockerfile" \
    --target "$target" -t "fleet/$target:$TAG" "$ROOT"
done

say "loading images into the cluster"
for target in "${IMAGES[@]}"; do
  kind load docker-image "fleet/$target:$TAG" --name "$CLUSTER"
done

# Bare Pods are immutable: `kubectl apply` cannot add a container, change an
# image, or edit env on a Pod that already exists — it fails with "pod updates
# may not add or remove containers". Deployments roll; Pods are replaced. This
# is the other half of the price of ADR-010's restartPolicy: Never.
#
# --ignore-not-found so a first run is not an error, and --wait so the new pods
# are not rejected as duplicates of ones still terminating.
say "replacing device pods"
kubectl -n "$NAMESPACE" delete pod -l app=edge-device --ignore-not-found --wait

say "applying manifests"
kubectl apply -f "$HERE/base/"
if [ "$WITH_KAFKA" = true ]; then
  kubectl apply -f "$HERE/kafka/"
  kubectl -n "$NAMESPACE" set env deployment/gateway GATEWAY_KAFKA_ENABLED=true
fi

# A code change produces a new image under the same tag, and Kubernetes cannot
# see that: the pod spec is byte-identical, so nothing rolls. The device pods
# were recreated above; the Deployments need telling.
say "restarting workloads onto the new images"
kubectl -n "$NAMESPACE" rollout restart deployment/gateway
if [ "$WITH_KAFKA" = true ]; then
  kubectl -n "$NAMESPACE" rollout restart deployment/stream-processor
fi

say "waiting for the pipeline"
kubectl -n "$NAMESPACE" rollout status deployment/broker --timeout=120s
kubectl -n "$NAMESPACE" rollout status deployment/gateway --timeout=180s
kubectl -n "$NAMESPACE" wait --for=condition=Ready pod \
  -l app=edge-device --timeout=120s

say "fleet"
kubectl -n "$NAMESPACE" get pods -o wide
cat <<EOF

The gateway is on http://127.0.0.1:18080 (kind maps 30080 -> 18080).

  curl -s http://127.0.0.1:18080/health
  curl -s http://127.0.0.1:18080/devices
  mosquitto_sub -h 127.0.0.1 -p 11883 -t 'fleet/+/telemetry' -v

Kill a device and watch the gateway notice — nothing recreates it, which is
the point (see ADR-010):

  kubectl -n $NAMESPACE delete pod edge-device-002 --grace-period=0 --force
  curl -s http://127.0.0.1:18080/devices/device-002

EOF

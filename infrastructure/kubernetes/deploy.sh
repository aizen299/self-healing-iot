#!/usr/bin/env bash
#
# Build the images, side-load them into kind, and bring up the fleet.
#
# Idempotent: safe to re-run, and re-running is how you deploy a code change.
#
#   ./infrastructure/kubernetes/deploy.sh            base stack, no Kafka
#   ./infrastructure/kubernetes/deploy.sh --kafka    with Kafka and the stream processor
#   ./infrastructure/kubernetes/deploy.sh --recovery with Kafka and the recovery operator
#   ./infrastructure/kubernetes/deploy.sh --monitoring  add Prometheus and Grafana
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
WITH_RECOVERY=false
WITH_MONITORING=false
for arg in "$@"; do
  case "$arg" in
    --kafka) WITH_KAFKA=true ;;
    # The operator consumes device.failures, so it cannot work without Kafka.
    # Implied rather than required as a second flag: a --recovery that came up
    # and silently recovered nothing would be worse than one that pulls in
    # what it needs.
    --recovery) WITH_RECOVERY=true; WITH_KAFKA=true ;;
    # Prometheus and Grafana. Composable with the others rather than implying
    # them: the dashboard is worth having over the base stack alone, and the
    # gateway's panels all work without Kafka. The operator's panels stay at
    # zero until --recovery is on, which is honest rather than broken.
    --monitoring) WITH_MONITORING=true ;;
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
  # kind switches the current context to the cluster it just made, so simply
  # not calling `kubectl config use-context` is not enough to leave the user's
  # kubeconfig alone. Put it back.
  previous_context="$(kubectl config current-context 2>/dev/null || true)"
  kind create cluster --config "$HERE/kind-cluster.yaml"
  if [ -n "$previous_context" ]; then
    kubectl config use-context "$previous_context" >/dev/null
    echo "current context left as $previous_context; this script uses --context"
  else
    kubectl config unset current-context >/dev/null
    echo "no current context was set before; left unset. This script uses --context"
  fi
fi

# --context on every call, rather than `kubectl config use-context`. That
# command changes the user's default context globally and never changes it
# back, so running this script would silently re-point every kubectl in every
# terminal at the kind cluster — including the next one typed at a real one.
#
# It has to be set somehow: kubectl's fallback when no context is configured
# is http://localhost:8080, which on this machine is Jenkins. It answers, so
# the failure reads as a puzzling authentication error rather than "you are
# not talking to Kubernetes".
KUBECTL=(kubectl --context "kind-$CLUSTER")

say "building images"
IMAGES=(gateway edge-device)
if [ "$WITH_KAFKA" = true ]; then
  IMAGES+=(stream-processor)
fi
if [ "$WITH_RECOVERY" = true ]; then
  IMAGES+=(recovery-operator)
fi
for target in "${IMAGES[@]}"; do
  docker build -f "$ROOT/infrastructure/docker/Dockerfile" \
    --target "$target" -t "fleet/$target:$TAG" "$ROOT"
done

# The third-party images too, not just ours.
#
# A fresh kind node has an empty image cache, so it pulls mosquitto and
# Kafka's ~392 MB over the network while the rollout timeouts below are
# already running — the first deploy on a new cluster fails on a slow link
# with everything stuck in ContainerCreating. The host has these already from
# compose, and side-loading them costs seconds. Pinned by digest here and in
# the manifests, so the two cannot drift apart.
BROKER_IMAGE=eclipse-mosquitto@sha256:6f8d8a947c506f8a2290ec65cd4bd2bc7cb4d43fb5f6271f861cb013e2ef9797
KAFKA_IMAGE=apache/kafka@sha256:fbc7d7c428e3755cf36518d4976596002477e4c052d1f80b5b9eafd06d0fff2f
# prom/prometheus:v3.1.0 and grafana/grafana:11.5.1, pinned by digest like the
# rest. Between them they are about 1.1 GB of image — Grafana alone is larger
# than Kafka — which is most of why monitoring is a flag rather than part of
# base/.
PROMETHEUS_IMAGE=prom/prometheus@sha256:6559acbd5d770b15bb3c954629ce190ac3cbbdb2b7f1c30f0385c4e05104e218
GRAFANA_IMAGE=grafana/grafana@sha256:5781759b3d27734d4d548fcbaf60b1180dbf4290e708f01f292faa6ae764c5e6

THIRD_PARTY=("$BROKER_IMAGE")
if [ "$WITH_KAFKA" = true ]; then
  THIRD_PARTY+=("$KAFKA_IMAGE")
fi
if [ "$WITH_MONITORING" = true ]; then
  THIRD_PARTY+=("$PROMETHEUS_IMAGE" "$GRAFANA_IMAGE")
fi

# Pulled inside the node, not side-loaded from the host. `kind load
# docker-image` cannot take a digest reference: the host's Docker store holds
# only the single-platform manifest it pulled, so the export is missing content
# the digest names and ctr rejects it. crictl pulls the real thing.
#
# It has to happen here rather than being left to the kubelet, because a fresh
# node has an empty image cache and Kafka's image is ~392 MB: left implicit, the
# download runs inside the rollout timeouts below and the first deploy on a new
# cluster fails with everything stuck in ContainerCreating.
#
# Best effort — if this fails the kubelet still pulls, just more slowly, so a
# pull error here should not abort a deploy that would otherwise work.
say "pre-pulling third-party images into the node"
for image in "${THIRD_PARTY[@]}"; do
  echo "  $image"
  docker exec "$CLUSTER-control-plane" crictl pull "$image" >/dev/null 2>&1 \
    || echo "  (pre-pull failed; leaving it to the kubelet)"
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
"${KUBECTL[@]}" -n "$NAMESPACE" delete pod -l app=edge-device --ignore-not-found --wait

say "applying manifests"
"${KUBECTL[@]}" apply -f "$HERE/base/"
if [ "$WITH_KAFKA" = true ]; then
  # No `set env` here any more. The fleet-kafka ConfigMap in that directory
  # carries both GATEWAY_KAFKA_ENABLED and the bootstrap address, and the
  # gateway picks it up with an optional configMapRef — so applying the
  # overlay is the switch, and the next `apply -f base/` has nothing to
  # revert. The rollout restart below is what makes the gateway read it.
  "${KUBECTL[@]}" apply -f "$HERE/kafka/"
fi
if [ "$WITH_RECOVERY" = true ]; then
  "${KUBECTL[@]}" apply -f "$HERE/recovery/"
fi
if [ "$WITH_MONITORING" = true ]; then
  "${KUBECTL[@]}" apply -f "$HERE/monitoring/"
  # The dashboard has one copy in this repository — the JSON file — and this
  # is what puts it in the cluster. monitoring/91-grafana.yaml deliberately
  # does not carry a transcription of it: two copies of a 20-panel dashboard
  # would disagree the first time one was edited.
  "${KUBECTL[@]}" -n "$NAMESPACE" create configmap grafana-dashboards \
    --from-file="fleet.json=$ROOT/infrastructure/monitoring/grafana/dashboards/fleet.json" \
    --dry-run=client -o yaml | "${KUBECTL[@]}" apply -f -
fi

say "waiting for the pipeline"
"${KUBECTL[@]}" -n "$NAMESPACE" rollout status deployment/broker --timeout=300s

# Kafka before anything that talks to it, and before the gateway is restarted
# onto the new image — not merely before the script finishes.
#
# Originally because the gateway built its KafkaProducer once at startup and
# forwarded nothing for the life of the process if the bootstrap address did
# not resolve yet — observed as a stack where every pod was healthy and
# telemetry.raw did not exist. The gateway retries that construction now, so
# it would recover on its own within ten seconds.
#
# The wait stays for the reasons that are still true: the topic-creation job in
# this overlay needs a live broker, the stream processor refuses to start
# against a source topic that does not exist, and a deploy script that returns
# before the stack is usable is not much of a deploy script.
if [ "$WITH_KAFKA" = true ]; then
  "${KUBECTL[@]}" -n "$NAMESPACE" rollout status statefulset/kafka --timeout=600s
fi

# A code change produces a new image under the same tag, and Kubernetes cannot
# see that: the pod spec is byte-identical, so nothing rolls. The device pods
# were recreated above; the Deployments need telling.
say "restarting workloads onto the new images"
"${KUBECTL[@]}" -n "$NAMESPACE" rollout restart deployment/gateway
if [ "$WITH_KAFKA" = true ]; then
  "${KUBECTL[@]}" -n "$NAMESPACE" rollout restart deployment/stream-processor
fi
if [ "$WITH_RECOVERY" = true ]; then
  "${KUBECTL[@]}" -n "$NAMESPACE" rollout restart deployment/recovery-operator
fi
if [ "$WITH_MONITORING" = true ]; then
  # Grafana only: it reads the dashboard ConfigMap at startup, so a dashboard
  # edit needs a new pod. Prometheus is left alone — its config is reloaded
  # through --web.enable-lifecycle and its series are worth keeping across a
  # redeploy.
  "${KUBECTL[@]}" -n "$NAMESPACE" rollout restart deployment/grafana
fi

say "waiting for the workloads"
"${KUBECTL[@]}" -n "$NAMESPACE" rollout status deployment/gateway --timeout=180s
if [ "$WITH_KAFKA" = true ]; then
  "${KUBECTL[@]}" -n "$NAMESPACE" rollout status deployment/stream-processor --timeout=240s
fi
if [ "$WITH_RECOVERY" = true ]; then
  "${KUBECTL[@]}" -n "$NAMESPACE" rollout status deployment/recovery-operator --timeout=240s
fi
if [ "$WITH_MONITORING" = true ]; then
  "${KUBECTL[@]}" -n "$NAMESPACE" rollout status deployment/prometheus --timeout=300s
  "${KUBECTL[@]}" -n "$NAMESPACE" rollout status deployment/grafana --timeout=300s
fi
"${KUBECTL[@]}" -n "$NAMESPACE" wait --for=condition=Ready pod \
  -l app=edge-device --timeout=300s

# The device-id label and FLEET_DEVICE_INDEX_OFFSET encode the same identity
# and nothing in Kubernetes can derive one from the other. A pod labelled
# device-002 that publishes as device-003 fails silently — the gateway sees
# valid ids and the fleet looks healthy — but Phase 9's operator selects on
# that label and would recover the wrong pod, apparently successfully.
#
# So check it against what the gateway actually received, which is the only
# authority on what each pod published.
say "checking device-id labels against what the gateway received"
labelled=$("${KUBECTL[@]}" -n "$NAMESPACE" get pods -l app=edge-device \
  -o jsonpath='{range .items[*]}{.metadata.labels.device-id}{"\n"}{end}' | sort)

# Only devices actually reporting. `presenceOnly:true` marks a device known
# solely from retained presence — a ghost left on the broker by an earlier
# run, which the gateway deliberately keeps and never acts on. Comparing
# against every id the gateway has ever heard of would fail a perfectly good
# deploy the first time a ghost outlived its pod.
reporting() {
  "${KUBECTL[@]}" -n "$NAMESPACE" exec deployment/gateway -c gateway -- \
    sh -c 'curl -fsS http://127.0.0.1:8080/devices' 2>/dev/null \
    | tr '{' '\n' \
    | grep -F '"presenceOnly":false' \
    | sed -n 's/.*"deviceId":"\([^"]*\)".*/\1/p' \
    | sort
}

# Devices publish on their first tick, and the readiness gate above only
# proves the process started, so give the fleet a few ticks to be heard
# before calling a mismatch.
for _ in $(seq 1 10); do
  reported="$(reporting || true)"
  [ "$labelled" = "$reported" ] && break
  sleep 2
done

if [ "$labelled" != "$reported" ]; then
  echo "device-id labels do not match the ids the gateway is receiving." >&2
  echo "  labelled : $(echo "$labelled" | tr '\n' ' ')" >&2
  echo "  reporting: $(echo "$reported" | tr '\n' ' ')" >&2
  echo "Check FLEET_DEVICE_INDEX_OFFSET against the device-id label in base/40-devices.yaml." >&2
  exit 1
fi
echo "  ok: $(echo "$labelled" | tr '\n' ' ')"

say "fleet"
"${KUBECTL[@]}" -n "$NAMESPACE" get pods -o wide
cat <<EOF

The gateway is on http://127.0.0.1:18080 (kind maps 30080 -> 18080).

  curl -s http://127.0.0.1:18080/health
  curl -s http://127.0.0.1:18080/devices

18081 is the same gateway through a Service that publishes it even when it is
not ready, so /health and /history stay reachable during a broker outage:

  curl -s http://127.0.0.1:18081/health
  mosquitto_sub -h 127.0.0.1 -p 11883 -t 'fleet/+/telemetry' -v

Kill a device and watch what happens:

  kubectl -n $NAMESPACE delete pod edge-device-002 --grace-period=0 --force
  curl -s http://127.0.0.1:18080/devices/device-002
  kubectl -n $NAMESPACE get pods -l app=edge-device -L device-id,recovery-id

EOF
if [ "$WITH_RECOVERY" = true ]; then
  cat <<EOF
The operator is watching device.failures, so that device comes back on its own:

  kubectl -n $NAMESPACE logs deployment/recovery-operator -f

EOF
else
  cat <<EOF
Nothing recreates it — that is Phase 8's point (ADR-010). Add --recovery to
deploy the operator that does.

EOF
fi
if [ "$WITH_MONITORING" = true ]; then
  cat <<EOF
Grafana is on http://127.0.0.1:13000 — anonymous, opening on the fleet
dashboard. Prometheus is on http://127.0.0.1:19090 for when a panel says
"No data" and you need to know whether the target is even up:

  open http://127.0.0.1:13000
  curl -s http://127.0.0.1:19090/api/v1/targets | grep -o '"health":"[a-z]*"'

Nothing on that dashboard is a result. Only a run recorded under
experiments/results/ supports a reported number.

EOF
else
  cat <<EOF
No dashboard: add --monitoring for Prometheus and Grafana. The gateway is
already exposing everything they read:

  curl -s http://127.0.0.1:18081/metrics | head -20

EOF
fi

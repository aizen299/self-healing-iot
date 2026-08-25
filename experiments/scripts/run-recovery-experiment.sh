#!/usr/bin/env bash
#
# Runs one Pillar B recovery experiment end to end and records it.
#
#   ./experiments/scripts/run-recovery-experiment.sh experiments/configs/b1-pod-loss.env
#
# Writes experiments/results/raw/<run-id>/ containing:
#
#   metadata.json    machine, toolchain, image tags, cluster and fleet config
#   recovery.jsonl   every record device.recovery carried during the run
#   iterations.jsonl one line per injected failure, as the runner saw it
#   run.log          the console transcript
#
# The authoritative numbers come from recovery.jsonl — the gateway's and the
# operator's own measurements, taken inside the system. The runner's own
# timings in iterations.jsonl are a cross-check from outside it, deliberately
# kept separate so a disagreement between them is visible rather than
# averaged away.
#
# Exits non-zero if any iteration failed to recover. A run that did not fully
# recover is still recorded — the reproducibility contract wants the failures
# too, and a recovery-success-rate of 100% means nothing if the runs that
# were not 100% were deleted.
set -euo pipefail

CLUSTER=fleet
NAMESPACE=fleet
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

if [ $# -ne 1 ]; then
  echo "usage: $(basename "$0") <config.env>" >&2
  exit 2
fi
CONFIG="$1"
if [ ! -f "$CONFIG" ]; then
  echo "no such config: $CONFIG" >&2
  exit 2
fi
# shellcheck disable=SC1090
source "$CONFIG"

# --context on every call, for the reason deploy.sh gives at length: kubectl
# with no context configured falls back to http://localhost:8080, which on
# this machine is Jenkins, and answers.
KUBECTL=(kubectl --context "kind-$CLUSTER" -n "$NAMESPACE")

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { echo "error: $*" >&2; exit 1; }

RUN_ID="${EXPERIMENT_ID}-$(date -u +%Y%m%dT%H%M%SZ)"
RAW="$ROOT/experiments/results/raw/$RUN_ID"
mkdir -p "$RAW"

# Everything after this point is teed, so run.log is the transcript of the
# run that produced the numbers beside it.
exec > >(tee "$RAW/run.log") 2>&1

say "preflight"
"${KUBECTL[@]}" get namespace "$NAMESPACE" >/dev/null 2>&1 \
  || die "namespace $NAMESPACE not found — deploy with --recovery first"
for deployment in gateway recovery-operator; do
  ready=$("${KUBECTL[@]}" get deployment "$deployment" \
    -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo 0)
  [ "${ready:-0}" -ge 1 ] || die "$deployment is not ready — this experiment needs --recovery"
done
"${KUBECTL[@]}" get statefulset kafka >/dev/null 2>&1 \
  || die "kafka not found — recovery.jsonl is read from device.recovery"

# Every device named in the config must actually be reporting before a single
# failure is injected. Starting a run against a fleet that is already one
# device down measures recovery from an unknown state.
for device in $DEVICE_IDS; do
  pod=$("${KUBECTL[@]}" get pods -l "device-id=$device" \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
  [ -n "$pod" ] || die "no pod carries device-id=$device"
  phase=$("${KUBECTL[@]}" get pod "$pod" -o jsonpath='{.status.phase}')
  [ "$phase" = "Running" ] || die "$pod is $phase, not Running"
  echo "  $device -> $pod ($phase)"
done

say "recording metadata"
GATEWAY_POD=$("${KUBECTL[@]}" get pods -l app=gateway -o jsonpath='{.items[0].metadata.name}')
DEVICE_COUNT=$(echo "$DEVICE_IDS" | wc -w | tr -d ' ')

# Captured from the cluster rather than restated here, so the metadata cannot
# drift from the fleet that produced the numbers.
#
# The startup headers, not a list of ConfigMap keys I had to guess right. Each
# process prints its own effective configuration — including the values that
# came from a code default and appear in no ConfigMap at all — so this records
# what the run actually used rather than what was set. The first version of
# this script read three named keys, two of which did not exist, and wrote
# "publishing interval: None" into a file whose whole purpose is to make the
# result checkable.
header_of() {
  "${KUBECTL[@]}" logs "$1" 2>/dev/null | sed -n '1,/^$/p' | sed '/^$/d' || true
}
DEVICE_POD=$("${KUBECTL[@]}" get pods -l app=edge-device \
  -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
header_of "deployment/gateway" > "$RAW/gateway-config.txt"
header_of "deployment/recovery-operator" > "$RAW/operator-config.txt"
[ -n "$DEVICE_POD" ] && header_of "$DEVICE_POD" > "$RAW/device-config.txt"
"${KUBECTL[@]}" get configmap fleet-config fleet-device-config -o json \
  > "$RAW/configmaps.json" 2>/dev/null || true
echo "  captured startup headers and ConfigMaps"

python3 - "$RAW/metadata.json" <<PY
import json, platform, subprocess, sys, os

def sh(*cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True,
                              timeout=30).stdout.strip()
    except Exception:
        return ""

kubectl = ["kubectl", "--context", "kind-$CLUSTER", "-n", "$NAMESPACE"]
raw = os.path.dirname(sys.argv[1])


def read_lines(name):
    path = os.path.join(raw, name)
    if not os.path.exists(path):
        return []
    return [line for line in open(path).read().splitlines() if line.strip()]


def configmap_data():
    path = os.path.join(raw, "configmaps.json")
    if not os.path.exists(path):
        return {}
    items = json.load(open(path)).get("items", [])
    return {item["metadata"]["name"]: item.get("data", {}) for item in items}


configmaps = configmap_data()

def images():
    out = sh(*kubectl, "get", "pods", "-o",
             "jsonpath={range .items[*]}{.metadata.name}{'\\t'}"
             "{.spec.containers[0].image}{'\\n'}{end}")
    return dict(line.split("\\t", 1) for line in out.splitlines() if "\\t" in line)

metadata = {
    "runId": "$RUN_ID",
    "experimentId": "$EXPERIMENT_ID",
    "pillar": "B - automated failure detection and recovery",
    "startedAtUtc": sh("date", "-u", "+%Y-%m-%dT%H:%M:%SZ"),
    # The baseline file is the reference; a run records what differs from it
    # and enough to prove it was this machine.
    "baseline": "experiments/environment-baseline.md",
    "machine": {
        "os": platform.platform(),
        "arch": platform.machine(),
        "cpuCount": os.cpu_count(),
        "memoryBytes": int(sh("sysctl", "-n", "hw.memsize") or 0),
    },
    "toolchain": {
        "java": sh(os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "java"), "-version")
                or sh("java", "-version"),
        "kind": sh("kind", "version"),
        "kubernetesServer": json.loads(sh(*kubectl[:2], "version", "-o", "json")
                                       or "{}").get("serverVersion", {}).get("gitVersion"),
        "docker": sh("docker", "--version"),
    },
    "fleet": {
        "model": "one device per pod (bare Pods, restartPolicy: Never - ADR-010)",
        "deviceCount": int("$DEVICE_COUNT"),
        "deviceIds": "$DEVICE_IDS".split(),
        # One value drives the device's publish interval and the gateway's
        # expected heartbeat interval, because heartbeats ride the telemetry
        # tick (ADR-006) and a mismatch shows up as false failures.
        "tickIntervalMs": configmaps.get("fleet-config", {}).get("TICK_INTERVAL_MS"),
        "variant": configmaps.get("fleet-device-config", {}).get("FLEET_VARIANT"),
        "heapLimit": configmaps.get("fleet-device-config", {}).get("JAVA_OPTS"),
        "sink": configmaps.get("fleet-device-config", {}).get("FLEET_SINK"),
        "devicesPerPod": configmaps.get("fleet-device-config", {}).get("FLEET_DEVICE_COUNT"),
        # Verbatim, so a value that came from a code default rather than a
        # ConfigMap is still on the record.
        "gatewayStartupConfig": read_lines("gateway-config.txt"),
        "deviceStartupConfig": read_lines("device-config.txt"),
        "operatorStartupConfig": read_lines("operator-config.txt"),
    },
    "experiment": {
        "failureMode": "$FAILURE_MODE",
        "iterations": int("$ITERATIONS"),
        "cooldownSeconds": int("$COOLDOWN_SECONDS"),
        "recoveryTimeoutSeconds": int("$RECOVERY_TIMEOUT_SECONDS"),
    },
    "images": images(),
    "notes": [
        "Numbers in recovery.jsonl are the system's own measurements.",
        "iterations.jsonl holds the runner's external view, as a cross-check.",
        "MTTR is the gateway's recoveryDurationMillis. The operator's"
        " detectionToReplacementMillis is a component of it and the two must"
        " never be added.",
    ],
}
with open(sys.argv[1], "w") as f:
    json.dump(metadata, f, indent=2)
    f.write("\\n")
print("  wrote", sys.argv[1])
PY

say "attaching to device.recovery"
# From the current end offset, not from the beginning: this run's records
# only. Reading the offset first and passing it explicitly avoids the race in
# starting a consumer at "latest" and missing what is produced while it joins.
START_OFFSET=$("${KUBECTL[@]}" exec kafka-0 -- /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server localhost:9092 --topic device.recovery 2>/dev/null \
  | awk -F: '{print $3}')
[ -n "$START_OFFSET" ] || die "could not read the end offset of device.recovery"
echo "  device.recovery starts at offset $START_OFFSET"

"${KUBECTL[@]}" exec kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic device.recovery \
  --partition 0 --offset "$START_OFFSET" > "$RAW/recovery.jsonl" 2>/dev/null &
CONSUMER_PID=$!
# Give the consumer time to join before the first failure is injected.
sleep 5
kill -0 "$CONSUMER_PID" 2>/dev/null || die "the device.recovery consumer did not start"

cleanup() {
  kill "$CONSUMER_PID" 2>/dev/null || true
  wait "$CONSUMER_PID" 2>/dev/null || true
}
trap cleanup EXIT

now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }

# The gateway is the authority on whether a device is back: it is the thing
# that has to see heartbeats again. A pod being Running is not the same claim.
device_health() {
  curl -fsS "http://127.0.0.1:18081/devices/$1" 2>/dev/null \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("health","?"))' 2>/dev/null \
    || echo "?"
}

say "injecting $ITERATIONS $FAILURE_MODE failures"
: > "$RAW/iterations.jsonl"
failures=0
i=0
for _ in $(seq 1 "$ITERATIONS"); do
  i=$((i + 1))
  # Round-robin, so a result is not a property of one device's pod.
  device=$(echo "$DEVICE_IDS" | tr ' ' '\n' | sed -n "$(( (i - 1) % DEVICE_COUNT + 1 ))p")
  pod=$("${KUBECTL[@]}" get pods -l "device-id=$device" \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
  if [ -z "$pod" ]; then
    echo "  [$i/$ITERATIONS] $device has no pod; recording as a failure"
    printf '{"iteration":%d,"deviceId":"%s","pod":null,"error":"no pod"}\n' \
      "$i" "$device" >> "$RAW/iterations.jsonl"
    failures=$((failures + 1))
    continue
  fi

  injected=$(now_ms)
  "${KUBECTL[@]}" delete pod "$pod" --grace-period=0 --force >/dev/null 2>&1
  printf '  [%d/%d] killed %s (%s) ... ' "$i" "$ITERATIONS" "$pod" "$device"

  deadline=$(( $(date +%s) + RECOVERY_TIMEOUT_SECONDS ))
  recovered=""
  saw_offline=false
  while [ "$(date +%s)" -lt "$deadline" ]; do
    health=$(device_health "$device")
    case "$health" in
      OFFLINE|SUSPECTED|RECOVERING) saw_offline=true ;;
      ONLINE)
        # ONLINE before the gateway ever saw it leave means the poll missed
        # the whole transition. Recorded rather than counted as a recovery:
        # this iteration measured nothing.
        if [ "$saw_offline" = true ]; then
          recovered=$(now_ms)
          break
        fi
        ;;
    esac
    sleep 0.25
  done

  if [ -n "$recovered" ]; then
    echo "back in $(( recovered - injected )) ms (runner clock)"
    printf '{"iteration":%d,"deviceId":"%s","pod":"%s","injectedAtMillis":%d,"observedOnlineAtMillis":%d,"observedMillis":%d,"recovered":true}\n' \
      "$i" "$device" "$pod" "$injected" "$recovered" "$(( recovered - injected ))" \
      >> "$RAW/iterations.jsonl"
  else
    echo "NOT RECOVERED within ${RECOVERY_TIMEOUT_SECONDS}s"
    printf '{"iteration":%d,"deviceId":"%s","pod":"%s","injectedAtMillis":%d,"recovered":false,"sawOffline":%s}\n' \
      "$i" "$device" "$pod" "$injected" "$saw_offline" >> "$RAW/iterations.jsonl"
    failures=$((failures + 1))
  fi

  sleep "$COOLDOWN_SECONDS"
done

say "collecting"
# The last recovery may still be in flight when the loop ends.
sleep 5
cleanup
trap - EXIT

records=$(wc -l < "$RAW/recovery.jsonl" | tr -d ' ')
echo "  device.recovery records captured: $records"
echo "  iterations that did not recover : $failures"

say "summary"
python3 "$HERE/summarise.py" "$RAW"

if [ "$failures" -gt 0 ]; then
  echo
  echo "run recorded with $failures failed iteration(s): $RAW"
  exit 1
fi
echo
echo "run recorded: $RAW"

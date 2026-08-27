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
STARTED_EPOCH=$(date +%s)
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

# The gateway's HTTP API, which is the only thing the recovery poll uses and
# the only thing preflight did not check. It reaches the cluster through a
# kind port mapping, so a cluster created before that mapping existed answers
# nothing — and every iteration would then time out and be recorded as a
# device that failed to recover. Twenty minutes producing a 0% success rate
# that is a property of the apparatus, not of the system under test.
GATEWAY_URL="http://127.0.0.1:18081"
probe=$(curl -fsS --max-time 5 "$GATEWAY_URL/devices/${DEVICE_IDS%% *}" 2>/dev/null || true)
[ -n "$probe" ] || die "cannot reach the gateway at $GATEWAY_URL — the recovery poll reads it, so a run without it would record every iteration as a failure"
echo "  gateway API reachable at $GATEWAY_URL"

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

# Quoted heredoc, and every value passed through the environment.
#
# The previous version interpolated shell variables into the Python source,
# which means the shell also expanded everything else it recognised: a pair of
# backticks in a comment became command substitution, and kubectl's help text
# was spliced into the middle of the program. Shell variables and program text
# should not share a quoting context.
RUN_ID="$RUN_ID" EXPERIMENT_ID="$EXPERIMENT_ID" DEVICE_COUNT="$DEVICE_COUNT" \
DEVICE_IDS="$DEVICE_IDS" FAILURE_MODE="$FAILURE_MODE" ITERATIONS="$ITERATIONS" \
COOLDOWN_SECONDS="$COOLDOWN_SECONDS" RECOVERY_TIMEOUT_SECONDS="$RECOVERY_TIMEOUT_SECONDS" \
KUBE_CONTEXT="kind-$CLUSTER" KUBE_NAMESPACE="$NAMESPACE" \
python3 - "$RAW/metadata.json" <<'METADATA'
import json, os, platform, subprocess, sys

raw = os.path.dirname(sys.argv[1])
kubectl = ["kubectl", "--context", os.environ["KUBE_CONTEXT"],
           "-n", os.environ["KUBE_NAMESPACE"]]


def sh(*cmd):
    """Run a command and return what it said, on either stream.

    Both streams, because some tools report their version on stderr.
    """
    try:
        done = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        return (done.stdout + done.stderr).strip()
    except Exception:
        return ""


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


def kubernetes_version():
    try:
        return json.loads(sh(*kubectl, "version", "-o", "json")).get(
            "serverVersion", {}).get("gitVersion")
    except (json.JSONDecodeError, AttributeError):
        return None


def container_jvm():
    """The JVM the system under test actually runs.

    Read from the processes' own startup banners, not from the host. The host
    JVM is irrelevant to a run whose devices, gateway and operator all execute
    inside containers built on eclipse-temurin -- and on this machine the java
    on PATH is a GraalVM, which ADR-002 bans outright because its escape
    analysis erases the allocation differences Pillar A measures. Recording it
    as "the JVM version" would have been worse than recording nothing.
    """
    for name in ("gateway-config.txt", "device-config.txt", "operator-config.txt"):
        for line in read_lines(name):
            if line.lower().startswith("jvm"):
                return line.split(":", 1)[1].strip()
    return None


def images():
    out = sh(*kubectl, "get", "pods", "-o",
             "jsonpath={range .items[*]}{.metadata.name}{'\t'}"
             "{.spec.containers[0].image}{'\n'}{end}")
    return dict(line.split("\t", 1) for line in out.splitlines() if "\t" in line)


configmaps = configmap_data()
device_ids = os.environ["DEVICE_IDS"].split()

metadata = {
    "runId": os.environ["RUN_ID"],
    "experimentId": os.environ["EXPERIMENT_ID"],
    "pillar": "B - automated failure detection and recovery",
    "startedAtUtc": sh("date", "-u", "+%Y-%m-%dT%H:%M:%SZ"),
    "baseline": "experiments/environment-baseline.md",
    "machine": {
        "os": platform.platform(),
        "arch": platform.machine(),
        "cpuCount": os.cpu_count(),
        "memoryBytes": int(sh("sysctl", "-n", "hw.memsize") or 0),
    },
    "toolchain": {
        # The one that matters: what the system under test runs.
        "jvm": container_jvm(),
        "jvmSource": "startup banner of the containerised processes",
        "kind": sh("kind", "version"),
        "kubernetesServer": kubernetes_version(),
        "docker": sh("docker", "--version"),
        # Recorded separately and labelled, because it is not the runtime under
        # measurement and on this machine it is not even a permitted one.
        "hostJavaOnPath": sh("java", "-version").splitlines()[:1],
    },
    "fleet": {
        "model": "one device per pod (bare Pods, restartPolicy: Never - ADR-010)",
        "deviceCount": int(os.environ["DEVICE_COUNT"]),
        "deviceIds": device_ids,
        "tickIntervalMs": configmaps.get("fleet-config", {}).get("TICK_INTERVAL_MS"),
        "variant": configmaps.get("fleet-device-config", {}).get("FLEET_VARIANT"),
        "heapLimit": configmaps.get("fleet-device-config", {}).get("JAVA_OPTS"),
        "sink": configmaps.get("fleet-device-config", {}).get("FLEET_SINK"),
        "devicesPerPod": configmaps.get("fleet-device-config", {}).get("FLEET_DEVICE_COUNT"),
        "gatewayStartupConfig": read_lines("gateway-config.txt"),
        "deviceStartupConfig": read_lines("device-config.txt"),
        "operatorStartupConfig": read_lines("operator-config.txt"),
    },
    "experiment": {
        "failureMode": os.environ["FAILURE_MODE"],
        "iterations": int(os.environ["ITERATIONS"]),
        "cooldownSeconds": int(os.environ["COOLDOWN_SECONDS"]),
        "recoveryTimeoutSeconds": int(os.environ["RECOVERY_TIMEOUT_SECONDS"]),
        "pollSleepSeconds": 0.25,
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
    f.write("\n")
print("  wrote", sys.argv[1])
METADATA

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
#
# Parsed with grep rather than python3. health is a bare enum in a flat
# object, so a regex is enough — and a python3 start per poll cost about
# 60 ms, which made the loop poll at roughly 3.2 Hz while the writeup claimed
# 4. The measurement is a cross-check of a millisecond-scale number; its own
# sampling rate should not be dominated by interpreter startup.
device_health() {
  local body
  body=$(curl -fsS --max-time 2 "http://127.0.0.1:18081/devices/$1" 2>/dev/null) || {
    echo "UNREACHABLE"
    return
  }
  echo "$body" | grep -o '"health":"[A-Z]*"' | cut -d'"' -f4 | head -1
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

  # Guarded, like the missing-pod branch above. Under set -e an unguarded
  # delete would exit the script on a transient API error, leaving a partial
  # raw directory with no summary — against this script's own rule that a run
  # that failed is still a run.
  injected=$(now_ms)
  if ! "${KUBECTL[@]}" delete pod "$pod" --grace-period=0 --force >/dev/null 2>&1; then
    echo "  [$i/$ITERATIONS] could not delete $pod; recording as a failure"
    printf '{"iteration":%d,"deviceId":"%s","pod":"%s","injectedAtMillis":%d,"recovered":false,"error":"delete failed"}\n' \
      "$i" "$device" "$pod" "$injected" >> "$RAW/iterations.jsonl"
    failures=$((failures + 1))
    sleep "$COOLDOWN_SECONDS"
    continue
  fi
  printf '  [%d/%d] killed %s (%s) ... ' "$i" "$ITERATIONS" "$pod" "$device"

  deadline=$(( $(date +%s) + RECOVERY_TIMEOUT_SECONDS ))
  recovered=""
  saw_offline=false
  poll_errors=0
  polls=0
  while [ "$(date +%s)" -lt "$deadline" ]; do
    health=$(device_health "$device")
    polls=$((polls + 1))
    case "$health" in
      OFFLINE|SUSPECTED|RECOVERING) saw_offline=true ;;
      ONLINE)
        # ONLINE before the gateway ever saw it leave means the poll missed
        # the whole transition. Recorded rather than counted as a recovery:
        # this iteration measured nothing. The summariser reconciles that
        # against the system's own records, so the case shows up as a
        # disagreement rather than as a device that failed to recover.
        if [ "$saw_offline" = true ]; then
          recovered=$(now_ms)
          break
        fi
        ;;
      *)
        # UNREACHABLE, or a body that did not parse. Counted, because an
        # apparatus failure recorded as a device failure would be attributed
        # to the system under test.
        poll_errors=$((poll_errors + 1))
        ;;
    esac
    sleep 0.25
  done

  if [ -n "$recovered" ]; then
    echo "back in $(( recovered - injected )) ms (runner clock)"
    printf '{"iteration":%d,"deviceId":"%s","pod":"%s","injectedAtMillis":%d,"observedOnlineAtMillis":%d,"observedMillis":%d,"recovered":true,"polls":%d,"pollErrors":%d}\n' \
      "$i" "$device" "$pod" "$injected" "$recovered" "$(( recovered - injected ))" \
      "$polls" "$poll_errors" >> "$RAW/iterations.jsonl"
  else
    echo "NOT RECOVERED within ${RECOVERY_TIMEOUT_SECONDS}s"
    printf '{"iteration":%d,"deviceId":"%s","pod":"%s","injectedAtMillis":%d,"recovered":false,"sawOffline":%s,"polls":%d,"pollErrors":%d}\n' \
      "$i" "$device" "$pod" "$injected" "$saw_offline" "$polls" "$poll_errors" \
      >> "$RAW/iterations.jsonl"
    failures=$((failures + 1))
  fi

  if ! kill -0 "$CONSUMER_PID" 2>/dev/null; then
    echo "  WARNING: the device.recovery consumer has died; the remaining"
    echo "           iterations will not appear in recovery.jsonl"
  fi

  sleep "$COOLDOWN_SECONDS"
done

say "collecting"
# The last recovery may still be in flight when the loop ends.
sleep 5
cleanup
trap - EXIT

# The contract lists "experiment duration" among the fields every run must
# record. metadata.json is written before the first failure is injected, so
# the elapsed reality has to be added once it exists — the configured
# iteration count and cooldown describe the intended shape, not what happened.
python3 - "$RAW/metadata.json" "$STARTED_EPOCH" <<'DURATION'
import json, subprocess, sys, time

path, started = sys.argv[1], int(sys.argv[2])
with open(path) as f:
    metadata = json.load(f)
metadata["finishedAtUtc"] = subprocess.run(
    ["date", "-u", "+%Y-%m-%dT%H:%M:%SZ"],
    capture_output=True, text=True).stdout.strip()
metadata["durationSeconds"] = int(time.time()) - started
with open(path, "w") as f:
    json.dump(metadata, f, indent=2)
    f.write("\n")
print("  duration recorded: %d s" % metadata["durationSeconds"])
DURATION

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

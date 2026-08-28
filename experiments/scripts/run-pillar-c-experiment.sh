#!/usr/bin/env bash
#
# Pillar C: what the pipeline costs as the fleet grows.
#
# Runs the real path — fleet -> Mosquitto -> gateway — at 10, 25 and 50
# devices, and records what each end cost and how long the gateway took to
# notice devices that stopped heartbeating.
#
# What this does not do is measure recovery latency against device count. That
# needs one device per pod, and a per-pod JVM's baseline against this host's
# 8 GB would produce a curve describing the laptop rather than the system
# (ADR-013 scoped Phase 11 to three devices for the same reason). The omission
# is named in the writeup rather than left for a reader to notice.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

# An alternative config may be passed, which is how the apparatus gets
# rehearsed on a short run before a real one is taken. The path is recorded in
# metadata.json, so a result always says which settings produced it.
CONFIG="${1:-experiments/configs/c1-fleet-scalability.env}"
[ -f "$CONFIG" ] || [ -f "$ROOT/$CONFIG" ] || { echo "no config at $CONFIG" >&2; exit 1; }
case "$CONFIG" in
  /*) CONFIG_PATH="$CONFIG" ;;
  *) CONFIG_PATH="$ROOT/$CONFIG" ;;
esac
# shellcheck source=/dev/null
. "$CONFIG_PATH"

say() { printf '\n=== %s\n' "$1"; }
die() { echo "error: $1" >&2; exit 1; }

# --- preflight ------------------------------------------------------------
say "checking the JVM"
[ -n "${JAVA_HOME:-}" ] || die "JAVA_HOME is not set. This experiment must not run
       on whatever java is on PATH — on this machine that is a GraalVM, which
       ADR-002 excludes. Set it to the pinned JDK:
       export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
JAVA="$JAVA_HOME/bin/java"
[ -x "$JAVA" ] || die "no java at $JAVA"
JAVA_VERSION="$("$JAVA" -version 2>&1)"
case "$JAVA_VERSION" in
  *GraalVM*|*jvmci*) die "that JAVA_HOME is a GraalVM, which ADR-002 excludes" ;;
esac
echo "  $(echo "$JAVA_VERSION" | head -1)"

lsof -nP -iTCP:"$GATEWAY_PORT" -sTCP:LISTEN >/dev/null 2>&1 \
  && die "port $GATEWAY_PORT is already in use; this run needs it for its own gateway"

say "rebuilding so the measured classes match this commit"
mvn -o -B -q -pl common,edge-device,gateway -am package -DskipTests >/dev/null
CLASSPATH="gateway/target/classes:edge-device/target/classes:common/target/classes"
CLASSPATH="$CLASSPATH:$(find gateway/target/dependency edge-device/target/dependency \
  -name '*.jar' 2>/dev/null | tr '\n' ':')"

say "starting the broker"
docker compose up -d --wait broker >/dev/null 2>&1 \
  || die "could not start the compose broker"
BROKER_URL="tcp://127.0.0.1:1883"

RUN_ID="${EXPERIMENT_ID}-$(date -u +%Y%m%dT%H%M%SZ)"
RAW="experiments/results/raw/$RUN_ID"
mkdir -p "$RAW"
WORK="$(mktemp -d)"

cleanup() {
  [ -n "${GW_PID:-}" ] && kill "$GW_PID" 2>/dev/null || true
  # Killing the `/usr/bin/time` wrapper leaves the JVM it was timing running,
  # and an orphaned gateway holds the port so the next run cannot start. The
  # first rehearsal of this script did exactly that. Anything still listening
  # on our port when we exit is ours, so it goes too.
  lsof -t -nP -iTCP:"$GATEWAY_PORT" -sTCP:LISTEN 2>/dev/null \
    | xargs -r kill 2>/dev/null || true
  docker compose stop broker >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

GIT_COMMIT="$(git rev-parse HEAD)"
GIT_DIRTY="$(git status --porcelain | wc -l | tr -d ' ')"

say "run $RUN_ID"
echo "  device counts: $DEVICE_COUNTS, $REPETITIONS repetitions each"
echo "  ${PUBLISH_INTERVAL_MS}ms tick, ${RUN_DURATION_SECONDS}s per run, $VARIANT variant"
echo "  fleet -Xmx$HEAP_CAP, gateway -Xmx$GATEWAY_HEAP_CAP"

# --- metadata -------------------------------------------------------------
# Captured before the first run so the elapsed time is the experiment's.
RUN_STARTED_EPOCH=$(date +%s)

RUN_ID="$RUN_ID" EXPERIMENT_ID="$EXPERIMENT_ID" DEVICE_COUNTS="$DEVICE_COUNTS" \
REPETITIONS="$REPETITIONS" PUBLISH_INTERVAL_MS="$PUBLISH_INTERVAL_MS" \
RUN_DURATION_SECONDS="$RUN_DURATION_SECONDS" VARIANT="$VARIANT" \
HEAP_CAP="$HEAP_CAP" GATEWAY_HEAP_CAP="$GATEWAY_HEAP_CAP" \
FAILURE_MODE="$FAILURE_MODE" FAIL_AFTER_READINGS="$FAIL_AFTER_READINGS" \
SUSPECT_AFTER_MISSES="$SUSPECT_AFTER_MISSES" OFFLINE_AFTER_MISSES="$OFFLINE_AFTER_MISSES" \
SEED="$SEED" BROKER_URL="$BROKER_URL" JAVA_VERSION="$JAVA_VERSION" \
JAVA_HOME="$JAVA_HOME" GIT_COMMIT="$GIT_COMMIT" GIT_DIRTY="$GIT_DIRTY" \
CONFIG="$CONFIG" \
python3 - "$RAW/metadata.json" <<'METADATA'
import json, os, platform, subprocess, sys

env = os.environ
out = sys.argv[1]


def sh(*cmd):
    """What a command said, on either stream — `java -version` uses stderr."""
    try:
        done = subprocess.run(cmd, capture_output=True, text=True, timeout=20)
        return (done.stdout or done.stderr).strip()
    except (OSError, subprocess.SubprocessError):
        return ""


metadata = {
    "runId": env["RUN_ID"],
    "experimentId": env["EXPERIMENT_ID"],
    "pillar": "C - fleet scalability against device count",
    "scope": {
        "measured": [
            "throughput against device count",
            "fleet-side CPU, resident memory and GC against device count",
            "gateway-side CPU and resident memory against device count",
            "heartbeat-detection latency against device count",
        ],
        "notMeasured": {
            "recoveryLatencyAgainstDeviceCount":
                "needs one device per pod; a per-pod JVM costs ~64 MB of "
                "baseline and this host has 8 GB with a Docker VM taking "
                "half, so the curve would describe the machine. Phase 11 was "
                "scoped to three devices for the same reason (ADR-013).",
        },
    },
    "config": env["CONFIG"],
    "startedAt": sh("date", "-u", "+%Y-%m-%dT%H:%M:%SZ"),
    "machine": {
        "os": platform.platform(),
        "arch": platform.machine(),
        "cpuModel": sh("sysctl", "-n", "machdep.cpu.brand_string"),
        "cpuCount": os.cpu_count(),
        "memoryBytes": sh("sysctl", "-n", "hw.memsize"),
    },
    "toolchain": {
        "java": env["JAVA_VERSION"],
        "javaHome": env["JAVA_HOME"],
        "maven": sh("mvn", "-v").splitlines()[0] if sh("mvn", "-v") else "",
    },
    "source": {
        "commit": env["GIT_COMMIT"],
        "uncommittedFiles": int(env["GIT_DIRTY"]),
    },
    "parameters": {
        "deviceCounts": [int(c) for c in env["DEVICE_COUNTS"].split()],
        "repetitions": int(env["REPETITIONS"]),
        "publishIntervalMillis": int(env["PUBLISH_INTERVAL_MS"]),
        "runDurationSeconds": int(env["RUN_DURATION_SECONDS"]),
        "variant": env["VARIANT"],
        "fleetHeapCap": env["HEAP_CAP"],
        "gatewayHeapCap": env["GATEWAY_HEAP_CAP"],
        "failureMode": env["FAILURE_MODE"],
        "failAfterReadings": int(env["FAIL_AFTER_READINGS"]),
        "suspectAfterMisses": int(env["SUSPECT_AFTER_MISSES"]),
        "offlineAfterMisses": int(env["OFFLINE_AFTER_MISSES"]),
        "seed": int(env["SEED"]),
    },
    "mqtt": {
        "broker": env["BROKER_URL"],
        "image": "eclipse-mosquitto, digest-pinned in docker-compose.yml",
        "qos": "telemetry and heartbeats at QoS 0 (ADR-004)",
    },
    "notApplicable": {
        "kafka": "GATEWAY_KAFKA_ENABLED is off; nothing here reaches Kafka (ADR-009)",
        "kubernetes": "fleet and gateway run as host JVMs, not on a cluster",
    },
}
with open(out, "w") as handle:
    json.dump(metadata, handle, indent=2, sort_keys=True)
    handle.write("\n")
print("  metadata recorded")
METADATA

# --- one run --------------------------------------------------------------
RUNS="$RAW/runs.jsonl"
: > "$RUNS"
failures=0
total=0

record_run() {
  count=$1
  rep=$2
  tag="n${count}-rep${rep}"
  store="$WORK/store-$tag"
  mkdir -p "$store"

  printf '  %-3s devices  rep %d/%d ... ' "$count" "$rep" "$REPETITIONS"

  # The gateway first, timed like the fleet. Its own JVM, its own cap.
  GATEWAY_HTTP_PORT="$GATEWAY_PORT" \
  GATEWAY_CLIENT_ID="gw-c1-$tag-$$" \
  GATEWAY_HEARTBEAT_INTERVAL_MS="$PUBLISH_INTERVAL_MS" \
  GATEWAY_SUSPECT_AFTER_MISSES="$SUSPECT_AFTER_MISSES" \
  GATEWAY_OFFLINE_AFTER_MISSES="$OFFLINE_AFTER_MISSES" \
  GATEWAY_STORE_PATH="$store/fleet" \
  GATEWAY_RUN_DURATION_SECONDS="$((RUN_DURATION_SECONDS + 15))" \
  MQTT_BROKER_URL="$BROKER_URL" \
    /usr/bin/time -l "$JAVA" "-Xmx$GATEWAY_HEAP_CAP" \
      -cp "$CLASSPATH" io.fleet.gateway.Main \
      > "$RAW/$tag.gateway.out" 2> "$RAW/$tag.gateway.time" &
  GW_PID=$!

  ready=false
  for _ in $(seq 1 60); do
    if curl -fsS --max-time 2 "http://127.0.0.1:$GATEWAY_PORT/ready" >/dev/null 2>&1; then
      ready=true
      break
    fi
    sleep 0.5
  done
  if [ "$ready" != true ]; then
    kill "$GW_PID" 2>/dev/null || true
    wait "$GW_PID" 2>/dev/null || true
    GW_PID=""
    failures=$((failures + 1))
    echo "FAILED (gateway never became ready)"
    return
  fi

  # The fleet, in the background so the device records can be sampled while
  # it is still connected. That timing is not incidental: when the fleet
  # exits, every device's Last Will fires, and a will confirming the death of
  # a device already OFFLINE restarts its offline clock (ADR-006, amended).
  # Sampling afterwards would therefore measure the teardown, not the
  # detection this run exists to time.
  set +e
  FLEET_VARIANT="$VARIANT" \
  FLEET_DEVICE_COUNT="$count" \
  FLEET_PUBLISH_INTERVAL_MS="$PUBLISH_INTERVAL_MS" \
  FLEET_RUN_DURATION_SECONDS="$RUN_DURATION_SECONDS" \
  FLEET_SINK=mqtt \
  FLEET_FAILURE_MODE="$FAILURE_MODE" \
  FLEET_FAIL_AFTER="$FAIL_AFTER_READINGS" \
  FLEET_SEED="$SEED" \
  MQTT_BROKER_URL="$BROKER_URL" \
  MQTT_CLIENT_ID_PREFIX="c1-$tag" \
    /usr/bin/time -l "$JAVA" "-Xmx$HEAP_CAP" \
      "-Xlog:gc:file=$RAW/$tag.fleet.gc" \
      -cp "$CLASSPATH" io.fleet.edge.Main \
      > "$RAW/$tag.fleet.out" 2> "$RAW/$tag.fleet.time" &
  FLEET_PID=$!

  # Five seconds before the fleet stops: detection has long since happened
  # (heartbeats stop at reading FAIL_AFTER_READINGS, and OFFLINE follows
  # OFFLINE_AFTER_MISSES ticks later) and every device is still connected.
  sleep "$((RUN_DURATION_SECONDS - 5))"
  curl -fsS --max-time 5 "http://127.0.0.1:$GATEWAY_PORT/health" \
    > "$RAW/$tag.health.json" 2>/dev/null || true
  : > "$RAW/$tag.devices.jsonl"
  for i in $(seq 1 "$count"); do
    id=$(printf 'device-%03d' "$i")
    curl -fsS --max-time 3 "http://127.0.0.1:$GATEWAY_PORT/devices/$id" \
      >> "$RAW/$tag.devices.jsonl" 2>/dev/null || true
    echo >> "$RAW/$tag.devices.jsonl"
  done

  wait "$FLEET_PID"
  rc=$?

  # A second sample, after the fleet has stopped and the gateway has had time
  # to drain whatever was still in flight. Telemetry is QoS 0 (ADR-004), so a
  # shortfall against what the fleet published is either loss or lag, and the
  # mid-run sample alone cannot tell them apart: a gateway that is merely
  # behind looks exactly like one that dropped messages. If this figure
  # catches up to the fleet's count it was lag; if it does not, the difference
  # was lost on the wire.
  sleep 8
  curl -fsS --max-time 5 "http://127.0.0.1:$GATEWAY_PORT/health" \
    > "$RAW/$tag.health-final.json" 2>/dev/null || true
  set -e

  # Waited out rather than killed. `/usr/bin/time` writes its report when the
  # process it is timing exits; killing the wrapper kills it first, and the
  # gateway's CPU and resident set are then simply absent — which is what the
  # first rehearsal of this script produced. The gateway is given its own run
  # duration instead and allowed to finish.
  wait "$GW_PID" 2>/dev/null || true
  GW_PID=""

  total=$((total + 1))
  if [ "$rc" -ne 0 ]; then
    failures=$((failures + 1))
    echo "FAILED (fleet exit $rc)"
  else
    echo "ok"
  fi

  RUN_TAG="$tag" DEVICE_COUNT="$count" REP="$rep" EXIT_CODE="$rc" \
  PUBLISH_INTERVAL_MS="$PUBLISH_INTERVAL_MS" RAW_DIR="$RAW" \
  python3 "$ROOT/experiments/scripts/parse_pillar_c_run.py" >> "$RUNS"
}

say "running"
for rep in $(seq 1 "$REPETITIONS"); do
  order="$DEVICE_COUNTS"
  # The middle repetition runs the counts in reverse, so the largest fleet is
  # not always the one meeting a machine that has been busy for two minutes.
  if [ "$((rep % 2))" -eq 0 ]; then
    order="$(echo "$DEVICE_COUNTS" | tr ' ' '\n' | sort -rn | tr '\n' ' ')"
  fi
  for count in $order; do
    record_run "$count" "$rep"
  done
done

say "recording the elapsed time"
RUN_STARTED_EPOCH="$RUN_STARTED_EPOCH" python3 - "$RAW/metadata.json" <<'COMPLETION'
import json, os, subprocess, sys

path = sys.argv[1]
with open(path) as handle:
    metadata = json.load(handle)
ended = subprocess.run(["date", "-u", "+%Y-%m-%dT%H:%M:%SZ"],
                       capture_output=True, text=True).stdout.strip()
metadata["completedAt"] = ended
metadata["durationSeconds"] = int(
    subprocess.run(["date", "+%s"], capture_output=True, text=True).stdout.strip()
) - int(os.environ["RUN_STARTED_EPOCH"])
with open(path, "w") as handle:
    json.dump(metadata, handle, indent=2, sort_keys=True)
    handle.write("\n")
print(f"  {metadata['durationSeconds']}s, ended {ended}")
COMPLETION

say "summarising"
python3 "$ROOT/experiments/scripts/summarise-pillar-c.py" "$RAW"

if [ "$failures" -ne 0 ]; then
  echo >&2
  echo "error: $failures of $total runs did not complete. The run is recorded" >&2
  echo "       anyway — a curve that quietly drops the points that failed is" >&2
  echo "       not a curve." >&2
  exit 1
fi

echo
echo "raw:       experiments/results/raw/$RUN_ID/"
echo "processed: experiments/results/processed/$RUN_ID*"

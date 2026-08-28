#!/usr/bin/env bash
#
# Pillar A: constrained vs. naive Java under an identical heap cap.
#
# Runs both variants alternately under the same -Xmx, the same seed, the same
# device count and the same tick, and records what each cost to do the same
# work. Two independent views, kept apart on purpose and never merged:
#
#   internal  the JVM's own account of itself, printed by the run summary —
#             GC collections, GC time, heap in use, readings, throughput.
#   external  /usr/bin/time's account of the process — wall clock, user and
#             system CPU, maximum resident set size.
#
# The external view includes JVM startup, the JIT, and the classes the
# internal view never sees. It is not a better or worse measurement; it is a
# different one, and a disagreement between what the JVM says it used and what
# the operating system says it used is worth being able to see (ADR-013).
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$PWD

say() { printf '\n=== %s\n' "$1"; }
die() { echo "error: $1" >&2; exit 1; }

CONFIG=${1:-experiments/configs/a1-constrained-vs-naive.env}
[ -f "$CONFIG" ] || die "no such config: $CONFIG"
# shellcheck source=/dev/null
. "$CONFIG"

: "${EXPERIMENT_ID:?config must set EXPERIMENT_ID}"
: "${HEAP_CAP:?config must set HEAP_CAP}"
: "${DEVICE_COUNT:?config must set DEVICE_COUNT}"
: "${PUBLISH_INTERVAL_MS:?config must set PUBLISH_INTERVAL_MS}"
: "${RUN_DURATION_SECONDS:?config must set RUN_DURATION_SECONDS}"
: "${REPETITIONS:?config must set REPETITIONS}"
: "${SINK:?config must set SINK}"
: "${FAILURE_MODE:?config must set FAILURE_MODE}"
: "${SEED:?config must set SEED}"

RUN_ID="${EXPERIMENT_ID}-$(date -u +%Y%m%dT%H%M%SZ)"
RAW="$ROOT/experiments/results/raw/$RUN_ID"

# --- the toolchain, which is part of the measurement ----------------------
say "checking the JVM"

JAVA_HOME=${JAVA_HOME:-}
[ -n "$JAVA_HOME" ] || die "JAVA_HOME is not set. This experiment must not run
       on whatever java is on PATH — on this machine that is a GraalVM, which
       ADR-002 excludes. Set it to the pinned JDK:
       export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"

JAVA="$JAVA_HOME/bin/java"
[ -x "$JAVA" ] || die "no java at $JAVA"

# -version writes to stderr. Phase 11 recorded an empty toolchain field for a
# whole run by reading stdout, and the run was discarded for it.
JAVA_VERSION=$("$JAVA" -version 2>&1)

# ADR-002 rejects GraalVM outright: its escape analysis can optimise away the
# very allocations this experiment exists to compare, which would produce a
# null result caused by the toolchain rather than by the code. The build's
# enforcer catches this for Maven; nothing else would catch it here, because
# this runs the classes directly.
case "$JAVA_VERSION" in
  *GraalVM*|*jvmci*)
    die "this is a GraalVM JVM, which ADR-002 excludes from Pillar A:
       its escape analysis can erase the allocation differences being
       measured, turning a real difference into a null result.
       $(echo "$JAVA_VERSION" | head -1)" ;;
esac
case "$JAVA_VERSION" in
  *'version "21'*) : ;;
  *) die "expected a Java 21 runtime (ADR-002), got: $(echo "$JAVA_VERSION" | head -1)" ;;
esac
echo "  $(echo "$JAVA_VERSION" | head -1)"

command -v /usr/bin/time >/dev/null 2>&1 || die "/usr/bin/time is needed for the external view"

# --- the bytecode, which is also part of the measurement ------------------
say "rebuilding so the measured classes match this commit"
# Not an optimisation. Measuring classes left over from an earlier edit, and
# recording the current commit beside them, is the kind of quiet mismatch that
# makes a committed result worthless.
mvn -o -B -q -pl edge-device -am package -DskipTests \
  || die "build failed — refusing to measure classes that may not match the source"

CLASSPATH="$ROOT/edge-device/target/classes:$ROOT/common/target/classes"
[ -d "$ROOT/edge-device/target/classes" ] || die "no classes at $CLASSPATH"

GIT_COMMIT=$(git rev-parse HEAD)
GIT_DIRTY=$(git status --porcelain | wc -l | tr -d ' ')

mkdir -p "$RAW"
say "run $RUN_ID"
echo "  $REPETITIONS repetitions of each variant, alternating"
echo "  -Xmx$HEAP_CAP, $DEVICE_COUNT devices, ${PUBLISH_INTERVAL_MS}ms tick, ${RUN_DURATION_SECONDS}s each"
total=$((REPETITIONS * 2))
echo "  about $(( total * (RUN_DURATION_SECONDS + 3) / 60 )) minutes"

# --- metadata -------------------------------------------------------------
# Captured before the first run, so the elapsed time written at the end is the
# whole experiment's rather than the summarising step's.
RUN_STARTED_EPOCH=$(date +%s)

# Quoted heredoc, every value passed through the environment. An unquoted one
# lets the shell expand anything it recognises inside what is meant to be
# program text — Phase 11 lost a run to a pair of backticks in a comment.
RUN_ID="$RUN_ID" EXPERIMENT_ID="$EXPERIMENT_ID" HEAP_CAP="$HEAP_CAP" \
DEVICE_COUNT="$DEVICE_COUNT" PUBLISH_INTERVAL_MS="$PUBLISH_INTERVAL_MS" \
RUN_DURATION_SECONDS="$RUN_DURATION_SECONDS" REPETITIONS="$REPETITIONS" \
SINK="$SINK" FAILURE_MODE="$FAILURE_MODE" SEED="$SEED" \
JAVA_VERSION="$JAVA_VERSION" JAVA_HOME="$JAVA_HOME" \
GIT_COMMIT="$GIT_COMMIT" GIT_DIRTY="$GIT_DIRTY" CONFIG="$CONFIG" \
python3 - "$RAW/metadata.json" <<'METADATA'
import json, os, platform, subprocess, sys

out = sys.argv[1]


def sh(*cmd):
    """Run a command and return what it said, on either stream."""
    try:
        done = subprocess.run(cmd, capture_output=True, text=True, timeout=20)
        return (done.stdout + done.stderr).strip()
    except Exception:
        return ""


env = os.environ
metadata = {
    "runId": env["RUN_ID"],
    "experimentId": env["EXPERIMENT_ID"],
    "pillar": "A - constrained vs naive Java under an identical heap cap",
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
        "heapCap": env["HEAP_CAP"],
        "deviceCount": int(env["DEVICE_COUNT"]),
        "publishIntervalMillis": int(env["PUBLISH_INTERVAL_MS"]),
        "runDurationSeconds": int(env["RUN_DURATION_SECONDS"]),
        "repetitionsPerVariant": int(env["REPETITIONS"]),
        "sink": env["SINK"],
        "failureMode": env["FAILURE_MODE"],
        "seed": int(env["SEED"]),
    },
    # Stated rather than omitted: a reader of the contract's required fields
    # should find out here that they do not apply, not by not finding them.
    "notApplicable": {
        "mqtt": "sink is COUNTING; no broker is involved (see the config)",
        "kafka": "not on this path; the simulator does not reach Kafka",
        "kubernetes": "the shared harness runs as one JVM on the host, not on a cluster",
    },
}
with open(out, "w") as handle:
    json.dump(metadata, handle, indent=2, sort_keys=True)
    handle.write("\n")
print("  metadata recorded")
METADATA

# --- the runs -------------------------------------------------------------
RUNS="$RAW/runs.jsonl"
: > "$RUNS"
failures=0

record_run() {
  variant=$1
  rep=$2
  tag="${variant}-${rep}"
  stdout_file="$RAW/$tag.out"
  time_file="$RAW/$tag.time"
  gc_file="$RAW/$tag.gc"

  printf '  %-12s rep %d/%d ... ' "$variant" "$rep" "$REPETITIONS"

  # `set +e` around the run: a variant that dies under the cap is a result,
  # not a reason to abandon the experiment. It is recorded and reported.
  set +e
  FLEET_VARIANT="$variant" \
  FLEET_DEVICE_COUNT="$DEVICE_COUNT" \
  FLEET_PUBLISH_INTERVAL_MS="$PUBLISH_INTERVAL_MS" \
  FLEET_RUN_DURATION_SECONDS="$RUN_DURATION_SECONDS" \
  FLEET_SINK="$SINK" \
  FLEET_FAILURE_MODE="$FAILURE_MODE" \
  FLEET_SEED="$SEED" \
    /usr/bin/time -l "$JAVA" \
      "-Xmx$HEAP_CAP" \
      "-Xlog:gc:file=$gc_file" \
      -cp "$CLASSPATH" io.fleet.edge.Main \
      > "$stdout_file" 2> "$time_file"
  rc=$?
  set -e

  if [ $rc -ne 0 ]; then
    failures=$((failures + 1))
    echo "FAILED (exit $rc)"
  else
    echo "ok"
  fi

  RUN_TAG="$tag" VARIANT="$variant" REP="$rep" EXIT_CODE="$rc" \
  STDOUT_FILE="$stdout_file" TIME_FILE="$time_file" GC_FILE="$gc_file" \
  python3 "$ROOT/experiments/scripts/parse_pillar_a_run.py" >> "$RUNS"
}

say "running"
for rep in $(seq 1 "$REPETITIONS"); do
  # Alternating, and the order swaps on odd repetitions so that neither
  # variant is always the one that runs into a colder machine.
  if [ $((rep % 2)) -eq 1 ]; then
    record_run constrained "$rep"
    record_run naive "$rep"
  else
    record_run naive "$rep"
    record_run constrained "$rep"
  fi
done

# --- summarise ------------------------------------------------------------
# The elapsed time of the whole experiment, written now rather than guessed
# later. metadata.json is created before the first run, so without this the
# record says when the experiment started and never when it ended — which is
# one of the two omissions that cost Phase 11 a run (ADR-013). Per-run wall
# time is in runs.jsonl either way; this is the figure the reproducibility
# contract asks for, and it is recorded rather than derived.
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
python3 "$ROOT/experiments/scripts/summarise-pillar-a.py" "$RAW"

if [ "$failures" -ne 0 ]; then
  echo >&2
  echo "error: $failures of $total runs did not complete. The run is recorded" >&2
  echo "       anyway — a comparison that quietly drops the runs that failed" >&2
  echo "       is not a comparison." >&2
  exit 1
fi

echo
echo "raw:       experiments/results/raw/$RUN_ID/"
echo "processed: experiments/results/processed/$RUN_ID*"

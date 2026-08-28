#!/usr/bin/env python3
"""Turn one Pillar C run's files into a single JSON line.

Reads paths from the environment, like the Pillar A parser and for the same
reason: nothing a filename contains can be read as a flag.

  RUN_TAG DEVICE_COUNT REP EXIT_CODE PUBLISH_INTERVAL_MS RAW_DIR

Four sources, kept separate in the output because they are four different
witnesses and a disagreement between them is worth seeing:

  fleet     the simulator's own run summary, plus what /usr/bin/time and the
            gc log say the fleet process cost
  gateway   what /usr/bin/time says the gateway process cost
  ingest    the gateway's own counters, sampled over HTTP while the fleet was
            still connected
  detection per-device: how long the gateway took to declare a device offline
            after its last heartbeat

A field that could not be read is absent rather than zero.
"""
import json
import os
import re
import sys

FLEET_SUMMARY = {
    "durationMillis": (r"^duration\s*:\s*(\d+) ms", int),
    "readingsPublished": (r"^readings published\s*:\s*(\d+)", int),
    "heartbeatsPublished": (r"^heartbeats sent\s*:\s*(\d+)", int),
    "throughputPerSecond": (r"^throughput\s*:\s*([\d.]+) readings/s", float),
    "sinkErrors": (r"^sink errors\s*:\s*(\d+)", int),
    "unexpectedErrors": (r"^unexpected errors\s*:\s*(\d+)", int),
    "gcCollections": (r"^gc collections\s*:\s*(\d+)", int),
    "gcTimeMillis": (r"^gc time\s*:\s*(\d+) ms", int),
}

INGEST_FIELDS = (
    "telemetryAccepted", "telemetryMalformed", "telemetryInvalid",
    "heartbeatsAccepted", "heartbeatsMalformed", "failuresDetected",
    "devicesKnown", "devicesReporting", "devicesOnline",
    "handlerErrors", "unroutableMessages", "connectionLosses",
)


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return ""


def extract(text, fields):
    found = {}
    for name, (pattern, cast) in fields.items():
        match = re.search(pattern, text, re.MULTILINE)
        if match:
            found[name] = cast(match.group(1))
    return found


def process_cost(text):
    """What `/usr/bin/time -l` says one process cost."""
    out = {}
    timing = re.search(r"^\s*([\d.]+) real\s+([\d.]+) user\s+([\d.]+) sys",
                       text, re.MULTILINE)
    if timing:
        out["wallSeconds"] = float(timing.group(1))
        out["cpuSeconds"] = round(float(timing.group(2)) + float(timing.group(3)), 6)
        out["userCpuSeconds"] = float(timing.group(2))
    rss = re.search(r"^\s*(\d+)\s+maximum resident set size\s*$", text, re.MULTILINE)
    if rss:
        out["maxResidentBytes"] = int(rss.group(1))
    return out


def detection(devices_text, interval_millis):
    """How long the gateway took to notice each device had stopped.

    Measured as the gateway's own `offlineSinceMillis - lastHeartbeatAtMillis`,
    which is its account of the interval and needs no polling. The sample is
    taken while the fleet is still connected, because a Last Will confirming
    the death of an already-offline device restarts that clock (ADR-006,
    amended) and would turn this into a measurement of teardown.

    Devices that were not OFFLINE at sample time are counted, not silently
    dropped: a run where detection did not happen must not look like a run
    where it happened quickly.
    """
    latencies = []
    not_offline = []
    unreadable = 0
    for line in devices_text.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            unreadable += 1
            continue
        device_id = record.get("deviceId", "?")
        if record.get("health") != "OFFLINE":
            not_offline.append(f"{device_id}={record.get('health')}")
            continue
        offline_since = record.get("offlineSinceMillis", 0)
        last_beat = record.get("lastHeartbeatAtMillis", 0)
        if offline_since > 0 and last_beat > 0:
            latencies.append(offline_since - last_beat)

    out = {
        "samples": len(latencies),
        "notOffline": len(not_offline),
        "unreadable": unreadable,
    }
    if not_offline:
        # Named, not just counted — which devices failed to be detected is the
        # first thing anyone would ask.
        out["notOfflineDetail"] = sorted(not_offline)[:10]
    if latencies:
        latencies.sort()
        out["minMillis"] = latencies[0]
        out["maxMillis"] = latencies[-1]
        out["medianMillis"] = latencies[len(latencies) // 2]
        out["meanMillis"] = round(sum(latencies) / len(latencies), 1)
        # The policy floor: OFFLINE cannot be declared before this many ticks
        # have been missed, so a latency below it would mean the policy was
        # not what the metadata says.
        out["policyFloorMillis"] = interval_millis * 4
    return out


def main():
    env = os.environ
    raw = env["RAW_DIR"]
    tag = env["RUN_TAG"]

    fleet_out = read(f"{raw}/{tag}.fleet.out")
    health_text = read(f"{raw}/{tag}.health.json")

    ingest = {}
    if health_text.strip():
        try:
            health = json.loads(health_text)
            ingest = {k: health[k] for k in INGEST_FIELDS if k in health}
            if "health" in health:
                ingest["healthCounts"] = health["health"]
        except json.JSONDecodeError:
            ingest = {}

    record = {
        "runTag": tag,
        "deviceCount": int(env["DEVICE_COUNT"]),
        "repetition": int(env["REP"]),
        "exitCode": int(env["EXIT_CODE"]),
        "fleet": {
            **extract(fleet_out, FLEET_SUMMARY),
            **{f"process_{k}": v
               for k, v in process_cost(read(f"{raw}/{tag}.fleet.time")).items()},
        },
        "gateway": process_cost(read(f"{raw}/{tag}.gateway.time")),
        "ingest": ingest,
        "detection": detection(read(f"{raw}/{tag}.devices.jsonl"),
                               int(env["PUBLISH_INTERVAL_MS"])),
    }
    if "readingsPublished" not in record["fleet"]:
        record["incomplete"] = "no fleet run summary"
    if not ingest:
        record.setdefault("incomplete", "no gateway health sample")

    json.dump(record, sys.stdout, sort_keys=True)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())

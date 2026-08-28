#!/usr/bin/env python3
"""Turn one Pillar A run's three files into a single JSON line.

Reads the paths from the environment rather than argv so the runner passes
values the same way it passes them to the metadata heredoc, and so nothing a
filename contains can be read as a flag.

  RUN_TAG VARIANT REP EXIT_CODE STDOUT_FILE TIME_FILE GC_FILE

Three sources, deliberately kept separate in the output:

  internal  the JVM's own account, from the run summary the simulator prints
  external  /usr/bin/time's account of the same process
  gc        the JVM's gc log, which corroborates the internal counters and
            carries the per-collection detail they flatten

A field that could not be read is absent rather than zero. A zero in a
resource column is a claim; a missing key is the absence of one, and the
summariser reports it as missing instead of averaging it in.
"""
import json
import os
import re
import sys

# "duration          : 10015 ms" — the simulator's own summary, one key per
# line, which is also what a human reads when they run it by hand.
SUMMARY_FIELDS = {
    "durationMillis": (r"^duration\s*:\s*(\d+) ms", int),
    "readingsPublished": (r"^readings published\s*:\s*(\d+)", int),
    "heartbeatsPublished": (r"^heartbeats sent\s*:\s*(\d+)", int),
    "payloadsDelivered": (r"^payloads delivered\s*:\s*(\d+)", int),
    "throughputPerSecond": (r"^throughput\s*:\s*([\d.]+) readings/s", float),
    "crashedDevices": (r"^crashed devices\s*:\s*(\d+)", int),
    "sinkErrors": (r"^sink errors\s*:\s*(\d+)", int),
    "unexpectedErrors": (r"^unexpected errors\s*:\s*(\d+)", int),
    "gcCollections": (r"^gc collections\s*:\s*(\d+)", int),
    "gcTimeMillis": (r"^gc time\s*:\s*(\d+) ms", int),
}

HEADER_FIELDS = {
    "variantReported": (r"^variant\s*:\s*(\S+)", str),
    "sink": (r"^sink\s*:\s*(\S+)", str),
    "devices": (r"^devices\s*:\s*(\d+)", int),
    "publishIntervalMillis": (r"^publish interval\s*:\s*(\d+) ms", int),
    "seed": (r"^seed\s*:\s*(\d+)", int),
    "maxHeap": (r"^max heap\s*:\s*(.+?)\s*$", str),
    "collectors": (r"^collectors\s*:\s*(.+?)\s*$", str),
}


def extract(text, fields):
    found = {}
    for name, (pattern, cast) in fields.items():
        match = re.search(pattern, text, re.MULTILINE)
        if match:
            found[name] = cast(match.group(1))
    return found


def bytes_field(text, label):
    """A `/usr/bin/time -l` counter, which is right-aligned before its name."""
    match = re.search(rf"^\s*(\d+)\s+{label}\s*$", text, re.MULTILINE)
    return int(match.group(1)) if match else None


def external(text):
    """What the operating system says the process cost."""
    out = {}
    timing = re.search(r"^\s*([\d.]+) real\s+([\d.]+) user\s+([\d.]+) sys", text,
                       re.MULTILINE)
    if timing:
        out["wallSeconds"] = float(timing.group(1))
        out["userCpuSeconds"] = float(timing.group(2))
        out["systemCpuSeconds"] = float(timing.group(3))
        out["cpuSeconds"] = round(float(timing.group(2)) + float(timing.group(3)), 6)
    rss = bytes_field(text, "maximum resident set size")
    if rss is not None:
        out["maxResidentBytes"] = rss
    faults = bytes_field(text, "page reclaims")
    if faults is not None:
        out["pageReclaims"] = faults
    return out


# "[2.347s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->1M(64M) 1.905ms"
GC_PAUSE = re.compile(
    r"^\[([\d.]+)s\].*?\bgc\b.*?GC\(\d+\).*?"
    r"(?P<before>\d+)M->(?P<after>\d+)M\((?P<total>\d+)M\)\s+(?P<pause>[\d.]+)ms",
    re.MULTILINE)


def gc_log(text):
    """The collector's own record, which the MXBean counters flatten."""
    pauses = list(GC_PAUSE.finditer(text))
    out = {"collections": len(pauses)}
    if pauses:
        out["pauseMillisTotal"] = round(sum(float(p.group("pause")) for p in pauses), 3)
        out["pauseMillisMax"] = round(max(float(p.group("pause")) for p in pauses), 3)
        # The largest heap seen immediately before a collection: the closest
        # thing to a high-water mark the gc log offers, and the reason a
        # variant that never collects reports none of this rather than zero.
        out["heapBeforeMaxMegabytes"] = max(int(p.group("before")) for p in pauses)
    collector = re.search(r"^\[[\d.]+s\]\[info\]\[gc\] Using (\S+)", text, re.MULTILINE)
    if collector:
        out["collector"] = collector.group(1)
    return out


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return ""


def main():
    env = os.environ
    stdout_text = read(env["STDOUT_FILE"])
    time_text = read(env["TIME_FILE"])
    gc_text = read(env["GC_FILE"])

    record = {
        "runTag": env["RUN_TAG"],
        "variant": env["VARIANT"],
        "repetition": int(env["REP"]),
        "exitCode": int(env["EXIT_CODE"]),
        "header": extract(stdout_text, HEADER_FIELDS),
        "internal": extract(stdout_text, SUMMARY_FIELDS),
        "external": external(time_text),
        "gc": gc_log(gc_text),
    }

    # A run whose summary is missing did not finish, whatever its exit code
    # said. Saying so here means the summariser does not have to guess.
    if "readingsPublished" not in record["internal"]:
        record["incomplete"] = "no run summary in stdout"

    json.dump(record, sys.stdout, sort_keys=True)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Fail when the test suite reported less than it should have.

`mvn verify` exits 0 when tests are skipped, and eleven of this project's
260 tests skip themselves when no MQTT broker is listening — the whole
MQTT wire path plus the heartbeat-detection integration tests. A CI run
whose broker never started would therefore be green while covering none of
Phase 2 or Phase 4. That is the same failure this project keeps designing
against: an apparatus that reports success while measuring nothing.

Three things are checked, none of which needs updating when a test is added:

  1. Every module with a `src/test/java` produced surefire reports. A module
     dropped from the reactor stops reporting rather than failing.
  2. No test was skipped. With a broker up, the suite skips nothing at all
     (measured), so zero is the honest threshold and any new skip has to be
     argued for here rather than appearing silently.
  3. No failures or errors, which `mvn` already enforces unless someone
     passes -Dmaven.test.failure.ignore.

Run it after `mvn verify`, from the repository root:

    python3 .github/scripts/assert-suite-complete.py
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def modules_with_tests():
    """Directories holding a Maven module that has tests to run.

    Found by looking for the tests themselves rather than by parsing the
    reactor, so a module deleted from pom.xml while its tests remain on
    disk is reported as missing instead of quietly not running.
    """
    found = []
    for tests in ROOT.glob("**/src/test/java"):
        if "target" in tests.parts:
            continue
        module = tests.parents[2]
        if (module / "pom.xml").exists() and any(tests.rglob("*.java")):
            found.append(module)
    return sorted(found)


def totals(reports):
    tests = skipped = failures = errors = 0
    skipped_by_class = {}
    for report in reports:
        root = ET.parse(report).getroot()
        tests += int(root.get("tests", 0))
        failures += int(root.get("failures", 0))
        errors += int(root.get("errors", 0))
        n = int(root.get("skipped", 0))
        skipped += n
        if n:
            skipped_by_class[root.get("name", report.name)] = (
                n, int(root.get("tests", 0)))
    return tests, skipped, failures, errors, skipped_by_class


def main():
    problems = []
    reports = []
    modules = modules_with_tests()

    if not modules:
        # The check found nothing to check. Reporting that as success would
        # make this script a no-op the moment the layout moves under it.
        print("FAIL: found no modules with tests — either the repository "
              "layout changed or this check is looking in the wrong place",
              file=sys.stderr)
        return 1

    for module in modules:
        found = sorted((module / "target" / "surefire-reports").glob("TEST-*.xml"))
        rel = module.relative_to(ROOT)
        if not found:
            problems.append(
                f"{rel} has tests but produced no surefire report — "
                f"it did not run")
        reports.extend(found)

    if not reports:
        # Distinct from "a module is missing": this is the case where nothing
        # ran at all, which every other check would pass vacuously.
        print("FAIL: no surefire reports anywhere — the suite did not run",
              file=sys.stderr)
        return 1

    tests, skipped, failures, errors, by_class = totals(reports)

    if skipped:
        detail = ", ".join(f"{cls} ({n}/{t})"
                           for cls, (n, t) in sorted(by_class.items()))
        problems.append(
            f"{skipped} of {tests} tests were skipped: {detail}. "
            f"The MQTT suites skip themselves when no broker is reachable at "
            f"MQTT_BROKER_URL, so this usually means the broker never started")
    if failures or errors:
        problems.append(f"{failures} failures and {errors} errors")

    if problems:
        print(f"FAIL: the suite ran {tests} tests but did not run complete:",
              file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    print(f"OK: {tests} tests, none skipped, across {len(modules)} modules")
    return 0


if __name__ == "__main__":
    sys.exit(main())

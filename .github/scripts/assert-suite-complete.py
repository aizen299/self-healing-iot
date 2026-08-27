#!/usr/bin/env python3
"""Fail when the test suite reported less than it should have.

`mvn verify` exits 0 when tests are skipped, and this project's MQTT suites
skip themselves when no broker is listening — the whole MQTT wire path plus
the heartbeat-detection integration tests. A CI run whose broker never
started would therefore be green while covering none of Phase 2 or Phase 4.
That is the same failure this project keeps designing against: an apparatus
that reports success while measuring nothing. See ADR-014, which records the
counts as they stood when the gate was written.

Four things are checked, none of which needs updating when a test is added:

  1. Every module with a `src/test/java` produced surefire reports. A module
     dropped from the reactor stops reporting rather than failing.
  2. No report belongs to a test class that no longer exists. Surefire never
     deletes reports, so without this a build that skipped `clean` counts a
     renamed or deleted class's last run as part of this one.
  3. No test was skipped. With a broker up the suite skips nothing at all, so
     zero is the honest threshold and any new skip has to be argued for here
     rather than appearing silently. The reason is read out of the report
     rather than guessed at, because the test that skipped already said why.
  4. No failures or errors, which `mvn` already enforces unless someone
     passes -Dmaven.test.failure.ignore.

Run it after `mvn verify`, from anywhere:

    python3 .github/scripts/assert-suite-complete.py

`--self-test` checks this script instead of the project, by building fixture
trees in a temporary directory and asserting each verdict above. It needs no
build and touches nothing outside the temporary directory.
"""
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def modules_with_tests(root):
    """Directories holding a Maven module that has tests to run.

    Found by looking for the tests themselves rather than by parsing the
    reactor, so a module deleted from pom.xml while its tests remain on
    disk is reported as missing instead of quietly not running.
    """
    found = []
    for tests in root.glob("**/src/test/java"):
        if "target" in tests.relative_to(root).parts:
            continue
        module = tests.parents[2]
        if (module / "pom.xml").exists() and any(tests.rglob("*.java")):
            found.append(module)
    return sorted(found)


def source_exists(module, suite_name):
    """Whether `suite_name` still has a source file under this module.

    Surefire names a suite for its top-level class, so a nested or
    parameterised class reports as `Outer$Nested` and the file to look for is
    the outer one.
    """
    top_level = suite_name.split("$", 1)[0]
    return (module / "src" / "test" / "java"
            / Path(*top_level.split("."))).with_suffix(".java").exists()


def skip_reasons(suite):
    """What each skipped test said about why, straight from the report.

    JUnit writes the assumption message into the <skipped> element — as an
    attribute when there is one, otherwise as the first line of the stack
    trace. Reading it beats inferring a cause: a skip that has nothing to do
    with the broker should not be reported as a broker problem.
    """
    reasons = []
    for case in suite.iter("testcase"):
        for skipped in case.iter("skipped"):
            text = skipped.get("message") or (skipped.text or "").strip()
            first = text.splitlines()[0] if text else ""
            for prefix in ("org.opentest4j.TestAbortedException:",
                           "Assumption failed:"):
                if first.startswith(prefix):
                    first = first[len(prefix):].strip()
            reasons.append((case.get("name", "?"), first or "no reason given"))
    return reasons


def evaluate(root):
    """Judge the surefire output under `root`.

    Returns (problems, tests_counted, module_count). An empty problems list
    means the suite ran complete.
    """
    problems = []
    modules = modules_with_tests(root)

    if not modules:
        # The check found nothing to check. Reporting that as success would
        # make this script a no-op the moment the layout moves under it.
        return (["found no modules with tests — either the repository layout "
                 "changed or this check is looking in the wrong place"], 0, 0)

    tests = failures = errors = 0
    skipped_detail = []
    reports_seen = 0

    for module in modules:
        rel = module.relative_to(root)
        reports = sorted((module / "target" / "surefire-reports")
                         .glob("TEST-*.xml"))
        if not reports:
            problems.append(f"{rel} has tests but produced no surefire report "
                            f"— it did not run")
            continue

        for report in reports:
            try:
                suite = ET.parse(report).getroot()
            except ET.ParseError as e:
                problems.append(f"{rel}: {report.name} is not readable XML "
                                f"({e}) — surefire may have died mid-write")
                continue

            name = suite.get("name", report.stem)
            if not source_exists(module, name):
                # Counting it would fold a previous run into this one.
                problems.append(
                    f"{rel}: {report.name} reports on {name}, which has no "
                    f"source file — a stale report from an earlier run. Run "
                    f"`mvn clean` (surefire never deletes reports)")
                continue

            reports_seen += 1
            tests += int(suite.get("tests", 0))
            failures += int(suite.get("failures", 0))
            errors += int(suite.get("errors", 0))
            for case, reason in skip_reasons(suite):
                skipped_detail.append(f"{name}.{case}: {reason}")

    if modules and not reports_seen and not problems:
        # Distinct from "a module is missing": nothing ran at all, which every
        # other check would pass vacuously.
        problems.append("no surefire reports anywhere — the suite did not run")

    if skipped_detail:
        problems.append(
            f"{len(skipped_detail)} of {tests} tests were skipped, and a "
            f"skipped test reports success:")
        problems.extend(f"    {line}" for line in sorted(skipped_detail))
    if failures or errors:
        problems.append(f"{failures} failures and {errors} errors")

    return (problems, tests, len(modules))


def report(root, out=sys.stdout, err=sys.stderr):
    problems, tests, modules = evaluate(root)
    if problems:
        print(f"FAIL: the suite ran {tests} tests but did not run complete:",
              file=err)
        for problem in problems:
            print(f"  - {problem}" if not problem.startswith("    ")
                  else problem, file=err)
        return 1
    print(f"OK: {tests} tests, none skipped, across {modules} modules",
          file=out)
    return 0


# --- self-test -----------------------------------------------------------
# The gate is the one thing in this pipeline whose failure mode is a green
# build, so a regression in it is invisible by construction. These fixtures
# are what make that regression loud instead.

SUITE = ('<?xml version="1.0" encoding="UTF-8"?>\n'
         '<testsuite name="{name}" tests="{tests}" errors="{errors}" '
         'skipped="{skipped}" failures="{failures}">{cases}</testsuite>\n')

SKIPPED_CASE = ('<testcase name="{case}"><skipped '
                'type="org.opentest4j.TestAbortedException">'
                'org.opentest4j.TestAbortedException: Assumption failed: '
                '{reason}\n\tat org.junit</skipped></testcase>')


def fixture(root, module="mod", cls="a.b.SomeTest", *, source=True,
            tests=2, skipped=0, failures=0, errors=0, reason="no broker",
            report_for=None):
    """A module with a pom, a test source, and one surefire report."""
    path = root / module
    (path / "src" / "test" / "java" / Path(*cls.split(".")[:-1])).mkdir(
        parents=True, exist_ok=True)
    (path / "pom.xml").write_text("<project/>")
    if source:
        (path / "src" / "test" / "java"
         / Path(*cls.split("."))).with_suffix(".java").write_text("class X{}")
    reported = report_for or cls
    if tests is not None:
        reports = path / "target" / "surefire-reports"
        reports.mkdir(parents=True, exist_ok=True)
        cases = "".join(SKIPPED_CASE.format(case=f"skipped{i}", reason=reason)
                        for i in range(skipped))
        (reports / f"TEST-{reported}.xml").write_text(SUITE.format(
            name=reported, tests=tests, errors=errors, skipped=skipped,
            failures=failures, cases=cases))
    return path


def self_test():
    cases = []

    def check(label, root, want_ok, want_in=None):
        problems, tests, _ = evaluate(root)
        ok = not problems
        joined = " | ".join(problems)
        good = (ok == want_ok) and (want_in is None or want_in in joined)
        cases.append((good, label,
                      "OK" if ok else joined[:160], tests))

    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)

        root = tmp / "healthy"
        fixture(root)
        check("a complete run passes", root, True)

        root = tmp / "skipped"
        fixture(root, skipped=1, reason="no MQTT broker at tcp://x:1883")
        check("a skipped test fails the gate", root, False,
              "no MQTT broker at tcp://x:1883")

        root = tmp / "reason-verbatim"
        fixture(root, skipped=1, reason="a reason with nothing to do with MQTT")
        check("the skip reason is read, not guessed", root, False,
              "a reason with nothing to do with MQTT")

        root = tmp / "no-report"
        fixture(root, tests=None)
        check("a module that did not run fails", root, False,
              "produced no surefire report")

        root = tmp / "stale"
        fixture(root, cls="a.b.LiveTest", report_for="a.b.DeletedTest")
        check("a stale report fails rather than counting", root, False,
              "stale report")

        root = tmp / "empty"
        root.mkdir()
        check("a layout with no modules fails", root, False,
              "found no modules")

        root = tmp / "failures"
        fixture(root, failures=1)
        check("a reported failure fails", root, False, "1 failures")

        root = tmp / "counting"
        fixture(root, module="one", cls="a.OneTest", tests=3)
        fixture(root, module="two", cls="a.TwoTest", tests=4)
        check("tests are summed across modules", root, True)
        if cases[-1][3] != 7:
            cases[-1] = (False, cases[-1][1], f"counted {cases[-1][3]}, not 7",
                         cases[-1][3])

    for good, label, detail, _ in cases:
        print(f"  {'ok  ' if good else 'FAIL'}  {label}"
              + ("" if good else f"  ({detail})"))
    failed = [c for c in cases if not c[0]]
    print(f"{len(cases) - len(failed)}/{len(cases)} self-tests passed")
    return 1 if failed else 0


if __name__ == "__main__":
    if "--self-test" in sys.argv[1:]:
        sys.exit(self_test())
    sys.exit(report(ROOT))

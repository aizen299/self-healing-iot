#!/usr/bin/env python3
"""Check the Argo CD manifests that kubeconform cannot.

`kubeconform` has no schema for `Application` or `AppProject`, so CI parses
them as YAML and learns nothing else. That leaves the mistakes most likely to
be made here entirely uncaught, and they fail at bootstrap time on a cluster
rather than in review:

  1. A `path` that does not exist in the repository. Argo reports
     `ComparisonError` and the Application never syncs — which is exactly what
     an induced typo produced while testing the bootstrap script.
  2. A `repoURL` that disagrees between manifests, so some components would
     reconcile from one repository and some from another.
  3. A `project` that is not the AppProject shipped beside them, which silently
     escapes the Pod blacklist that keeps GitOps off the device fleet
     (ADR-016).
  4. A managed path that contains a bare Pod. This is the boundary ADR-016
     exists to defend, and it should fail in review rather than at sync.
  5. A missing sync-wave on a component that needs one, or a `targetRevision`
     that is not `main` — a branch committed by accident would have the cluster
     tracking something that may be deleted after a merge.

Run from anywhere:

    python3 .github/scripts/assert-gitops-sane.py

`--self-test` checks this script against fixtures instead of the repository.
"""
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GITOPS = ROOT / "infrastructure" / "gitops"


def load(path):
    """Read the handful of fields this checks, without a YAML dependency.

    These manifests are flat, generated from one template, and checked into
    this repository — a real parser would be better, and PyYAML is not in the
    runner's guaranteed set. Anything this cannot read is reported rather than
    assumed to be fine.
    """
    text = path.read_text()
    fields = {}
    for key in ("kind", "name", "project", "repoURL", "targetRevision", "path"):
        match = re.search(rf"^\s*{key}:\s*(\S+)\s*$", text, re.MULTILINE)
        if match:
            fields[key] = match.group(1).strip('"\'')
    wave = re.search(r'sync-wave:\s*"(-?\d+)"', text)
    if wave:
        fields["wave"] = int(wave.group(1))
    return fields


def bare_pods_under(path, root):
    """Manifest files under `path` that declare a bare Pod."""
    directory = root / path
    if not directory.is_dir():
        return []
    found = []
    for manifest in sorted(directory.rglob("*.yaml")):
        if re.search(r"^kind:\s*Pod\s*$", manifest.read_text(), re.MULTILINE):
            found.append(manifest.relative_to(root))
    return found


def check(gitops=GITOPS, root=ROOT):
    problems = []

    project_file = gitops / "project.yaml"
    root_file = gitops / "root.yaml"
    app_files = sorted((gitops / "apps").glob("*.yaml"))

    for required in (project_file, root_file):
        if not required.exists():
            problems.append(f"{required.relative_to(root)} is missing")
    if not app_files:
        problems.append("infrastructure/gitops/apps holds no Applications")
    if problems:
        return problems

    project = load(project_file)
    project_name = project.get("name")
    repo_urls = {}

    # The AppProject's own repo declaration counts as one of the sources that
    # must agree; a sourceRepos that names a different repository than the
    # Applications makes every sync fail on a permission error.
    match = re.search(r"^\s*-\s*(https://\S+\.git)\s*$",
                      project_file.read_text(), re.MULTILINE)
    if match:
        repo_urls[str(project_file.relative_to(root))] = match.group(1)

    for manifest in [root_file] + app_files:
        rel = str(manifest.relative_to(root))
        fields = load(manifest)

        if fields.get("kind") != "Application":
            problems.append(f"{rel}: kind is {fields.get('kind')!r}, expected Application")
            continue

        for key in ("name", "project", "repoURL", "targetRevision", "path"):
            if key not in fields:
                problems.append(f"{rel}: no {key} — this check could not read it")

        if "repoURL" in fields:
            repo_urls[rel] = fields["repoURL"]

        if fields.get("project") != project_name:
            problems.append(
                f"{rel}: project is {fields.get('project')!r}, but the AppProject "
                f"beside it is {project_name!r} — an Application outside that "
                f"project escapes its Pod blacklist (ADR-016)")

        if fields.get("targetRevision") != "main":
            problems.append(
                f"{rel}: targetRevision is {fields.get('targetRevision')!r}. The "
                f"committed manifests track main; use bootstrap.sh --revision to "
                f"try a branch, so a deleted branch cannot strand the cluster")

        declared = fields.get("path")
        if declared and not (root / declared).is_dir():
            problems.append(
                f"{rel}: path {declared!r} is not a directory in this repository "
                f"— Argo would report ComparisonError and never sync")
        elif declared:
            for pod in bare_pods_under(declared, root):
                problems.append(
                    f"{rel}: path {declared!r} contains {pod}, which declares a "
                    f"bare Pod. The device fleet must stay outside GitOps "
                    f"management (ADR-016)")

        if manifest in app_files and "wave" not in fields:
            problems.append(
                f"{rel}: no argocd.argoproj.io/sync-wave. The namespace is "
                f"created by one Application and used by the others, so the "
                f"order they sync in has to be stated")

    distinct = set(repo_urls.values())
    if len(distinct) > 1:
        detail = ", ".join(f"{k} -> {v}" for k, v in sorted(repo_urls.items()))
        problems.append(f"manifests disagree about the repository: {detail}")

    return problems


# --- self-test -------------------------------------------------------------

PROJECT = """apiVersion: argoproj.io/v1alpha1
kind: AppProject
metadata:
  name: fleet
spec:
  sourceRepos:
    - https://example.com/repo.git
"""

APP = """apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: fleet-{name}
  annotations:
    argocd.argoproj.io/sync-wave: "{wave}"
spec:
  project: {project}
  source:
    repoURL: {repo}
    targetRevision: {revision}
    path: {path}
"""


def fixture(tmp, *, project="fleet", repo="https://example.com/repo.git",
            revision="main", path="managed", wave=0, pod=False,
            root_path="apps-dir"):
    """A gitops tree and the repository root it describes."""
    root = tmp
    (root / "managed").mkdir(parents=True, exist_ok=True)
    (root / "apps-dir").mkdir(parents=True, exist_ok=True)
    (root / "managed" / "10-thing.yaml").write_text(
        "kind: Pod\n" if pod else "kind: Deployment\n")

    gitops = root / "infrastructure" / "gitops" / "apps"
    gitops.mkdir(parents=True, exist_ok=True)
    (gitops.parent / "project.yaml").write_text(PROJECT)
    (gitops.parent / "root.yaml").write_text(APP.format(
        name="root", project=project, repo=repo, revision=revision,
        path=root_path, wave=wave))
    (gitops / "platform.yaml").write_text(APP.format(
        name="platform", project=project, repo=repo, revision=revision,
        path=path, wave=wave))
    return gitops.parent, root


def self_test():
    cases = []

    def case(label, want_ok, want_in=None, **kw):
        with tempfile.TemporaryDirectory() as tmp:
            gitops, root = fixture(Path(tmp), **kw)
            problems = check(gitops, root)
            ok = not problems
            joined = " | ".join(problems)
            good = (ok == want_ok) and (want_in is None or want_in in joined)
            cases.append((good, label, "OK" if ok else joined[:150]))

    case("a well-formed tree passes", True)
    case("a path that does not exist fails", False, "not a directory",
         path="nowhere")
    case("a bare Pod in a managed path fails", False, "bare Pod", pod=True)
    case("a foreign project fails", False, "escapes its Pod blacklist",
         project="default")
    case("a branch targetRevision fails", False, "track main",
         revision="some-branch")
    case("a disagreeing repoURL fails", False, "disagree about the repository",
         repo="https://example.com/other.git")

    # A missing sync-wave is checked directly: the fixture always writes one.
    with tempfile.TemporaryDirectory() as tmp:
        gitops, root = fixture(Path(tmp))
        app = gitops / "apps" / "platform.yaml"
        app.write_text(re.sub(r'\n  annotations:\n.*?\n', '\n', app.read_text(),
                              count=1, flags=re.DOTALL))
        problems = check(gitops, root)
        cases.append((any("sync-wave" in p for p in problems),
                      "a missing sync-wave fails",
                      " | ".join(problems)[:150] or "OK"))

    for good, label, detail in cases:
        print(f"  {'ok  ' if good else 'FAIL'}  {label}"
              + ("" if good else f"  ({detail})"))
    failed = [c for c in cases if not c[0]]
    print(f"{len(cases) - len(failed)}/{len(cases)} self-tests passed")
    return 1 if failed else 0


def main():
    problems = check()
    if problems:
        print("FAIL: the GitOps manifests are not consistent:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print("OK: GitOps manifests agree on repository, project, revision, "
          "ordering, and every managed path exists and holds no bare Pod")
    return 0


if __name__ == "__main__":
    sys.exit(self_test() if "--self-test" in sys.argv[1:] else main())

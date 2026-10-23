#!/usr/bin/env python3
"""pre-push-verdict.py — the detekt verdict about the tree that is being pushed.

The pre-push hook has one question to answer: **does the tree that is being
pushed hold a detekt violation?** This script answers it, and the hook prints
what it says.

Why it is a script and not a shell function: the decision has two branches
that are easy to get wrong and impossible to see in a diff — which tree to
judge, and whether the tree changed under detekt's feet. Both are testable
here, and the tests are cheap: this file and its test take seconds, while the
hook it replaces grew to hundreds of lines of bash whose branches nothing
exercised.

The tree is chosen like this:

  - the current worktree, when it holds byte-identical content for everything
    detekt can read — then the worktree *is* the pushed tree, and judging it
    there is warm (this checkout's own Gradle home and build directories);
  - a throwaway worktree at the pushed sha, otherwise. The hook already
    checks one out for the same sha and passes it as ``--fallback-tree``.

The verdict READS the tree; it does not correct it.

Two signals make the verdict red, and both are needed:

  - the run's exit code — detekt's verdict on the findings it reported;
  - the content digest before and after the run — the evidence that the run
    changed the tree it was judging. The verdict runs detekt read-only, so
    this is a safety net rather than the usual case: it catches any task in
    the invocation that writes to a source file (ADR
    2026-10-23-the-verdict-reads-the-tree-it-does-not-correct-it.md).

The digest is a hash over the content of every Kotlin file, not
``git status --porcelain``: a file that was already modified before the run
keeps the same status line after the correction, so a status comparison
cannot see that anything happened.

Which modules are judged is computed, not listed: the module graph in
``scripts/changed-modules.py`` says what a change can break, so a change in one
module judges that module and the modules that compile against it, and a change
in the rules or in the build judges every module. The list is therefore never
wider than the change and never narrower than what the change can break — and
it is never a hardcoded pair of module names that goes stale.

The verdict is PARTIAL, and says so: it judges the modules this change can
affect. A violation in a module the change does not touch is CI's to find —
CI judges every module, the hook judges the affected ones. The registry
``config/hooks/pre-push.json`` records both sides, and
``scripts/check-hook-gates.py`` checks that they still agree.

Usage:
    python3 scripts/pre-push-verdict.py --sha <sha> [--repo .] [--fallback-tree PATH]
    python3 scripts/pre-push-verdict.py --sha <sha> --json

Exit codes:
    0 — the pushed tree is clean
    1 — the pushed tree holds a violation, or the run changed the tree
    2 — the verdict could not be taken (no tree, no Gradle)
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import pathlib
import re
import subprocess
import sys

ROOT: pathlib.Path = pathlib.Path(__file__).resolve().parent.parent

#: The paths that can change a detekt verdict:
#:   - the Kotlin sources detekt analyses;
#:   - the build scripts that decide which source sets are scanned;
#:   - the detekt config, the baseline and the custom rules;
#:   - the version catalog the build scripts read;
#:   - .editorconfig — ktlint reads it from inside detekt (max_line_length,
#:     ktlint_class_signature_rule_force_multiline_when_parameter_count…),
#:     so a worktree with a different .editorconfig is judged by different
#:     rules than the tree it stands in for.
DETEKT_PATHSPECS = (
    ':(glob)**/*.kt',
    ':(glob)**/*.kts',
    'config/detekt/**',
    'build-logic/**',
    'detekt-rules/**',
    'gradle/**',
    '.editorconfig',
)

#: The detekt tasks the hook judges when nothing else is known. The real
#: choice is computed from the module graph (see `detekt_tasks_for_change`),
#: and this list is only the fallback for a push whose range has no known base.
#: The CI judges more modules — see config/hooks/pre-push.json, which is what
#: check-hook-gates.py reads.
DEFAULT_TASKS = (":shared:detekt", ":desktopApp:detekt")

#: Paths whose change can change the verdict of EVERY module: the rules, the
#: custom rules, the build that decides the source sets, and the editorconfig
#: that ktlint reads from inside detekt.
VERDICT_WIDE_PATHS = (
    'config/detekt/',
    'detekt-rules/',
    'build-logic/',
    'gradle/',
    '.editorconfig',
)

#: Where the change is measured against when the push does not name a base.
#: The trunk's remote-tracking ref: a branch that does not exist on the remote
#: yet is still a change *against the trunk*, and measuring it against nothing
#: would judge the whole repository — every module, for a one-line change.
DEFAULT_BASE = "origin/main"

#: The Gradle command the hook uses. ./gw, not ./gradlew: a worktree-private
#: GRADLE_USER_HOME, so a push in one worktree cannot kill the daemons of
#: another (ADR 2026-10-04-gradle-daemon-isolation.md).
DEFAULT_GRADLE = "./gw"


def tree_env() -> dict[str, str]:
    """The environment for a git command that runs inside a tree.

    Git exports GIT_DIR (and GIT_WORK_TREE, GIT_INDEX_FILE) for the hook, and
    they point at THIS worktree: a `git ls-files` inside the pushed tree would
    list the original worktree's files, and the digest would be of a tree
    nobody pushed. The pushed tree has its own `.git` file, and git finds it
    when the environment does not say otherwise.
    """
    env = dict(os.environ)
    for name in ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR'):
        env.pop(name, None)
    return env

class VerdictUnavailable(Exception):
    """The verdict could not be taken — there is no tree to judge."""


def load_module_graph():
    """The module graph, loaded from scripts/changed-modules.py.

    One source of truth: the local verification plan, the CI module list and
    this verdict all read the same graph, so a module that appears in one and
    not in the others cannot be forgotten.
    """
    path = ROOT / "scripts" / "changed-modules.py"
    spec = importlib.util.spec_from_file_location("changed_modules", path)
    if spec is None or spec.loader is None:
        raise VerdictUnavailable(f"could not load the module graph from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def changed_paths(tree: pathlib.Path, base: str, sha: str) -> list[str]:
    """The paths the pushed range touches, in the tree that is judged."""
    result = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...{sha}"],
        cwd=tree,
        env=tree_env(),
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise VerdictUnavailable(
            f"could not compare {base}...{sha[:8]} in {tree}: {result.stderr.strip()}",
        )
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def default_base(tree: pathlib.Path, branch: str | None = None) -> str | None:
    """The ref the change is measured against when the push does not name one.

    Three sources, in the order they are worth more:

      1. the branch's own upstream — the branch it is a branch of. A branch
         that targets a release line is measured against that line, not
         against the trunk: measuring it against `main` would call every
         change to the release line a change to the trunk, and judge the
         modules the release line does not even have;
      2. the trunk's remote-tracking ref (`origin/main`) — a branch that does
         not exist on the remote yet is still a change *against the trunk*,
         and measuring it against nothing would judge the whole repository —
         every module, for a one-line change;
      3. nothing — and then every module is judged, the safe direction.
    """
    if branch:
        upstream = _rev_parse(tree, f'{branch}@{{upstream}}')
        if upstream:
            return upstream
    return DEFAULT_BASE if _rev_parse(tree, DEFAULT_BASE) else None


def _rev_parse(tree: pathlib.Path, ref: str) -> str | None:
    """The sha a ref points at, or None when the ref does not exist here."""
    result = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", ref],
        cwd=tree,
        env=tree_env(),
        capture_output=True,
        text=True,
        check=False,
    )
    return result.stdout.strip() or None if result.returncode == 0 else None


def module_of(task: str) -> str:
    """The module a Gradle task belongs to.

    `:shared:detekt` → `shared`; `:tools:unwritten-properties:test` →
    `tools:unwritten-properties`. The module is a path in Gradle, so the name
    can have more than one segment — taking the first one would say `tools`
    and hide which module was meant.
    """
    return task[1:].rsplit(":", 1)[0] if task.startswith(":") else task


def detekt_tasks_for_change(tree: pathlib.Path, base: str | None, sha: str) -> tuple[list[str], str]:
    """The detekt tasks this change makes worth running, and why.

    Three cases, in the order they are decided:

      - the change touches the rules, the custom rules or the build — then
        every module's verdict can change, so every module is judged;
      - the change touches a module — then that module and the modules that
        compile against it are judged, because that is what the module graph
        says the change can break;
      - the change touches nothing that detekt reads — then no detekt run is
        needed, and the verdict says so instead of claiming a clean tree.

    A range with no known base is judged in full: the safe direction.
    """
    graph = load_module_graph()
    every = [f":{module}:detekt" for module in graph.TASKS.detekt_modules()]

    if base is None:
        return every, "the pushed range has no known base — every module is judged"

    paths = changed_paths(tree, base, sha)
    wide = [path for path in paths if path.startswith(VERDICT_WIDE_PATHS)]
    if wide:
        return every, f"{wide[0]} changed — the rules or the build moved, so every module is judged"

    tasks, _reasons = graph.plan(paths)
    # The plan is the LOCAL plan: it names the gate scripts as well as the
    # Gradle tasks, because a change to the gates is a change that has to be
    # checked by the gates. The verdict is a Gradle verdict, so only the
    # Gradle tasks are its business.
    gradle_tasks = [task for task in tasks if task.startswith(":")]
    detekt = [task for task in gradle_tasks if task.endswith(":detekt")]
    if not detekt:
        if gradle_tasks:
            # The change reaches a module that has no detekt task of its own:
            # `:tools:unwritten-properties` applies the plain kotlin("jvm")
            # plugin, so its verdict is its test suite. That is CI's to run —
            # the local verdict is a detekt verdict, and it says so instead of
            # claiming a clean tree it never measured.
            modules = sorted({module_of(task) for task in gradle_tasks})
            return [], (
                f"the change reaches {', '.join(modules)}, whose verdict is its test suite — "
                "no detekt task exists for it, so the local verdict does not judge it"
            )
        return [], "this change moves no Kotlin and no rules — no detekt run is needed"
    modules = sorted({module_of(task) for task in detekt})
    return detekt, f"the change reaches {', '.join(modules)}"


def git(repo: pathlib.Path, *args: str, check: bool = True) -> str:
    """Run a git command in `repo` and return its stdout."""
    result = subprocess.run(
        ["git", *args],
        cwd=repo,
        env=tree_env(),
        capture_output=True,
        text=True,
        check=False,
    )
    if check and result.returncode != 0:
        raise VerdictUnavailable(
            f"git {' '.join(args)} failed in {repo}: {result.stderr.strip() or result.returncode}",
        )
    return result.stdout


def worktree_holds_sha(repo: pathlib.Path, sha: str) -> bool:
    """Does the worktree hold exactly the content of `sha` for everything detekt reads?

    Tracked differences AND untracked files count: `git diff` cannot see a new
    Kotlin file, and detekt reads it.
    """
    diff = subprocess.run(
        ["git", "diff", "--quiet", sha, "--", *DETEKT_PATHSPECS],
        cwd=repo,
        env=tree_env(),
        capture_output=True,
        check=False,
    )
    if diff.returncode != 0:
        return False
    status = git(repo, "status", "--porcelain", "--untracked-files=all", "--", *DETEKT_PATHSPECS)
    return not any(line.startswith("??") for line in status.splitlines())


def kotlin_digest(tree: pathlib.Path) -> str:
    """One hash over the content of every Kotlin file in a tree.

    Tracked and untracked: detekt reads both, and a correction to an
    untracked file is as much a violation as a correction to a tracked one.

    `git hash-object --stdin-paths` reads the paths FROM stdin — passing them
    as arguments as well is an error.
    """
    listing = subprocess.run(
        ["git", "ls-files", "--", "*.kt", "*.kts"],
        cwd=tree,
        env=tree_env(),
        capture_output=True,
        text=True,
        check=False,
    )
    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard", "--", "*.kt", "*.kts"],
        cwd=tree,
        env=tree_env(),
        capture_output=True,
        text=True,
        check=False,
    )
    paths = [p for p in (listing.stdout + untracked.stdout).splitlines() if p]
    if not paths:
        return "empty"
    hashed = subprocess.run(
        ["git", "hash-object", "--stdin-paths"],
        cwd=tree,
        input="\n".join(paths),
        capture_output=True,
        text=True,
        check=False,
    )
    if hashed.returncode != 0:
        raise VerdictUnavailable(f"could not hash the Kotlin files in {tree}: {hashed.stderr.strip()}")
    return hashed.stdout.strip()


def run_tasks(
    tree: pathlib.Path,
    tasks: tuple[str, ...],
    gradle_cmd: str,
    log: pathlib.Path,
) -> tuple[int, list[str]]:
    """Run the detekt tasks in ONE Gradle invocation.

    Returns the exit code and the tasks that failed.

    One invocation, not one per task: the constraint that forced one Gradle
    call per detekt task was `--auto-correct` reaching only the LAST task of
    an invocation (ADR 2026-10-17). The verdict does not correct, so the
    constraint is gone — and a single invocation pays the configuration phase
    once instead of once per module, which is what a wide change costs.

    The flag is NOT passed, and that is the point: the verdict reads the tree
    it judges, so it cannot change the tree that is being pushed. Correction
    is a separate step (`just detekt-fix`), and the verdict tells the user
    about the findings instead of quietly repairing them.

    `--rerun` forces re-analysis: without it Gradle is UP-TO-DATE and the
    verdict would be about the previous run's inputs. `--rerun` re-runs
    detekt alone and leaves its dependencies alone.

    `--continue` runs every judged module even when an earlier one failed, so
    the report names all the findings instead of the first one.
    """
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("w", encoding="utf-8") as out:
        result = subprocess.run(
            [
                *gradle_cmd.split(),
                *tasks,
                "--rerun",
                "--continue",
                "--no-daemon",
                "--console=plain",
            ],
            cwd=tree,
            stdout=out,
            stderr=subprocess.STDOUT,
            check=False,
        )
    return result.returncode, failed_tasks_in(log)


#: Gradle's plain console prints a failed task as `> Task :module:detekt FAILED`.
_FAILED_TASK = re.compile(r"^> Task (\S+) FAILED", re.MULTILINE)


def failed_tasks_in(log: pathlib.Path) -> list[str]:
    """The tasks the log says failed, in the order the log reports them."""
    try:
        text = log.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    seen: list[str] = []
    for match in _FAILED_TASK.finditer(text):
        task = match.group(1)
        if task not in seen:
            seen.append(task)
    return seen


def select_tree(repo: pathlib.Path, sha: str, fallback: pathlib.Path | None) -> tuple[pathlib.Path, str]:
    """Pick the tree that holds the pushed content, and say which one it is."""
    if worktree_holds_sha(repo, sha):
        return repo, f"the current worktree — byte-identical to {sha[:8]}"
    if fallback is not None and fallback.is_dir():
        return fallback, f"a temporary worktree at {sha[:8]}"
    raise VerdictUnavailable(f"no tree holds {sha[:8]}")


def judge(
    repo: pathlib.Path,
    sha: str,
    fallback: pathlib.Path | None,
    gradle_cmd: str,
    tasks: tuple[str, ...] | None,
    log_dir: pathlib.Path,
    base: str | None = None,
) -> dict:
    """Take the verdict. Returns a report; `clean` says whether the tree is clean."""
    tree, label = select_tree(repo, sha, fallback)

    reason = "the tasks were given on the command line"
    if tasks is None:
        tasks, reason = detekt_tasks_for_change(tree, base, sha)

    before = kotlin_digest(tree) if tasks else ""

    failed: list[str] = []
    run_failed = False
    if tasks:
        log = log_dir / f"detekt-{sha[:8]}.log"
        rc, failed = run_tasks(tree, tuple(tasks), gradle_cmd, log)
        run_failed = rc != 0
        if run_failed and not failed:
            # The invocation failed without naming a task — a configuration
            # failure, a broken build script. The verdict is red, and it says
            # the run failed rather than naming a module that did not.
            failed = ["<the detekt run itself>"]

    after = kotlin_digest(tree) if tasks else ""
    mutated = bool(tasks) and before != after

    return {
        "sha": sha,
        "tree": str(tree),
        "tree_label": label,
        "tasks": list(tasks),
        "coverage_reason": reason,
        "base": base,
        "failed_tasks": failed,
        "mutated": mutated,
        "clean": not run_failed and not mutated,
        "judged": bool(tasks),
        "log_dir": str(log_dir),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--sha", required=True, help="the commit being pushed")
    parser.add_argument("--repo", default=".", help="the repository root")
    parser.add_argument(
        "--fallback-tree",
        default=None,
        help="a worktree already checked out at the sha, used when the current one differs",
    )
    parser.add_argument("--gradle-cmd", default=DEFAULT_GRADLE, help="the Gradle command to run")
    parser.add_argument(
        "--tasks",
        default=None,
        help="the detekt tasks to judge (default: the modules this change can affect)",
    )
    parser.add_argument(
        "--base",
        default=None,
        help="the ref the pushed range starts at; the change is measured against it",
    )
    parser.add_argument(
        "--branch",
        default=None,
        help="the branch being pushed — its upstream is the base when the push does not name one",
    )
    parser.add_argument("--log-dir", default=None, help="where the detekt logs are written")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    args = parser.parse_args(argv)

    repo = pathlib.Path(args.repo).resolve()
    fallback = pathlib.Path(args.fallback_tree).resolve() if args.fallback_tree else None
    log_dir = pathlib.Path(args.log_dir) if args.log_dir else pathlib.Path("/tmp/pre-push-verdict")
    tasks = tuple(t for t in args.tasks.split()) if args.tasks else None
    base = args.base or default_base(repo, args.branch)

    try:
        report = judge(repo, args.sha, fallback, args.gradle_cmd, tasks, log_dir, base)
    except VerdictUnavailable as exc:
        if args.json:
            print(json.dumps({"clean": False, "unavailable": str(exc)}, ensure_ascii=False))
        else:
            print(f"pre-push: the detekt verdict could not be taken — {exc}", file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps(report, ensure_ascii=False))
        return 0 if report["clean"] else 1

    print(f"   detekt verdict taken on {report['tree_label']}")
    if report["judged"]:
        print(f"   detekt verdict: judged {len(report['tasks'])} modules")
        print(f"   modules judged: {', '.join(report['tasks'])}")
        print(f"   why: {report['coverage_reason']}")
        print(
            "   the local verdict covers the modules this change can affect — "
            "a violation in a module it does not touch is CI's to find",
        )
    else:
        # Not "the tree is clean": nothing was judged, so nothing was found.
        # The hook reads this line and says "not taken", not "clean".
        print(f"   detekt verdict: nothing to judge — {report['coverage_reason']}")
    for task in report["failed_tasks"]:
        print(f"   detekt {task}: FAILED — see {report['log_dir']}")
    if report["mutated"]:
        print("   the tree that is being pushed changed under the verdict run:")
        diff = subprocess.run(
            ["git", "--no-pager", "diff"],
            cwd=report["tree"],
            capture_output=True,
            text=True,
            check=False,
        )
        print(diff.stdout)
    if report["clean"]:
        print("   the pushed tree is clean")
    return 0 if report["clean"] else 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""measure-verdict-cost.py — what the local pre-push verdict costs, measured.

Why this exists
---------------
The price of the verdict was prose. The published page carried a table whose
numbers were typed into the generator's template: a claim nobody could check, and
a claim that aged into a quietly wrong one. The price of a thing the push waits
for is worth more as a measurement than as a sentence, so this script measures it
and writes the result to a record, and the page is rendered from that record.

What it measures
----------------
Four phases, each of them something the hook actually does (or could do):

  * ``detekt`` — the analysis the verdict waits for: the detekt tasks of the
    modules the change reaches, read-only, exactly as the hook runs them.
  * ``auto-correct`` — the same run with ``--auto-correct``: what the verdict
    deliberately does NOT do (ADR
    ``2026-10-23-the-verdict-reads-the-tree-it-does-not-correct-it.md``), measured
    so that the price of correcting is a number and not an opinion.
  * ``digest`` — the content digest the verdict computes before and after the
    analysis (``git ls-files`` plus ``git hash-object`` over every Kotlin file).
  * ``together`` — several modules judged in ONE Gradle invocation: the question
    "does judging two modules cost less than judging them one after the other?"
    answered by a measurement instead of an assumption.

and, on request, the whole thing end to end:

  * ``verdict`` — ``scripts/pre-push-verdict.py`` itself, timed, with the report
    it produced.

The record
----------
``docs/testing/verdict-cost.json`` — committed, because it is a record of a
measurement and not a byproduct of one: the page renders from it, so it has to
survive the run that produced it. It carries the environment and the host load
the numbers were measured under, because a duration without them is a number
nobody can compare with anything.

Freshness is part of the record. ``status`` is ``fresh`` or ``stale``, and a
stale record says why. A stale record is not deleted and not hidden: the page
publishes the numbers AND says that they describe another tree. Silently
dropping them would leave a reader with no number and no warning, which is the
worse of the two failures.

Usage:
    python3 scripts/measure-verdict-cost.py
    python3 scripts/measure-verdict-cost.py --phases detekt,digest
    python3 scripts/measure-verdict-cost.py --phases verdict --verdict-script FAKE
    python3 scripts/measure-verdict-cost.py --refresh

Exit codes:
    0 — the record was written (or refreshed)
    1 — a phase could not be measured
    2 — the record could not be written
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: The committed record of the measured price of the verdict.
DEFAULT_RECORD = ROOT / "docs" / "testing" / "verdict-cost.json"

#: The verdict this script measures the price of.
DEFAULT_VERDICT_SCRIPT = ROOT / "scripts" / "pre-push-verdict.py"

#: The version of the record format. A reader that does not know this version
#: must not guess what the fields mean.
SCHEMA_VERSION = 1

#: The Gradle command the hook uses: ``./gw``, not ``./gradlew`` — a
#: worktree-private GRADLE_USER_HOME, so a measurement in one worktree cannot
#: kill the daemons of another (ADR 2026-10-04-gradle-daemon-isolation.md).
DEFAULT_GRADLE = "./gw"

#: The tasks the first two phases judge. The whale of the verdict is
#: ``:shared``; the rest are seconds to two minutes.
DEFAULT_TASKS = (":shared:detekt",)

#: The tasks the ``together`` phase judges in one invocation.
DEFAULT_TOGETHER_TASKS = (":shared:detekt", ":desktopApp:detekt")

#: How long a record stays fresh. A price measured weeks ago describes a tree
#: that has moved on, and the page says so rather than publishing it as current.
DEFAULT_EXPIRES_DAYS = 30

#: The phases this script knows how to measure.
PHASES = ("detekt", "auto-correct", "digest", "together", "verdict")

#: The phases measured when none are named. The two that cost a Gradle
#: invocation each are in: a price that is not measured is not a price.
DEFAULT_PHASES = ("detekt", "auto-correct", "digest", "together")

#: The flags the hook's own detekt invocation uses. Copied from
#: ``pre-push-verdict.py`` so that the measurement prices the same run the hook
#: performs — a measurement of a different invocation is a measurement of
#: something else.
GRADLE_FLAGS = ("--rerun", "--continue", "--no-daemon", "--console=plain")


class MeasurementError(Exception):
    """A phase could not be measured."""


def environment() -> dict[str, object]:
    """What the measurement was taken on.

    The same fields the gate-costs baseline records, for the same reason: a
    duration is not a fact about a gate, it is a fact about a gate on a machine.
    """
    return {
        "os": platform.system(),
        "cpus": os.cpu_count() or 0,
        "python": platform.python_version(),
        "javaHome": os.environ.get("JAVA_HOME", ""),
    }


def host_load() -> dict[str, object]:
    """How busy the machine was when the measurement was taken."""
    try:
        one, five, fifteen = os.getloadavg()
    except (OSError, AttributeError):  # not every platform has it
        return {}
    return {
        "load1m": round(one, 1),
        "load5m": round(five, 1),
        "load15m": round(fifteen, 1),
        "cpus": os.cpu_count() or 0,
    }


def run_timed(command: list[str], cwd: Path) -> tuple[int, int]:
    """Run a command and return its exit code and its wall-clock duration.

    The duration is the measurement; the exit code is a fact about the run and
    is recorded next to it. A detekt run that finds a violation exits non-zero
    and still costs the time it took, so the exit code never decides whether
    the phase was measured.
    """
    started = time.monotonic()
    try:
        result = subprocess.run(
            command,
            cwd=cwd,
            check=False,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except OSError as exc:
        raise MeasurementError(f"could not run {' '.join(command)}: {exc}") from exc
    return result.returncode, int((time.monotonic() - started) * 1000)


def gradle_command(gradle_cmd: str, tasks: tuple[str, ...], extra: tuple[str, ...] = ()) -> list[str]:
    """The Gradle invocation of one phase."""
    return [*gradle_cmd.split(), *tasks, *extra, *GRADLE_FLAGS]


def phase_entry(
    name: str,
    what: str,
    command: list[str],
    exit_code: int,
    duration_ms: int,
    note: str = "",
) -> dict[str, object]:
    """One measured phase: what it was, what it cost, and what it found."""
    return {
        "name": name,
        "what": what,
        "command": " ".join(command),
        "durationMs": duration_ms,
        "exitCode": exit_code,
        "note": note,
    }


def measure_detekt(gradle_cmd: str, tasks: tuple[str, ...], repo: Path) -> dict[str, object]:
    """The analysis the verdict waits for: read-only, exactly as the hook runs it."""
    command = gradle_command(gradle_cmd, tasks)
    exit_code, duration = run_timed(command, repo)
    return phase_entry(
        "detekt",
        "the analysis the verdict waits for — "
        f"{', '.join(f'`{task}`' for task in tasks)}, read-only",
        command,
        exit_code,
        duration,
    )


def measure_auto_correct(gradle_cmd: str, tasks: tuple[str, ...], repo: Path) -> dict[str, object]:
    """The same run with ``--auto-correct``: what the verdict deliberately does not do.

    Measured rather than assumed, because "correcting is cheap" is exactly the
    kind of sentence that goes stale: the price of the correction is a share of
    the price of the analysis, and the share moves with the size of the tree.
    """
    command = gradle_command(gradle_cmd, tasks, extra=("--auto-correct",))
    exit_code, duration = run_timed(command, repo)
    return phase_entry(
        "auto-correct",
        "the same run with `--auto-correct` — what the verdict deliberately does "
        "not do, because it reads the tree it judges",
        command,
        exit_code,
        duration,
    )


#: The variables git exports to a hook. A git command that reads a tree must
#: not inherit them: they name the tree the hook runs in, not the tree the
#: command was pointed at (ADR 2026-10-23-the-shared-config-is-one-file-and-
#: every-worktree-writes-to-it.md, and scripts/check-git-environment.py).
GIT_ENVIRONMENT = ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR")


def tree_env() -> dict[str, str]:
    """The environment for a git command that reads a tree of its own."""
    env = dict(os.environ)
    for name in GIT_ENVIRONMENT:
        env.pop(name, None)
    return env


def kotlin_digest(repo: Path) -> tuple[str, int]:
    """The content digest the verdict computes, and how long it takes.

    Tracked and untracked files, exactly as ``pre-push-verdict.py`` computes it:
    detekt reads both, so a digest that ignored the untracked ones would not
    see a correction to a new file.
    """
    started = time.monotonic()
    try:
        listing = subprocess.run(
            ["git", "ls-files", "--", "*.kt", "*.kts"],
            cwd=repo, capture_output=True, text=True, check=False, env=tree_env(),
        )
        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard", "--", "*.kt", "*.kts"],
            cwd=repo, capture_output=True, text=True, check=False, env=tree_env(),
        )
        paths = [p for p in (listing.stdout + untracked.stdout).splitlines() if p]
        if not paths:
            return "", int((time.monotonic() - started) * 1000)
        hashed = subprocess.run(
            ["git", "hash-object", "--stdin-paths"],
            cwd=repo, input="\n".join(paths), capture_output=True, text=True, check=False,
            env=tree_env(),
        )
    except OSError as exc:
        raise MeasurementError(f"could not compute the tree digest: {exc}") from exc
    duration = int((time.monotonic() - started) * 1000)
    if hashed.returncode != 0:
        raise MeasurementError(
            f"could not hash the Kotlin files in {repo}: {hashed.stderr.strip()}"
        )
    return hashed.stdout.strip(), duration


def measure_digest(repo: Path) -> tuple[dict[str, object], str]:
    """The digest phase, and the digest itself.

    The digest is kept in the record: it is what says whether the tree the
    numbers were measured on is still the tree in front of you.
    """
    digest, duration = kotlin_digest(repo)
    command = ["git", "ls-files", "--", "*.kt", "*.kts"]
    return (
        phase_entry(
            "digest",
            "the content digest the verdict computes before and after the analysis "
            "(`git ls-files` plus `git hash-object` over every Kotlin file)",
            command,
            0,
            duration,
        ),
        digest,
    )


def measure_together(gradle_cmd: str, tasks: tuple[str, ...], repo: Path) -> dict[str, object]:
    """Several modules judged in ONE Gradle invocation.

    The question this answers is whether judging two modules costs less than
    judging them one after the other. The answer is a number, and the number is
    often "no" — the Gradle slots are global, and a machine that is already busy
    serialises the work whatever the build file says.
    """
    command = gradle_command(gradle_cmd, tasks, extra=("--parallel",))
    exit_code, duration = run_timed(command, repo)
    return phase_entry(
        "together",
        f"{', '.join(f'`{task}`' for task in tasks)} judged in ONE Gradle invocation — "
        "whether judging several modules costs less than judging them one after "
        "the other",
        command,
        exit_code,
        duration,
    )


def measure_verdict(
    verdict_script: Path,
    sha: str,
    repo: Path,
    gradle_cmd: str,
    tasks: tuple[str, ...] | None,
    base: str | None,
    branch: str | None,
) -> tuple[dict[str, object], dict[str, object]]:
    """The whole verdict, end to end, and the report it produced."""
    command = [
        sys.executable,
        str(verdict_script),
        "--json",
        "--sha",
        sha,
        "--repo",
        str(repo),
        "--gradle-cmd",
        gradle_cmd,
    ]
    if tasks:
        command += ["--tasks", " ".join(tasks)]
    if base:
        command += ["--base", base]
    if branch:
        command += ["--branch", branch]
    started = time.monotonic()
    try:
        result = subprocess.run(
            command, cwd=repo, capture_output=True, text=True, check=False,
            stdin=subprocess.DEVNULL,
        )
    except OSError as exc:
        raise MeasurementError(f"could not run the verdict: {exc}") from exc
    duration = int((time.monotonic() - started) * 1000)
    try:
        report = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise MeasurementError(
            f"the verdict did not answer with JSON: {result.stdout.strip()[:200]}"
        ) from exc
    if not isinstance(report, dict):
        raise MeasurementError("the verdict answered with something that is not a report")
    phase = phase_entry(
        "verdict",
        "the whole verdict, end to end — what the push actually waits for",
        command,
        result.returncode,
        duration,
    )
    return phase, report


def measured_at() -> str:
    """When the measurement was taken, in a form that sorts and parses."""
    return datetime.now().astimezone().replace(microsecond=0).isoformat()


def current_sha(repo: Path) -> str:
    """The commit the measurement is about."""
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=repo, capture_output=True, text=True, check=False, env=tree_env(),
        )
    except OSError as exc:
        raise MeasurementError(f"could not read HEAD: {exc}") from exc
    sha = result.stdout.strip()
    if result.returncode != 0 or not sha:
        raise MeasurementError(f"could not read HEAD in {repo}")
    return sha


def freshness(
    record: dict[str, object],
    tree_digest: str | None = None,
    now: datetime | None = None,
) -> tuple[str, str]:
    """Whether the record still describes the tree in front of you, and why not.

    Two things make a record stale: time (the price of a tree that has grown is
    not the price of the tree that is here) and the tree itself (the numbers
    were measured on a different one). Both are decided here and stored in the
    record, so that the published page says the same thing every time it is
    rendered — a page that decided for itself would print a different sentence
    on Tuesday than on Monday, and the gate that compares the two would fail for
    the wrong reason.
    """
    status = "fresh"
    reasons: list[str] = []

    expires = record.get("expiresDays")
    measured = record.get("measuredAt")
    if isinstance(expires, int) and isinstance(measured, str) and measured:
        try:
            taken = datetime.fromisoformat(measured)
        except ValueError:
            taken = None
        if taken is not None:
            if taken.tzinfo is None:
                # A record may carry a day instead of a moment; the day is read
                # in the machine's own timezone rather than in UTC, so that the
                # age of a record is not off by the offset.
                taken = taken.astimezone()
            age_days = ((now or datetime.now().astimezone()) - taken).days
            if age_days > expires:
                reasons.append(
                    f"measured {age_days} days ago; the record expires after {expires} days"
                )

    recorded_digest = record.get("treeDigest")
    if tree_digest and isinstance(recorded_digest, str) and recorded_digest:
        if recorded_digest != tree_digest:
            reasons.append("the tree changed since the measurement")

    if reasons:
        status = "stale"
    return status, "; ".join(reasons)


def build_record(
    phases: list[dict[str, object]],
    *,
    sha: str,
    tree_digest: str,
    expires_days: int,
    notes: list[str] | None = None,
    verdict: dict[str, object] | None = None,
) -> dict[str, object]:
    """The record: the phases, the machine, and the moment.

    ``verdict`` is the report the verdict itself produced, when the verdict was
    one of the measured phases. It says which modules were judged, and the price
    of a verdict is a price per judged module.
    """
    return {
        "schemaVersion": SCHEMA_VERSION,
        "measuredAt": measured_at(),
        "status": "fresh",
        "staleReason": "",
        "expiresDays": expires_days,
        "sha": sha,
        "treeDigest": tree_digest,
        "environment": environment(),
        "host": host_load(),
        "verdict": verdict or {},
        "phases": phases,
        "notes": notes or [],
    }


def write_record(record: dict[str, object], path: Path) -> None:
    """Write the record, sorted, so that a diff of it is readable."""
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    except OSError as exc:
        raise MeasurementError(f"could not write the record at {path}: {exc}") from exc


def read_record(path: Path) -> dict[str, object]:
    """The record on disk."""
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise MeasurementError(f"could not read the record at {path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise MeasurementError(f"the record at {path} is not JSON: {exc}") from exc
    if not isinstance(document, dict):
        raise MeasurementError(f"the record at {path} is not a record")
    return document


def refresh(path: Path, repo: Path) -> dict[str, object]:
    """Re-decide the freshness of a record without measuring anything.

    Cheap on purpose: the tree digest is a second, and the question it answers
    — "does this record still describe this tree?" — is worth a second.
    """
    record = read_record(path)
    digest = ""
    try:
        digest, _ = kotlin_digest(repo)
    except MeasurementError:
        digest = ""
    status, reason = freshness(record, digest or None)
    record["status"] = status
    record["staleReason"] = reason
    record["checkedAt"] = measured_at()
    write_record(record, path)
    return record


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--repo", default=str(ROOT), help="the repository the verdict is measured in"
    )
    parser.add_argument(
        "--out", default=str(DEFAULT_RECORD), help="where the record is written"
    )
    parser.add_argument(
        "--sha", default=None, help="the commit to judge (default: HEAD)"
    )
    parser.add_argument(
        "--tasks",
        default=" ".join(DEFAULT_TASKS),
        help="the detekt tasks the verdict judges",
    )
    parser.add_argument(
        "--together-tasks",
        default=" ".join(DEFAULT_TOGETHER_TASKS),
        help="the detekt tasks the 'together' phase judges in one invocation",
    )
    parser.add_argument(
        "--gradle-cmd", default=DEFAULT_GRADLE, help="the Gradle command to run"
    )
    parser.add_argument(
        "--verdict-script",
        default=str(DEFAULT_VERDICT_SCRIPT),
        help="the verdict script the 'verdict' phase runs",
    )
    parser.add_argument(
        "--base", default=None, help="the ref the pushed range starts at"
    )
    parser.add_argument(
        "--branch", default=None, help="the branch being pushed"
    )
    parser.add_argument(
        "--phases",
        default=",".join(DEFAULT_PHASES),
        help=f"the phases to measure ({', '.join(PHASES)}); 'none' measures nothing",
    )
    parser.add_argument(
        "--expires-days",
        type=int,
        default=DEFAULT_EXPIRES_DAYS,
        help="how long the record stays fresh",
    )
    parser.add_argument(
        "--refresh",
        action="store_true",
        help="re-decide the freshness of the record without measuring anything",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    repo = Path(args.repo).resolve()
    path = Path(args.out)

    try:
        if args.refresh:
            record = refresh(path, repo)
            print(
                f"the record at {path} is {record['status']}"
                + (f" — {record['staleReason']}" if record.get("staleReason") else "")
            )
            return 0

        phases = [p.strip() for p in args.phases.split(",") if p.strip()]
        unknown = [p for p in phases if p not in PHASES]
        if unknown:
            print(
                f"unknown phase(s): {', '.join(unknown)} — the phases are {', '.join(PHASES)}",
                file=sys.stderr,
            )
            return 1

        tasks = tuple(t for t in args.tasks.split() if t)
        together = tuple(t for t in args.together_tasks.split() if t)
        sha = args.sha or current_sha(repo)

        measured: list[dict[str, object]] = []
        digest = ""
        report: dict[str, object] | None = None
        for phase in phases:
            if phase == "detekt":
                measured.append(measure_detekt(args.gradle_cmd, tasks, repo))
            elif phase == "auto-correct":
                measured.append(measure_auto_correct(args.gradle_cmd, tasks, repo))
            elif phase == "digest":
                entry, digest = measure_digest(repo)
                measured.append(entry)
            elif phase == "together":
                measured.append(measure_together(args.gradle_cmd, together, repo))
            elif phase == "verdict":
                entry, report = measure_verdict(
                    Path(args.verdict_script),
                    sha,
                    repo,
                    args.gradle_cmd,
                    tasks or None,
                    args.base,
                    args.branch,
                )
                measured.append(entry)

        record = build_record(
            measured,
            sha=sha,
            tree_digest=digest,
            expires_days=args.expires_days,
            verdict=report,
        )
        write_record(record, path)
    except MeasurementError as exc:
        print(f"measure-verdict-cost: {exc}", file=sys.stderr)
        return 1

    for entry in measured:
        print(f"  {entry['name']:<14}{entry['durationMs'] / 1000:>8.1f} s  {entry['command']}")
    print(f"the record was written to {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

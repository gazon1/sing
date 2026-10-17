#!/usr/bin/env python3
"""check-flaky-tests.py — classify a test run against the previous one.

## The gap this fills

Every gate in this repo answers a question about *one* run: did the tests pass,
did enough of them run, was coverage above the floor. None of them can see a
test that passes on Tuesday and fails on Wednesday, because a run that is green
looks exactly like a run that is green because the test is sound.

That failure mode is expensive precisely because it is invisible. Two flakes in
this repo's recent history — `ProjectsFlowTest` reading a draft state before the
init collector seeded it, and `TaskDetailCoordinatorGraphTest` waiting on a
10-second *real-time* budget under parallel load — were each observed once, not
reproduced, and ended up as prose in `docs/decisions/deferred-backlog.md` rather
than as a signal. This script turns "observed once" into a measured set.

## How a flake is identified

A flake is not visible inside one run. It is visible as a **status flip between
two runs**:

    previously passed, now failed   -> new failure
    previously failed, now passed   -> recovered, i.e. the flake witness

The second line is the valuable one: a test that fails in one run and passes in
the next has proven it is order- or timing-dependent, no matter what either run
reported.

## Baseline

`config/docs/flaky-baseline.txt` acknowledges known-flaky tests. An acknowledged
`new failure` is reported and does not fail the gate; an unacknowledged one does.
Acknowledging a test is a claim that its nondeterminism is understood — the file
asks for the reason, so the answer is written down rather than remembered.

## Usage

    scripts/check-flaky-tests.py --current shared/build/test-results/jvmTest \\
                                 --previous /tmp/prev-run
    scripts/check-flaky-tests.py --current ... --previous ... --summary "$GITHUB_STEP_SUMMARY"

Fails when a run contains a failure nobody has acknowledged. It never suppresses
a failure: the build result still comes from Gradle, this only annotates it.
"""
import pathlib
import sys

import click

# `scripts/src` on sys.path so `traceability.junit_xml` resolves. When loaded by a
# test harness (importlib.util.spec_from_file_location), sys.path[0] is
# `scripts/tests` and the sibling package is invisible; the real file's location is
# authoritative.
_scripts_src = str(pathlib.Path(__file__).resolve().parent / "src")
_added = _scripts_src not in sys.path
if _added:
    sys.path.insert(0, _scripts_src)
from traceability.junit_xml import parse_junit_file  # noqa: E402

if _added:
    sys.path.remove(_scripts_src)

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASELINE = ROOT / "config" / "docs" / "flaky-baseline.txt"

PASSED, FAILED, SKIPPED = "passed", "failed", "skipped"
CLEAN = 0
VALIDATION_FAILURE = 1

CUSTOM_ERROR = 2


def test_id(case) -> str:
    return f"{case.classname}::{case.name}"


def read_report(detail_dir: pathlib.Path):
    """test id -> status, or None when the directory has no JUnit XML.

    Delegates to `traceability.junit_xml.parse_junit_file` — the shared reader.
    A status is `failed` when the case failed *or* errored (an error is an
    infrastructure problem, not a distinct bucket for this comparison).
    """
    if not detail_dir.is_dir():
        return None
    results = {}
    for xml in sorted(detail_dir.glob("**/*.xml")):
        for case in parse_junit_file(xml):
            status = "failed" if case.failed_like else case.status
            results[test_id(case)] = status
    return results


def load_baseline(path: pathlib.Path):
    """acknowledged flaky ids, from lines of the form `id  # reason`.

    An entry may be a full `class::case` id or a bare class name, which
    acknowledges every test in that class. Nondeterminism is usually a property
    of a fixture or a shared daemon rather than of one assertion, and requiring
    one line per test would make the file unusable in practice.
    """
    if not path.exists():
        return {}
    entries = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        ident, _, reason = line.partition("#")
        entries[ident.strip()] = reason.strip()
    return entries


def is_acknowledged(test: str, acknowledged: dict) -> bool:
    if test in acknowledged:
        return True
    cls = test.split("::", 1)[0]
    return cls in acknowledged


def compare(previous: dict, current: dict):
    """Split the difference between two runs into named buckets."""
    new_failures, recovered, still_failing = [], [], []
    became_skipped, disappeared = [], []
    for ident, status in sorted(current.items()):
        before = previous.get(ident)
        if status == FAILED:
            if before == FAILED:
                still_failing.append(ident)
            else:
                new_failures.append(ident)
        elif status == SKIPPED and before == PASSED:
            became_skipped.append(ident)
        elif before == FAILED and status == PASSED:
            recovered.append(ident)
    for ident, before in sorted(previous.items()):
        if ident not in current:
            disappeared.append((ident, before))
    return {
        "new_failures": new_failures,
        "recovered": recovered,
        "still_failing": still_failing,
        "became_skipped": became_skipped,
        "disappeared": disappeared,
    }


@click.command()
@click.option("--current", required=True, help="JUnit XML dir of this run")
@click.option("--previous", default="", help="JUnit XML dir of the previous run")
@click.option("--baseline", default=str(BASELINE))
@click.option("--summary", default="", help="append a markdown section here")
@click.option("--allow-missing-previous", is_flag=True,
              help="exit 0 when there is nothing to compare against")
@click.pass_context
def cli(ctx: click.Context, current: str, previous: str, baseline: str,
        summary: str, allow_missing_previous: bool) -> None:
    current_path = pathlib.Path(current)
    rep = read_report(current_path)
    if not rep:
        click.echo(f"no JUnit XML in {current} — run the tests first", err=True)
        raise ctx.exit(VALIDATION_FAILURE)

    acknowledged = load_baseline(pathlib.Path(baseline))

    if not previous:
        message = "no previous run to compare against — flake analysis skipped"
        if allow_missing_previous:
            click.echo(message)
            raise ctx.exit(CLEAN)
        click.echo(message, err=True)
        raise ctx.exit(VALIDATION_FAILURE)

    prev_rep = read_report(pathlib.Path(previous))
    if prev_rep is None:
        message = f"previous run has no JUnit XML in {previous}"
        if allow_missing_previous:
            click.echo(message)
            raise ctx.exit(CLEAN)
        click.echo(message, err=True)
        raise ctx.exit(VALIDATION_FAILURE)

    buckets = compare(prev_rep, rep)
    unacknowledged = [t for t in buckets["new_failures"] if not is_acknowledged(t, acknowledged)]
    known = [t for t in buckets["new_failures"] if is_acknowledged(t, acknowledged)]

    lines = []
    for label, items in (
        ("new failures", buckets["new_failures"]),
        ("recovered (flake witnesses)", buckets["recovered"]),
        ("still failing", buckets["still_failing"]),
        ("newly skipped", buckets["became_skipped"]),
    ):
        if items:
            lines.append(f"- **{label}**: {len(items)}")
            lines.extend(f"  - `{t}`" for t in items)
    vanished = [i for i, _ in buckets["disappeared"]]
    if vanished:
        lines.append(f"- **no longer in the report**: {len(vanished)}")
        lines.extend(f"  - `{t}`" for t in vanished)

    click.echo(f"compared {len(rep)} tests against {len(prev_rep)} in the previous run")
    if known:
        click.echo(f"acknowledged flaky failures: {len(known)}")
    if buckets["recovered"]:
        click.echo(f"flake witnesses (failed in the previous run, passed now): "
                   f"{len(buckets['recovered'])}")
        for t in buckets["recovered"]:
            click.echo(f"  {t}")

    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write("\n## Flake analysis (previous run vs this one)\n\n")
            fh.write("\n".join(lines) + "\n" if lines else "No status changes.\n")

    if unacknowledged:
        click.echo("\nUnacknowledged test failures — no entry in "
                   "config/docs/flaky-baseline.txt:", err=True)
        for t in unacknowledged:
            click.echo(f"  {t}", err=True)
        click.echo(
            "\nIf the nondeterminism is understood, acknowledge it in\n"
            "config/docs/flaky-baseline.txt with the reason. If it is not, this is\n"
            "a real failure and the fix belongs in the code, not in the file.",
            err=True,
        )
        raise ctx.exit(VALIDATION_FAILURE)
    raise ctx.exit(CLEAN)


def main(argv=None) -> int:
    try:
        return cli.main(argv, standalone_mode=False)
    except SystemExit:
        raise


if __name__ == "__main__":
    raise SystemExit(main())

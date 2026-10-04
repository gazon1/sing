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
import argparse
import pathlib
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASELINE = ROOT / "config" / "docs" / "flaky-baseline.txt"

PASSED, FAILED, SKIPPED = "passed", "failed", "skipped"


def test_id(case) -> str:
    return f"{case.get('classname', '?')}::{case.get('name', '?')}"


def read_report(detail_dir: pathlib.Path):
    """test id -> status, or None when the directory has no JUnit XML."""
    if not detail_dir.is_dir():
        return None
    results = {}
    for xml in detail_dir.glob("**/*.xml"):
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for suite in suites:
            for case in suite.findall("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    status = FAILED
                elif case.find("skipped") is not None:
                    status = SKIPPED
                else:
                    status = PASSED
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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--current", required=True, help="JUnit XML dir of this run")
    parser.add_argument("--previous", default="", help="JUnit XML dir of the previous run")
    parser.add_argument("--baseline", default=str(BASELINE))
    parser.add_argument("--summary", default="", help="append a markdown section here")
    parser.add_argument("--allow-missing-previous", action="store_true",
                        help="exit 0 when there is nothing to compare against")
    args = parser.parse_args()

    current = read_report(pathlib.Path(args.current))
    if not current:
        print(f"no JUnit XML in {args.current} — run the tests first", file=sys.stderr)
        return 1

    acknowledged = load_baseline(pathlib.Path(args.baseline))

    if not args.previous:
        message = "no previous run to compare against — flake analysis skipped"
        if args.allow_missing_previous:
            print(message)
            return 0
        print(message, file=sys.stderr)
        return 1

    previous = read_report(pathlib.Path(args.previous))
    if previous is None:
        message = f"previous run has no JUnit XML in {args.previous}"
        if args.allow_missing_previous:
            print(message)
            return 0
        print(message, file=sys.stderr)
        return 1

    buckets = compare(previous, current)
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

    print(f"compared {len(current)} tests against {len(previous)} in the previous run")
    if known:
        print(f"acknowledged flaky failures: {len(known)}")
    if buckets["recovered"]:
        print(f"flake witnesses (failed in the previous run, passed now): "
              f"{len(buckets['recovered'])}")
        for t in buckets["recovered"]:
            print(f"  {t}")

    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as fh:
            fh.write("\n## Flake analysis (previous run vs this one)\n\n")
            fh.write("\n".join(lines) + "\n" if lines else "No status changes.\n")

    if unacknowledged:
        print("\nUnacknowledged test failures — no entry in "
              "config/docs/flaky-baseline.txt:", file=sys.stderr)
        for t in unacknowledged:
            print(f"  {t}", file=sys.stderr)
        print(
            "\nIf the nondeterminism is understood, acknowledge it in\n"
            "config/docs/flaky-baseline.txt with the reason. If it is not, this is\n"
            "a real failure and the fix belongs in the code, not in the file.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

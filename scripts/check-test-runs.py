#!/usr/bin/env python3
"""check-test-runs.py — fail when a test run executed fewer tests than the baseline.

## The defect this catches

A green test task is not evidence that the tests ran. Gradle reports
`BUILD SUCCESSFUL` whether a suite executed 1500 tests or none, and JUnit's
`includeTags(...)` matches tags **per class**, so an untagged (or
wrongly-engine'd) class is dropped from the run without a warning.

That is not hypothetical. `ci.yml` ran `-Ptest.tags=fast,slow` while only 16 of
218 test classes carried a `@Tag`, and 23 desktopApp classes were JUnit 4 on the
Vintage engine, where `org.junit.jupiter.api.Tag` is invisible to the filter.
CI executed 16 classes in `shared` and **zero** in `desktopApp` and stayed green:
no navigation test and no desktop flow test had ever run.

`TestTagCoverageTest` closes the tag half of that hole at the source level, and
`failOnNoDiscoveredTests` closes the "nothing ran" half. Neither catches a
*partial* skip — 1500 of 1500 → 1400 of 1500 still passes both. This gate does:
it compares the executed counts against a committed baseline and fails when a
source set runs fewer classes or tests than the floor.

The point is that "tests passed" and "the tests ran" are different claims, and
only the second one is worth recording. A verification step that reports
pass/fail without reporting *how much ran* is not a baseline.

## Baseline

`config/docs/test-runs-baseline.txt`, one line per source set:

    <source-set> <classes> <tests> <max-skipped>

Counts only XML reports that exist, so a source set that was never run is
reported as missing rather than silently absent from the comparison.

`classes` and `tests` are **floors**: running fewer means a suite stopped being
selected. `max-skipped` is a **ceiling**: it is 0 across the board, and any
non-zero value is a defect, not a baseline to grow into.

A skipped test is the other silent green. `@Disabled` on a class, or a
`@EnabledIf`/`assumeTrue` guard that starts failing, removes real coverage while
the run still reports the same test count — the count floor cannot see it,
because JUnit counts a skipped testcase in `tests=` exactly like a passing one.
That is how `TaskOutgoingLinksTest` sat `@Disabled` with 15 tests for a month
(ADR `2026-09-25-test-jvm-heap-default`) behind a fully green task.

## Usage

    scripts/check-test-runs.py                    # compare against the baseline
    scripts/check-test-runs.py --quiet            # failures only
    scripts/check-test-runs.py --update-baseline  # after adding tests

Regenerate the baseline whenever tests are *added* (counts go up, which is
fine). Investigate before regenerating whenever they go *down* — a drop means a
class stopped being selected, which is the defect.
"""
import argparse
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASELINE = ROOT / "config" / "docs" / "test-runs-baseline.txt"

#: source set label -> JUnit XML directory, relative to the repo root.
SOURCE_SETS = {
    "shared:jvmTest": "shared/build/test-results/jvmTest",
    "shared:testAndroidHostTest": "shared/build/test-results/testAndroidHostTest",
    "desktopApp:test": "desktopApp/build/test-results/test",
    "mcp-server:test": "mcp-server/build/test-results/test",
}

SUITE_RE = re.compile(r'tests="(\d+)"')


def count(detail_dir: pathlib.Path):
    """(classes, tests, skipped) from the JUnit XML in *detail_dir*, or None if absent."""
    if not detail_dir.is_dir():
        return None
    classes = tests = skipped = 0
    for xml in detail_dir.glob("**/*.xml"):
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        if root.tag != "testsuite":
            continue
        classes += 1
        # Prefer the parsed attribute; fall back to the raw-text scan for reports
        # written by a tool that omits the attribute on the root element.
        total = root.get("tests")
        if total is None:
            match = SUITE_RE.search(xml.read_text(encoding="utf-8", errors="replace")[:2000])
            total = match.group(1) if match else 0
        tests += int(total)
        skipped += int(root.get("skipped", 0) or 0)
    return classes, tests, skipped


def load_baseline(path: pathlib.Path):
    """label -> (classes, tests, max_skipped). A 3-column line means max-skipped 0."""
    entries = {}
    if not path.exists():
        return entries
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) >= 3:
            max_skipped = int(parts[3]) if len(parts) >= 4 else 0
            entries[parts[0]] = (int(parts[1]), int(parts[2]), max_skipped)
    return entries


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="print failures only")
    parser.add_argument("--update-baseline", action="store_true", help="rewrite the baseline")
    parser.add_argument(
        "--require",
        default="",
        help="comma-separated source sets that MUST have produced results in this job "
             "(e.g. shared:jvmTest,desktopApp:test); others are skipped when absent",
    )
    args = parser.parse_args()

    required = {r.strip() for r in args.require.split(",") if r.strip()}

    observed = {}
    for label, rel in SOURCE_SETS.items():
        found = count(ROOT / rel)
        if found:
            observed[label] = found

    if args.update_baseline:
        lines = [
            "# Executed test counts, used as a floor by scripts/check-test-runs.py.",
            "# Format: <source-set> <classes> <tests> <max-skipped>",
            "#",
            "# Record the SMALLEST count any legitimate run produces. The default local",
            "# run (no -Ptest.tags) executes only @Tag(\"fast\") classes, so it is the",
            "# floor; CI's `-Ptest.tags=fast,slow` is a superset and can only be higher.",
            "# Recording the CI numbers here instead would make every plain local run",
            "# look like a regression.",
            "#",
            "# A DROP means a test class stopped being selected — an untagged class, a",
            "# JUnit 4 class on the Vintage engine, or a narrowed filter. Investigate; do",
            "# not regenerate. A RISE just means tests were added.",
            "#",
            "# max-skipped is a CEILING, not a floor, and it is 0. A skipped test is a",
            "# silent green: JUnit counts it in tests= exactly like a passing one, so a",
            "# @Disabled class or a failing assumption guard removes real coverage with no",
            "# other symptom. Do not raise it to make this gate pass — re-enable the test.",
            "#",
            "# Regenerate with: python3 scripts/check-test-runs.py --update-baseline",
        ]
        for label in sorted(observed):
            classes, tests, skipped = observed[label]
            lines.append(f"{label} {classes} {tests} {skipped}")
        BASELINE.parent.mkdir(parents=True, exist_ok=True)
        BASELINE.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"baseline written: {BASELINE.relative_to(ROOT)} ({len(observed)} source sets)")
        for label in sorted(observed):
            classes, tests, skipped = observed[label]
            print(f"  {label}: {classes} classes, {tests} tests, {skipped} skipped")
        return 0

    baseline = load_baseline(BASELINE)
    if not baseline:
        print(f"no baseline at {BASELINE.relative_to(ROOT)} — run --update-baseline", file=sys.stderr)
        return 1

    regressions = []
    for label, (base_classes, base_tests, max_skipped) in sorted(baseline.items()):
        actual = observed.get(label)
        if actual is None:
            # A source set that produced no results at all is only a failure where this
            # job is supposed to produce them; Gradle already fails a job whose own test
            # step failed, so silence elsewhere means "not this job's source set".
            if label in required:
                regressions.append(
                    f"{label}: produced no test results in a job that requires it"
                )
            continue
        classes, tests, skipped = actual
        if classes < base_classes or tests < base_tests:
            regressions.append(
                f"{label}: {classes} classes / {tests} tests, "
                f"baseline {base_classes} / {base_tests} "
                f"(-{base_classes - classes} classes, -{base_tests - tests} tests)"
            )
        if skipped > max_skipped:
            regressions.append(
                f"{label}: {skipped} skipped tests, ceiling {max_skipped} "
                f"(+{skipped - max_skipped}) — a @Disabled class or a failing "
                f"assumption guard is removing coverage silently"
            )

    if not args.quiet:
        for label in sorted(observed):
            classes, tests, skipped = observed[label]
            print(f"{label}: {classes} classes, {tests} tests, {skipped} skipped")

    if regressions:
        print("\nTest runs below the recorded floor — a suite stopped running:", file=sys.stderr)
        for line in regressions:
            print(f"  {line}", file=sys.stderr)
        print(
            "\nUsually an untagged class, a JUnit 4 class on the Vintage engine, or a\n"
            "narrowed -Ptest.tags filter. See TestTagCoverageTest and the note in\n"
            "config/docs/test-runs-baseline.txt before regenerating.",
            file=sys.stderr,
        )
        return 1

    if not args.quiet:
        print("Test run floors met.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

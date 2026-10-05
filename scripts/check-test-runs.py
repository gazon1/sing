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

## The second half, and why the floor was not enough

A floor compares a run against a *number recorded earlier*. That is blind to
the case that actually happens when someone adds a test: the new class is not
in the baseline, so dropping it lowers nothing the floor can see, and the gate
stays green while the test never executes once. The two untagged recurrence
classes were exactly this — invisible to the tag gate whose predicate matched
only `@Test`, absent from a baseline that had not been written yet.

So this gate also asks the question directly: for every `@Tag("fast")` class the
sources declare, is there a JUnit suite by that name in the XML? That is a fact
about the run rather than a comparison, which is why it survives a JUnit
annotation form no text predicate knows about. `fast` is the right scope and not
a convenience: `shared/build.gradle.kts` maps an absent `-Ptest.tags` to
`excludeTags("slow")`, so a plain local run executes exactly the `fast` classes
and CI's `-Ptest.tags=fast,slow` is a superset of them.

The class list is produced by `infra/kiwi/sync.py` — the same scanner that
backs the Kiwi stand — because "is this class runnable" had already been
implemented three times and the copies had drifted apart twice.

The point is that "tests passed" and "the tests ran" are different claims, and
only the second one is worth recording. A verification step that reports
pass/fail without reporting *how much ran* is not a baseline.

## Freshness

The gate reads whatever JUnit XML is on disk, and Gradle only rewrites a source
set's directory when that task actually runs. A partial run therefore leaves the
other sets' results from hours or days earlier, and the floor is satisfied by a run
that never happened — which is the same defect as the one this script exists to
catch, one level down. It bit while recording this baseline: `desktopApp:test` read
27 classes / 77 tests from a leftover `-Ptest.tags=fast,slow` run while a plain
`./gradlew :desktopApp:test` executes 10 / 30.

Two options, because one of them is wrong in a way that only shows up locally:

- `--since <epoch-seconds>` — **strict**: a source set whose newest report predates
  the instant is reported as produced nothing. Correct in CI, where the checkout is
  fresh and every test task runs.
- `--max-age <seconds>` — **tolerant**: the same check with a window. Correct for a
  local loop, and it exists because of a measured failure mode: when a test task is
  UP-TO-DATE, Gradle does not rewrite its results directory, so `--since` marks a
  perfectly valid run as stale. Two consecutive `check.sh` invocations would fail the
  second one for having reused correct results.

`./check.sh` uses `--max-age`; CI uses `--since`.

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
    scripts/check-test-runs.py --since 1770000000    # strict: results must be from this run
    scripts/check-test-runs.py --max-age 21600       # tolerant: results no older than 6h

Regenerate the baseline whenever tests are *added* (counts go up, which is
fine). Investigate before regenerating whenever they go *down* — a drop means a
class stopped being selected, which is the defect.
"""
import argparse
import importlib.util
import pathlib
import re
import sys
import time
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

#: Gradle writes suite names in two shapes and this gate has to read both:
#:
#:   shared      `RecurrenceRuleMapperTest[jvm]`      — simple name, target suffix
#:   desktopApp  `com.singularity.todo.core.ui.menu.MenuBarTest`
#:   mcp-server  `com.singularity.todo.mcp.schema.KoogJsonSchemaBuilderTest`
#:
#: Not a cosmetic difference: comparing a source class name against a
#: fully-qualified report name reports every class as missing, and a check that
#: always fails is a check nobody runs. The target suffix is stripped first,
#: then everything up to the last dot, so both shapes reduce to the simple name.
#: A JUnit `@Nested` suite (`OuterTest$NestedTest`) therefore stays distinct from
#: its outer class rather than colliding with it.
TARGET_SUFFIX_RE = re.compile(r"\[[^\]]+\]$")

#: source set label -> source roots whose *fast* classes it must have executed.
#:
#: Only the Gradle module roots are listed. `TEST_ROOTS` in `infra/kiwi/sync.py`
#: also covers androidHostTest, androidTest and detekt-rules, which are not this
#: gate's source sets — see `androidApp` and `detekt-rules` in SOURCE_SETS.
#:
#: The choice of *fast* is what makes this check true for both callers without a
#: second mode. `shared/build.gradle.kts` translates an absent `-Ptest.tags` to
#: `excludeTags("slow")`, so a plain local run executes every `fast` class, and
#: CI's `-Ptest.tags=fast,slow` is a superset. Checking `slow` as well would
#: fail every local run and mean nothing in CI.
EXPECTED_CLASS_SOURCES = {
    "shared:jvmTest": (
        "shared/src/commonTest/kotlin",
        "shared/src/jvmTest/kotlin",
    ),
    "desktopApp:test": ("desktopApp/src/jvmTest/kotlin",),
    "mcp-server:test": ("mcp-server/src/test/kotlin",),
}


class ScannerUnavailable(RuntimeError):
    """`infra/kiwi/sync.py` could not be loaded, so the by-results check cannot run.

    A dedicated type so the caller can fail loudly instead of skipping. Returning
    None here and treating it as "nothing to check" is the vacuous green this
    gate exists to prevent: measured by appending a failing import to sync.py,
    the gate reported "Test run floors met" and exit 0 while the by-results half
    was not running at all.
    """


def _load_sync():
    """The repository scanner from `infra/kiwi/sync.py`.

    Reusing the one implementation that already answers "is this class runnable"
    is the point. This gate previously asked the same question a third time, and
    the three answers had already drifted apart twice.

    Raises [ScannerUnavailable] rather than returning None. The caller has results
    to check, so "could not check" must be a failure, not an absence.
    """
    kiwi_dir = ROOT / "infra" / "kiwi"
    sync_py = kiwi_dir / "sync.py"
    if not sync_py.is_file():
        raise ScannerUnavailable(f"no repository scanner at {sync_py}")
    # sync.py imports its sibling kiwi_client, so its own directory has to be
    # importable before it is executed — not just its file.
    kiwi_str = str(kiwi_dir)
    added = kiwi_str not in sys.path
    if added:
        sys.path.insert(0, kiwi_str)
    try:
        spec = importlib.util.spec_from_file_location("check_test_runs_sync", sync_py)
        module = importlib.util.module_from_spec(spec)
        module.__name__ = "check_test_runs_sync"
        module.__file__ = str(sync_py)
        # Must precede exec_module: the @dataclass defined there resolves its
        # module through sys.modules[cls.__module__] and fails without this.
        sys.modules["check_test_runs_sync"] = module
        spec.loader.exec_module(module)
    except Exception as exc:  # noqa: BLE001 — any import failure is disqualifying
        sys.modules.pop("check_test_runs_sync", None)
        raise ScannerUnavailable(f"{sync_py} did not import: {exc!r}") from exc
    finally:
        if added:
            sys.path.remove(kiwi_str)
    return module


def expected_fast_classes(label: str):
    """Class names the source declares that are `@Tag("fast")`, or None if out of scope.

    None means *this gate has no opinion about that source set* — there are no
    roots configured for it. It never means "could not check": an unavailable
    scanner raises [ScannerUnavailable] instead, because a check that quietly
    does not run is indistinguishable from a check that passed.
    """
    roots = EXPECTED_CLASS_SOURCES.get(label)
    if not roots:
        return None
    sync = _load_sync()
    names = set()
    for rel in roots:
        root = ROOT / rel
        if not root.is_dir():
            continue
        for kt in root.rglob("*Test.kt"):
            source = kt.read_text(encoding="utf-8", errors="replace")
            for name in sync._test_classes_in(source):  # noqa: SLF001 — one repo, one scanner
                if sync._read_tag(source, name) == "fast":  # noqa: SLF001
                    names.add(name)
    return names


def executed_classes(detail_dir: pathlib.Path) -> set[str]:
    """Every class that produced a JUnit suite in *detail_dir*."""
    executed = set()
    if not detail_dir.is_dir():
        return executed
    for xml in detail_dir.glob("**/*.xml"):
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        if root.tag != "testsuite":
            continue
        name = root.get("name")
        if name:
            executed.add(TARGET_SUFFIX_RE.sub("", name).rsplit(".", 1)[-1])
    return executed


def missing_classes(expected: set[str], executed: set[str]) -> list[str]:
    """Declared-but-not-executed class names, sorted.

    Kept as a free function so the rule can be tested without a Gradle run, a
    temp tree, or the real scanner — the three things that make every other
    assertion in this file slow enough that nobody adds any.
    """
    return sorted(expected - executed)


def newest_report(detail_dir: pathlib.Path):
    """Epoch mtime of the most recent JUnit XML, or None when there is none."""
    if not detail_dir.is_dir():
        return None
    stamps = [xml.stat().st_mtime for xml in detail_dir.glob("**/*.xml")]
    return max(stamps) if stamps else None


def count(detail_dir: pathlib.Path, since: float | None = None, max_age: float | None = None):
    """(classes, tests, skipped) from the JUnit XML in *detail_dir*, or None if absent.

    A source set whose newest report fails the freshness check also returns None: its
    results are from a run that did not happen in this window, and a floor satisfied by
    yesterday's XML is not evidence about today.
    """
    if not detail_dir.is_dir():
        return None
    if since is not None or max_age is not None:
        newest = newest_report(detail_dir)
        if newest is None:
            return None
        if since is not None and newest < since:
            return None
        if max_age is not None and newest < time.time() - max_age:
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
    parser.add_argument(
        "--since",
        type=float,
        default=None,
        metavar="EPOCH",
        help="strict freshness: fail a source set whose newest JUnit report predates "
             "this epoch-seconds stamp",
    )
    parser.add_argument(
        "--max-age",
        type=float,
        default=None,
        metavar="SECONDS",
        help="tolerant freshness: fail a source set whose newest report is older than "
             "this many seconds. Use this rather than --since wherever a Gradle test "
             "task may be UP-TO-DATE and therefore not rewrite its results",
    )
    parser.add_argument("--update-baseline", action="store_true", help="rewrite the baseline")
    parser.add_argument(
        "--require",
        default="",
        help="comma-separated source sets that MUST have produced results in this job "
             "(e.g. shared:jvmTest,desktopApp:test); others are skipped when absent",
    )
    args = parser.parse_args()

    if args.since is not None and args.max_age is not None:
        parser.error("--since and --max-age are mutually exclusive")

    required = {r.strip() for r in args.require.split(",") if r.strip()}

    observed = {}
    stale = []
    for label, rel in SOURCE_SETS.items():
        detail_dir = ROOT / rel
        newest = newest_report(detail_dir)
        if newest is not None:
            if args.since is not None and newest < args.since:
                stale.append(label)
            elif args.max_age is not None and newest < time.time() - args.max_age:
                stale.append(label)
        found = count(detail_dir, since=args.since, max_age=args.max_age)
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
            "# not regenerate. A RISE just means tests were added — but see the warning",
            "# --update-baseline prints on a rise, because a rise measured with",
            "# `-Ptest.tags=fast,slow` is not a floor at all.",
            "#",
            "# max-skipped is a CEILING, not a floor, and it is 0. A skipped test is a",
            "# silent green: JUnit counts it in tests= exactly like a passing one, so a",
            "# @Disabled class or a failing assumption guard removes real coverage with no",
            "# other symptom. Do not raise it to make this gate pass — re-enable the test.",
            "#",
            "# Only source sets that were actually measured are rewritten. A line for a",
            "# source set that did not run is carried over untouched — see the",
            "# --update-baseline section of this file for why deleting it is a defect.",
            "#",
            "# Regenerate with: python3 scripts/check-test-runs.py --update-baseline",
        ]
        previous = load_baseline(BASELINE)
        for label in sorted(observed):
            classes, tests, skipped = observed[label]
            lines.append(f"{label} {classes} {tests} {skipped}")
        for label in sorted(previous):
            if label in observed:
                continue
            classes, tests, max_skipped = previous[label]
            lines.append(f"{label} {classes} {tests} {max_skipped}")
        BASELINE.parent.mkdir(parents=True, exist_ok=True)
        BASELINE.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"baseline written: {BASELINE.relative_to(ROOT)} ({len(observed)} measured)")
        for label in sorted(observed):
            classes, tests, skipped = observed[label]
            print(f"  {label}: {classes} classes, {tests} tests, {skipped} skipped")
        carried = sorted(set(previous) - set(observed))
        if carried:
            print(
                f"  carried over unchanged (not run here): {', '.join(carried)}",
            )
        rises = [
            (label, previous[label], observed[label])
            for label in sorted(observed)
            if label in previous
            # Either dimension rising counts. A tuple comparison was wrong here:
            # `(1, 60) > (5, 50)` is False because the class count fell, so a run
            # that added tests while merging two classes reported no rise and the
            # warning stayed silent. Each dimension has its own direction.
            and (observed[label][0] > previous[label][0]
                 or observed[label][1] > previous[label][1])
        ]
        if rises:
            # A rise is only trustworthy as a floor when it came from the
            # floor-producing configuration — the default run, which excludes
            # @Tag("slow"). Recording a `-Ptest.tags=fast,slow` number here is
            # how this file once held 1003 when the sources contained 998: a
            # floor above the number of tests that exist cannot be satisfied by
            # any real run, and it failed a CI job on its first use.
            print(
                "\nWARNING: these counts ROSE. Confirm the run that produced them was the\n"
                "floor-producing one (no -Ptest.tags, which excludes @Tag(\"slow\")),\n"
                "not `-Ptest.tags=fast,slow`. A floor recorded from a wider run makes\n"
                "every plain local run look like a regression:",
                file=sys.stderr,
            )
            for label, before, after in rises:
                print(
                    f"  {label}: {before[0]}/{before[1]} -> {after[0]}/{after[1]}",
                    file=sys.stderr,
                )
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
                if label in stale:
                    regressions.append(
                        f"{label}: results on disk are older than --since — they are "
                        f"from an earlier run, not this one"
                    )
                else:
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

    # The by-results half. A count floor answers "did fewer things run than
    # last time", which is silent when a *new* class is the one that got
    # skipped: a baseline recorded before the class existed cannot notice its
    # absence. This asks the direct question instead — for every @Tag("fast")
    # class in the sources, did a JUnit suite by that name land in the XML?
    #
    # It is a fact about the run, not a judgement about the source, which is why
    # a form of JUnit annotation this file has never heard of is caught here even
    # if both text predicates miss it.
    for label in sorted(observed):
        if label not in EXPECTED_CLASS_SOURCES:
            continue
        try:
            expected = expected_fast_classes(label)
        except ScannerUnavailable as exc:
            # Loudly, because the alternative — skipping the source set — is a
            # green light wired to nothing. That is the exact failure this gate
            # was written to catch, one level down.
            regressions.append(
                f"{label}: the by-results check could not run — {exc}. A check that "
                f"does not execute is not a passing check."
            )
            continue
        if expected is None:
            continue
        detail_dir = ROOT / SOURCE_SETS[label]
        executed = executed_classes(detail_dir)
        missing = missing_classes(expected, executed)
        if missing:
            regressions.append(
                f"{label}: {len(missing)} @Tag(\"fast\") class(es) declared in the "
                f"sources produced no JUnit report: {', '.join(missing)}"
            )

    if regressions:
        print("\nTest runs below the recorded floor — a suite stopped running:", file=sys.stderr)
        for line in regressions:
            print(f"  {line}", file=sys.stderr)
        print(
            "\nUsually an untagged class, a JUnit 4 class on the Vintage engine, or a\n"
            "narrowed -Ptest.tags filter. See TestTagCoverageTest and the note in\n"
            "config/docs/test-runs-baseline.txt before regenerating.\n\n"
            "A class that is in the sources and absent from the XML was never\n"
            "selected — no count floor can see that when the class is newer than\n"
            "the baseline. Tag it (@Tag(\"fast\")) or find out why JUnit skipped it.",
            file=sys.stderr,
        )
        return 1

    if not args.quiet:
        print("Test run floors met.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

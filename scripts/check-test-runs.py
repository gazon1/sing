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
a convenience. CI always passes an explicit tag list, so the question that
matters there is "did a declared `fast` class run", and
`TestTagCoverageTest` separately holds the untagged population at zero — which
is what makes `fast` the whole of what CI should have run. Checking `slow` as
well would fail every local run, because `shared/build.gradle.kts:290-296` maps
an absent `-Ptest.tags` to `excludeTags("slow")` and a `slow` class is excluded
by that default by design.

Note that the local default and CI's selection are *different*, not nested: the
default also runs untagged classes, and CI's `includeTags` does not. That
distinction was previously written down here as the opposite ("the default runs
exactly the fast classes, and CI is a superset") and it was wrong; the
correction is in ADR `2026-10-05-gate-audit-text-shape-vs-fact`.

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

**`--gradle-log PATH`** — passes the Gradle build log. Any source set whose test
task appears in it with `UP-TO-DATE` or `FROM-CACHE` has its XML from a *previous*
run and is exempt from the freshness check. This prevents a cached task from being
falsely flagged as stale on a second consecutive run.

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

# Reverse index: Gradle task path -> SOURCE_SETS label (built from SOURCE_SETS below).
_TASK_PATH_TO_LABEL: dict[str, str] = {
    ":shared:jvmTest": "shared:jvmTest",
    ":shared:testAndroidHostTest": "shared:testAndroidHostTest",
    ":desktopApp:test": "desktopApp:test",
    ":mcp-server:test": "mcp-server:test",
}

#: Pattern matching a Gradle task outcome line in the build log.
#: "> Task :shared:jvmTest UP-TO-DATE"
#: "> Task :desktopApp:test FROM-CACHE"
TASK_OUTCOME_RE = re.compile(r"^> Task (?P<path>:[\w:.-]+)\s+(?P<outcome>UP-TO-DATE|FROM-CACHE)\s*$")

NOT_EXECUTED = {"UP-TO-DATE", "FROM-CACHE"}


def parse_gradle_log_up_to_date(log_path: pathlib.Path) -> set[str]:
    """Return the set of source-set labels whose test task went UP-TO-DATE or FROM-CACHE.

    When a test task is served from Gradle's cache, it does not rewrite its output
    directory. The XML on disk is therefore older than the job's start time, and a
    freshness check that does not account for this would report every cached task as
    stale — even though the cached result is valid and the staleness is expected.
    """
    up_to_date: set[str] = set()
    try:
        text = log_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return up_to_date
    for line in text.splitlines():
        m = TASK_OUTCOME_RE.match(line)
        if m:
            label = _TASK_PATH_TO_LABEL.get(m.group("path"))
            if label and m.group("outcome") in NOT_EXECUTED:
                up_to_date.add(label)
    return up_to_date


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

#: `--update-baseline` rewrites only what is between these two lines, so the
#: hand-written notes in the file survive a regeneration. Same convention as
#: `Maestro/TAGS.md`.
GENERATED_BEGIN = "# GENERATED:BEGIN — rewritten by --update-baseline; do not hand-edit"
GENERATED_END = "# GENERATED:END"


def prose_outside_block(text: str) -> list[str]:
    """The lines of a baseline file that `--update-baseline` must not touch.

    Everything before GENERATED_BEGIN and after GENERATED_END. On a file with no
    markers yet — the state this shipped in — the whole file counts as prose and is
    returned untouched, so the first run after the markers are introduced cannot lose
    anything either. The caller appends a fresh generated block below it.

    Interior blank lines are kept and only the leading and trailing ones are dropped,
    so regenerating an already-correct file is a no-op down to the byte. A tool that
    reorders or reflows a committed file on every run trains people to distrust its
    diff, which is the thing that makes a real change get missed.
    """
    lines = text.split("\n")
    try:
        start = next(i for i, line in enumerate(lines) if line.startswith(GENERATED_BEGIN[:16]))
    except StopIteration:
        return _trim_blank_edges(lines)
    try:
        end = next(i for i, line in enumerate(lines) if line.startswith(GENERATED_END))
    except StopIteration:
        end = len(lines)
    return _trim_blank_edges(lines[:start]) + _trim_blank_edges(lines[end + 1:])


def _trim_blank_edges(lines: list[str]) -> list[str]:
    start = 0
    while start < len(lines) and not lines[start].strip():
        start += 1
    end = len(lines)
    while end > start and not lines[end - 1].strip():
        end -= 1
    return lines[start:end]

#: source set label -> source roots whose *fast* classes it must have executed.
#:
#: Only the Gradle module roots are listed. `TEST_ROOTS` in `infra/kiwi/sync.py`
#: also covers androidHostTest, androidTest and detekt-rules, which are not this
#: gate's source sets — see `androidApp` and `detekt-rules` in SOURCE_SETS.
#:
#: The choice of *fast* is what makes this check true for both callers without a
#: second mode. CI always passes an explicit tag list, so "did a declared `fast`
#: class run" is the question there, and `TestTagCoverageTest` holds the untagged
#: population at zero. A `local` run additionally covers untagged classes, which
#: is why it is a superset of `fast` rather than equal to it; requiring `slow`
#: would fail every local run, since the absent-`-Ptest.tags` default is
#: `excludeTags("slow")`.
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


def read_run_manifest(detail_dir: pathlib.Path):
    """The manifest `shared/build.gradle.kts` writes beside the XML, or None.

    Absent is normal and means "before this existed", not "filtered": a tree whose test
    task predates the manifest must not be reported as a filtered run. So this returns None
    and every caller treats missing as unknown rather than as a verdict.
    """
    path = detail_dir / "run-manifest.properties"
    if not path.is_file():
        return None
    fields = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        fields[key.strip()] = value.strip()
    return fields


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
    """label -> (tests, max_skipped).

    Two data columns, and the class count is not one of them. It was, until the
    by-results check made it redundant: that check already fails when any declared
    class produced no report, which is the same defect the class floor was there
    to catch, and it catches it by name rather than by subtraction. A class count
    moved with the test count in every remaining case — delete a class, empty a
    class, merge two into one — so `tests` fires wherever `classes` would have, and
    `classes` never fires on its own.

    A 3-column line is still read, and its first number is treated as the test
    count, so an older file degrades to a wrong-but-parsed value rather than to a
    crash mid-run.
    """
    entries = {}
    if not path.exists():
        return entries
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) >= 4:
            # Legacy form: <source-set> <classes> <tests> <max-skipped>. The class
            # column is not read, because it is no longer a floor; the last two
            # numbers are the ones that still mean something.
            entries[parts[0]] = (int(parts[2]), int(parts[3]))
        elif len(parts) == 3:
            entries[parts[0]] = (int(parts[1]), int(parts[2]))
        elif len(parts) == 2:
            entries[parts[0]] = (int(parts[1]), 0)
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
        "--allow-drop",
        action="store_true",
        help="permit --update-baseline to record a LOWER count than the current "
             "floor. Off by default: a drop means tests stopped being selected, "
             "which is a defect to investigate, not a floor to regenerate.",
    )
    parser.add_argument(
        "--require",
        default="",
        help="comma-separated source sets that MUST have produced results in this job "
             "(e.g. shared:jvmTest,desktopApp:test); others are skipped when absent",
    )
    parser.add_argument(
        "--gradle-log",
        type=pathlib.Path,
        default=None,
        metavar="PATH",
        help="path to the Gradle build log. When a source set's test task appears here "
             "with UP-TO-DATE or FROM-CACHE, its JUnit XML is not rewritten and the "
             "--since freshness check is skipped for that set. This prevents a cached "
             "task from being incorrectly flagged as stale.",
    )
    args = parser.parse_args()

    if args.since is not None and args.max_age is not None:
        parser.error("--since and --max-age are mutually exclusive")

    required = {r.strip() for r in args.require.split(",") if r.strip()}

    # Which source sets went UP-TO-DATE or FROM-CACHE in this Gradle run. Their XML
    # on disk is from a previous run and is legitimately older — do not flag as stale.
    up_to_date: set[str] = set()
    if args.gradle_log:
        up_to_date = parse_gradle_log_up_to_date(args.gradle_log)
        if up_to_date:
            print(f"  note: UP-TO-DATE in log: {', '.join(sorted(up_to_date))} — freshness check skipped for these")

    observed = {}
    stale = []
    for label, rel in SOURCE_SETS.items():
        detail_dir = ROOT / rel
        newest = newest_report(detail_dir)
        if newest is not None:
            # A set that went UP-TO-DATE/FROM-CACHE in the log has a legitimately older
            # XML file and must not be flagged as stale.
            if label not in up_to_date:
                if args.since is not None and newest < args.since:
                    stale.append(label)
                elif args.max_age is not None and newest < time.time() - args.max_age:
                    stale.append(label)
        found = count(detail_dir, since=args.since, max_age=args.max_age)
        if found:
            observed[label] = found

    if args.update_baseline:
        # Only the block between the markers is generated. Everything outside it is
        # hand-written and is preserved verbatim.
        #
        # This is not a precaution. The first version of this function rewrote the
        # whole file from a header literal, and the header literal did not contain
        # the notes that had been added to the file afterwards — so regenerating
        # the floor silently deleted the record of the 1003/998 incident, which
        # was the most valuable thing in the file. A generated region inside a
        # hand-maintained file is the only structure that can hold both.
        #
        # The same convention already exists in this repository:
        # `Maestro/TAGS.md` bounds its generated tables with GENERATED:BEND/END
        # markers and a test verifies them.
        header = [
            "# Format: <source-set> <tests> <max-skipped>",
            "#",
            "# No class column. It became redundant once the by-results check existed: that",
            "# check already fails when a declared class produced no report, which is what",
            "# the class floor was for, and it fails by name rather than by subtraction.",
            "# Every other way a class count moves — a class deleted, a class emptied, two",
            "# classes merged — moves the test count too, so this column fires wherever the",
            "# class column would have, and the class column never fired on its own.",
            "#",
            "# Record the SMALLEST count any legitimate run produces. The default local",
            "# run (no -Ptest.tags) excludes @Tag(\"slow\") and therefore runs every",
            "# @Tag(\"fast\") class PLUS any untagged one — which is why it, and not CI,",
            "# is the run to record. CI's `-Ptest.tags=fast,slow` selects tagged classes",
            "# only, so it is *not* a superset of the local run: it is a different",
            "# selection, narrower whenever a class is untagged. TestTagCoverageTest",
            "# is what keeps that population at zero.",
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
            "# (only the GENERATED block below is rewritten; the notes above it are not)",
        ]
        previous = load_baseline(BASELINE)

        # A drop is a defect until shown otherwise, and the baseline file says so in
        # its own header: "A DROP means a test class stopped being selected ...
        # investigate; do not regenerate." The tool did not enforce its own rule.
        #
        # Found by being bitten, on 2026-10-05: a filtered `./gradlew :shared:jvmTest
        # --tests <one class>` leaves one class of XML on disk, and `--update-baseline`
        # read it as a measurement and wrote a floor of **1 test** for a source set that
        # runs 1785. Nothing warned. This is the same incident as the 1003 that the
        # header documents, reached the same way — a results directory read as if it
        # were a full run — and it means the tool will happily record a number no
        # legitimate run produces.
        #
        # So a drop is refused by default and `--allow-drop` is the way to say you
        # meant it. Refusing costs one flag; not refusing costs a floor that can no
        # longer fail, because it is already at 1.
        drops = [
            (label, previous[label][0], observed[label][1])
            for label in sorted(observed)
            if label in previous and observed[label][1] < previous[label][0]
        ]
        if drops and not args.allow_drop:
            print(
                "refusing to rewrite the floor: these counts DROPPED, and a drop "
                "means tests stopped being selected rather than that the floor is "
                "wrong:",
                file=sys.stderr,
            )
            for label, before, after in drops:
                print(
                    f"  {label}: {before} -> {after} tests "
                    f"(-{before - after})",
                    file=sys.stderr,
                )
            print(
                "\nMost often this is a filtered or partial run: `--tests <pattern>` "
                "leaves only the matching\nsuites in the results directory, and "
                "`--update-baseline` reads that as a full run.\nRe-run the whole "
                "source set, or pass --allow-drop if you\nreally did delete tests.",
                file=sys.stderr,
            )
            return 1

        block = [GENERATED_BEGIN]
        for label in sorted(observed):
            _, tests, skipped = observed[label]
            block.append(f"{label} {tests} {skipped}")
        for label in sorted(previous):
            if label in observed:
                continue
            tests, max_skipped = previous[label]
            block.append(f"{label} {tests} {max_skipped}")
        block.append(GENERATED_END)

        if BASELINE.exists():
            # The existing file already carries its own header. The `header` literal
            # above is only for creating a file from scratch — appending it here is
            # how a second, contradictory copy of the same instructions ends up in
            # the file, which is worse than the duplication it was meant to avoid.
            existing = BASELINE.read_text(encoding="utf-8")
            content = prose_outside_block(existing) + block
        else:
            content = header + ["#"] + block
        BASELINE.parent.mkdir(parents=True, exist_ok=True)
        BASELINE.write_text("\n".join(content) + "\n", encoding="utf-8")
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
            if label in previous and observed[label][1] > previous[label][0]
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
                    f"  {label}: {before[0]} -> {after[1]} tests",
                    file=sys.stderr,
                )
        return 0

    baseline = load_baseline(BASELINE)
    if not baseline:
        print(f"no baseline at {BASELINE.relative_to(ROOT)} — run --update-baseline", file=sys.stderr)
        return 1

    regressions = []
    for label, (base_tests, max_skipped) in sorted(baseline.items()):
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
        if tests < base_tests:
            regressions.append(
                f"{label}: {tests} tests in {classes} classes, "
                f"baseline {base_tests} tests (-{base_tests - tests}). "
                f"A drop means tests stopped running: investigate, do not regenerate."
            )
        if skipped > max_skipped:
            regressions.append(
                f"{label}: {skipped} skipped tests, ceiling {max_skipped} "
                f"(+{skipped - max_skipped}) — a @Disabled class or a failing "
                f"assumption guard is removing coverage silently"
            )
        # For non-required sets, do not compare against the floor — the results may be
        # from a previous job's run and the floor reflects a different configuration.
        # Only staleness is meaningful for them (checked above at the `actual is None` branch).
        if label not in required:
            continue

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

        partial = [
            (label, read_run_manifest(ROOT / SOURCE_SETS[label]))
            for label in sorted(observed)
            if label in SOURCE_SETS
        ]
        partial = [(label, m) for label, m in partial if m and m.get("partial") == "true"]

        if partial:
            # The diagnosis, not a better answer (#222).
            #
            # Without this the gate says "a suite stopped running" about a tree where
            # nothing has: `./gw :shared:jvmTest --tests 'OneClass'` is an ordinary
            # development command, it rewrites the XML directory with one class's results,
            # and every gate that reads that directory inherits the damage. Observed twice
            # in one session, including as `check-gate-wiring` refusing to trust the
            # `test-runs` control because the gate was "already failing on a clean tree" —
            # a true sentence that sends the next person to the wrong tree.
            #
            # So: the counts were never evidence, and the fix is to re-run, not to
            # investigate a regression that does not exist.
            print(
                "\nThese runs were FILTERED, so their counts are not evidence about the "
                "suite:",
                file=sys.stderr,
            )
            for label, manifest in partial:
                print(
                    f"  {label}: filters={manifest.get('filters', '?')} "
                    f"({manifest.get('executedClasses', '?')} classes ran)",
                    file=sys.stderr,
                )
            print(
                "Re-run without --tests before reading anything into the numbers above.\n"
                "Gradle leaves no other trace of a narrowed run, which is why "
                "shared/build.gradle.kts writes the manifest next to the XML.",
                file=sys.stderr,
            )
            return 1

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

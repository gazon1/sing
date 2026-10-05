"""Unit tests for check-test-runs.py.

Run with: python3 -m unittest discover -s scripts/tests

The script is the only mechanism that catches a *partial* skip: a green test
task that executed fewer classes than it should. That makes the script itself
load-bearing infrastructure — a regression inside it silently disables the
gate, which is the same failure shape the gate exists to detect. These tests
are the answer to that.
"""

import contextlib
import importlib.util
import io
import os
import pathlib
import sys
import time
import tempfile
import unittest

_scripts_dir = pathlib.Path(__file__).resolve().parent.parent.parent / "scripts"
_spec = importlib.util.spec_from_file_location(
    "check_test_runs", _scripts_dir / "check-test-runs.py"
)
_module = importlib.util.module_from_spec(_spec)
_module.__name__ = "check_test_runs"
_module.__file__ = str(_scripts_dir / "check-test-runs.py")
sys.modules["check_test_runs"] = _module  # must precede exec_module
_spec.loader.exec_module(_module)
import check_test_runs as ctr

SUITE = (
    '<testsuite name="{name}" tests="{tests}" skipped="{skipped}" '
    'failures="0" errors="0" time="0.5"></testsuite>'
)


def write_suite(directory: pathlib.Path, name: str, tests: int, skipped: int = 0) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"TEST-{name}.xml").write_text(
        SUITE.format(name=name, tests=tests, skipped=skipped), encoding="utf-8"
    )


class TestCount(unittest.TestCase):
    def test_absent_directory_is_none_not_zero(self):
        """A source set that never ran must be 'missing', not 'ran nothing'.

        Reporting zero would satisfy a floor of zero and hide a whole job.
        """
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(ctr.count(pathlib.Path(tmp) / "nope"))

    def test_sums_classes_tests_and_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            write_suite(d, "A", tests=10, skipped=0)
            write_suite(d, "B", tests=5, skipped=3)
            self.assertEqual(ctr.count(d), (2, 15, 3))

    def test_non_testsuite_root_is_ignored(self):
        """A stray XML in the results dir must not count as a class."""
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            write_suite(d, "A", tests=10)
            (d / "TEST-other.xml").write_text(
                '<?xml version="1.0"?><testsuites><testsuite tests="99">'
                "</testsuite></testsuites>",
                encoding="utf-8",
            )
            self.assertEqual(ctr.count(d), (1, 10, 0))

    def test_skipped_testcase_is_counted_in_tests(self):
        """Documents the blind spot the ceiling exists for.

        JUnit reports a skipped testcase inside tests=, which is why a test-count
        floor alone cannot see a @Disabled class lose 15 tests of coverage.
        """
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            write_suite(d, "A", tests=15, skipped=15)
            classes, tests, skipped = ctr.count(d)
            self.assertEqual((classes, tests, skipped), (1, 15, 15))


class TestBaselineParsing(unittest.TestCase):
    """The baseline is two data columns: <tests> <max-skipped>.

    The class count was dropped once the by-results check made it redundant, and
    the legacy 4-column form is still read so an older file degrades to a
    parsed-but-shifted value rather than to a crash mid-run.
    """

    def test_two_column_line_defaults_skipped_ceiling_to_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp) / "b.txt"
            p.write_text("# comment\nshared:jvmTest 1519\n", encoding="utf-8")
            self.assertEqual(ctr.load_baseline(p), {"shared:jvmTest": (1519, 0)})

    def test_three_column_form_keeps_its_ceiling(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp) / "b.txt"
            p.write_text("shared:jvmTest 1519 2\n", encoding="utf-8")
            self.assertEqual(ctr.load_baseline(p), {"shared:jvmTest": (1519, 2)})

    def test_legacy_four_column_line_is_read_as_tests_and_ceiling(self):
        """A pre-2-column file must not crash, and must not silently keep a
        class count as if it were a test count."""
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp) / "b.txt"
            p.write_text("shared:jvmTest 190 1519 0\n", encoding="utf-8")
            self.assertEqual(
                ctr.load_baseline(p),
                {"shared:jvmTest": (1519, 0)},
                "the legacy <classes> <tests> <max-skipped> line must resolve to the "
                "test count, not to the class count",
            )

    def test_missing_file_is_empty_not_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(ctr.load_baseline(pathlib.Path(tmp) / "absent"), {})


class TestFreshness(unittest.TestCase):
    """A floor satisfied by yesterday's XML is not evidence about today."""

    def test_newest_report_is_none_when_absent(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(ctr.newest_report(pathlib.Path(tmp)))

    def test_count_ignores_results_older_than_since(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            write_suite(d, "A", tests=10)
            old = time.time() - 7200
            for xml in d.glob("*.xml"):
                os.utime(xml, (old, old))
            self.assertIsNone(ctr.count(d, since=time.time() - 3600))
            self.assertEqual(ctr.count(d, since=time.time() - 10800), (1, 10, 0))

    def test_count_without_since_ignores_staleness(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            write_suite(d, "A", tests=10)
            old = time.time() - 86400
            for xml in d.glob("*.xml"):
                os.utime(xml, (old, old))
            self.assertEqual(ctr.count(d), (1, 10, 0))

    def test_max_age_tolerates_an_up_to_date_run(self):
        """A UP-TO-DATE task does not rewrite its results — that is not staleness.

        This is the case --since cannot serve: the results are correct for the current
        inputs, they are simply old, and a strict check fails the second consecutive
        local run for it.
        """
        saved = (ctr.ROOT, ctr.BASELINE, ctr.SOURCE_SETS, ctr.EXPECTED_CLASS_SOURCES, sys.argv)
        tmp = pathlib.Path(tempfile.mkdtemp())
        ctr.ROOT = tmp
        ctr.BASELINE = tmp / "baseline.txt"
        ctr.SOURCE_SETS = {"shared:jvmTest": "results/jvmTest"}
        # Out of scope: a temp ROOT has no repository scanner. These tests are
        # about the freshness window, not the by-results comparison.
        ctr.EXPECTED_CLASS_SOURCES = {}
        try:
            d = tmp / "results" / "jvmTest"
            write_suite(d, "A", tests=50)
            ctr.BASELINE.write_text("shared:jvmTest 1 50 0\n", encoding="utf-8")
            old = time.time() - 3600
            for xml in d.glob("*.xml"):
                os.utime(xml, (old, old))
            sys.argv = ["check-test-runs.py", "--quiet", "--max-age", "21600"]
            self.assertEqual(ctr.main(), 0)
            # Without --require a silent source set is not this job's business; the
            # strict form only bites where the job promised to produce it.
            sys.argv = [
                "check-test-runs.py", "--quiet",
                "--require", "shared:jvmTest", "--since", str(time.time()),
            ]
            self.assertEqual(ctr.main(), 1)
        finally:
            ctr.ROOT, ctr.BASELINE, ctr.SOURCE_SETS, ctr.EXPECTED_CLASS_SOURCES, sys.argv = saved

    def test_since_and_max_age_are_mutually_exclusive(self):
        saved = sys.argv
        sys.argv = ["check-test-runs.py", "--since", "1", "--max-age", "1"]
        try:
            with self.assertRaises(SystemExit):
                ctr.main()
        finally:
            sys.argv = saved

    def test_stale_results_fail_a_required_source_set(self):
        saved = (ctr.ROOT, ctr.BASELINE, ctr.SOURCE_SETS, ctr.EXPECTED_CLASS_SOURCES, sys.argv)
        tmp = pathlib.Path(tempfile.mkdtemp())
        ctr.ROOT = tmp
        ctr.BASELINE = tmp / "baseline.txt"
        ctr.SOURCE_SETS = {"shared:jvmTest": "results/jvmTest"}
        # Out of scope: a temp ROOT has no repository scanner. These tests are
        # about the freshness window, not the by-results comparison.
        ctr.EXPECTED_CLASS_SOURCES = {}
        try:
            d = tmp / "results" / "jvmTest"
            write_suite(d, "A", tests=50)
            ctr.BASELINE.write_text("shared:jvmTest 1 50 0\n", encoding="utf-8")
            old = time.time() - 7200
            for xml in d.glob("*.xml"):
                os.utime(xml, (old, old))
            sys.argv = [
                "check-test-runs.py", "--require", "shared:jvmTest",
                "--since", str(time.time() - 3600), "--quiet",
            ]
            self.assertEqual(ctr.main(), 1)
        finally:
            ctr.ROOT, ctr.BASELINE, ctr.SOURCE_SETS, ctr.EXPECTED_CLASS_SOURCES, sys.argv = saved


class TestGate(unittest.TestCase):
    """End-to-end over a temp ROOT, exercising main()'s exit code."""

    def setUp(self):
        self._saved = (
            ctr.ROOT,
            ctr.BASELINE,
            ctr.SOURCE_SETS,
            ctr.EXPECTED_CLASS_SOURCES,
            sys.argv,
        )
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        ctr.ROOT = self.tmp
        ctr.BASELINE = self.tmp / "baseline.txt"
        ctr.SOURCE_SETS = {"shared:jvmTest": "results/jvmTest"}
        # These tests are about the count floor and the freshness window, not the
        # by-results half, and a temp ROOT has no repository scanner to read. The
        # by-results check has its own class below; see ScannerFailureIsLoudTest
        # for what happens when a scanner that *should* exist does not.
        ctr.EXPECTED_CLASS_SOURCES = {}
        # main() parses sys.argv; under unittest that is the runner's own argv.
        sys.argv = ["check-test-runs.py"]
        self.addCleanup(self._restore)

    def _restore(self):
        (
            ctr.ROOT,
            ctr.BASELINE,
            ctr.SOURCE_SETS,
            ctr.EXPECTED_CLASS_SOURCES,
            sys.argv,
        ) = self._saved

    def _baseline(self, text: str) -> None:
        ctr.BASELINE.write_text(text, encoding="utf-8")

    def _results(self, classes: int, tests: int, skipped: int = 0) -> pathlib.Path:
        d = self.tmp / "results" / "jvmTest"
        for i in range(classes):
            write_suite(d, f"C{i}", tests=max(tests // classes, 0), skipped=0)
        if skipped:
            # attribute belongs to one suite; rewrite the last one
            last = d / f"TEST-C{classes - 1}.xml"
            last.write_text(
                SUITE.format(name=f"C{classes - 1}", tests=tests // classes, skipped=skipped),
                encoding="utf-8",
            )
        return d

    def test_drop_below_floor_fails(self):
        self._results(2, 20)
        self._baseline("shared:jvmTest 5 50 0\n")
        self.assertEqual(ctr.main(), 1)

    def test_rise_above_floor_passes(self):
        self._results(5, 50)
        self._baseline("shared:jvmTest 2 20 0\n")
        self.assertEqual(ctr.main(), 0)

    def test_equal_to_floor_passes(self):
        self._results(5, 50)
        self._baseline("shared:jvmTest 5 50 0\n")
        self.assertEqual(ctr.main(), 0)

    def test_skips_above_ceiling_fail_even_at_full_count(self):
        """The whole point: counts are intact, coverage is gone."""
        self._results(5, 50, skipped=7)
        self._baseline("shared:jvmTest 5 50 0\n")
        self.assertEqual(ctr.main(), 1)

    def test_missing_results_fail_only_when_required(self):
        self._baseline("shared:jvmTest 1 1 0\n")
        self.assertEqual(ctr.main(), 0)
        saved_argv = sys.argv
        sys.argv = ["check-test-runs.py", "--require", "shared:jvmTest"]
        try:
            self.assertEqual(ctr.main(), 1)
        finally:
            sys.argv = saved_argv

    def test_absent_baseline_fails_loudly(self):
        self._results(5, 50)
        self.assertEqual(ctr.main(), 1)


class MissingClassesTest(unittest.TestCase):
    """The by-results half: a class in the sources that produced no report.

    A count floor cannot see this. It compares a run against a number recorded
    earlier, so a class added *after* the baseline was written can be skipped
    entirely and the floor still holds — which is precisely how the two untagged
    recurrence classes stayed invisible.
    """

    def test_class_present_in_both_is_not_missing(self):
        self.assertEqual(ctr.missing_classes({"A"}, {"A"}), [])

    def test_class_declared_but_not_executed_is_missing(self):
        self.assertEqual(ctr.missing_classes({"A", "B"}, {"A"}), ["B"])

    def test_extra_executed_classes_are_not_reported(self):
        """A nested or dynamically-generated suite is not a source class."""
        self.assertEqual(ctr.missing_classes({"A"}, {"A", "OuterTest$NestedTest"}), [])

    def test_missing_is_sorted_for_stable_output(self):
        self.assertEqual(ctr.missing_classes({"C", "A", "B"}, set()), ["A", "B", "C"])


class SuiteNameNormalisationTest(unittest.TestCase):
    """Gradle writes two different suite-name shapes; both must reduce alike.

    A check that always fails is a check nobody runs, so the normalisation is
    pinned rather than assumed.
    """

    def _names(self, suite_names: list[str]) -> set[str]:
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            for i, name in enumerate(suite_names):
                (d / f"TEST-{i}.xml").write_text(
                    f'<testsuite name="{name}" tests="1" skipped="0"></testsuite>',
                    encoding="utf-8",
                )
            return ctr.executed_classes(d)

    def test_simple_name_with_target_suffix(self):
        """What `shared` writes."""
        self.assertEqual(self._names(["RruleGeneratorTest[jvm]"]), {"RruleGeneratorTest"})

    def test_fully_qualified_name(self):
        """What `desktopApp` and `mcp-server` write."""
        self.assertEqual(
            self._names(["com.singularity.todo.core.ui.menu.MenuBarTest"]),
            {"MenuBarTest"},
        )

    def test_both_shapes_reduce_to_the_same_class(self):
        """The real requirement: a source name matches either report shape."""
        self.assertEqual(
            self._names(
                [
                    "MenuBarTest[jvm]",
                    "com.singularity.todo.core.ui.menu.MenuBarTest",
                ]
            ),
            {"MenuBarTest"},
        )

    def test_nested_suite_stays_distinct_from_its_outer_class(self):
        self.assertEqual(
            self._names(["com.x.OuterTest$NestedTest"]),
            {"OuterTest$NestedTest"},
        )

    def test_empty_and_malformed_reports_are_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            (d / "TEST-broken.xml").write_text("<not xml", encoding="utf-8")
            (d / "TEST-ok.xml").write_text(
                '<testsuite name="OkTest" tests="1"></testsuite>', encoding="utf-8"
            )
            self.assertEqual(ctr.executed_classes(d), {"OkTest"})
            self.assertEqual(ctr.executed_classes(d / "nope"), set())


class ExpectedFastClassesTest(unittest.TestCase):
    """The source half, against the real repository.

    This is the assertion that keeps the sentinel from decaying into a no-op: it
    depends on `infra/kiwi/sync.py` loading, and a scanner that fails to import
    must be a failure rather than a check that quietly stops running.
    """

    def test_scanner_loads_for_the_real_repo(self):
        self.assertIsNotNone(
            ctr._load_sync(),  # noqa: SLF001
            "infra/kiwi/sync.py did not load",
        )

    def test_shared_jvmtest_declares_a_non_trivial_set_of_fast_classes(self):
        found = ctr.expected_fast_classes("shared:jvmTest")
        self.assertIsNotNone(found)
        self.assertGreater(
            len(found),
            100,
            f"only {len(found or ())} fast classes found in shared — the scanner is "
            "probably not reading the source tree, which would make the check vacuous",
        )

    def test_the_two_annotated_recurrence_classes_are_in_the_expected_set(self):
        """The classes this whole mechanism was built for."""
        found = ctr.expected_fast_classes("shared:jvmTest")
        self.assertIn("RecurrenceRuleMapperTest", found)
        self.assertIn("RruleGeneratorTest", found)

    def test_unknown_source_set_yields_none_not_an_empty_set(self):
        """"Out of scope" and "there is nothing to check" must not look alike."""
        self.assertIsNone(ctr.expected_fast_classes("no:such-source-set"))


class ScannerFailureIsLoudTest(unittest.TestCase):
    """A scanner that will not import must fail the gate, not disable it.

    Measured, not hypothetical: appending a failing import to `infra/kiwi/sync.py`
    made the gate print "Test run floors met" and exit 0 while the by-results
    check was not running at all. The gate was reporting a verdict on a check it
    had stopped performing — the same failure it exists to catch, one level down.
    """

    def setUp(self):
        self._saved = (ctr.ROOT, ctr.BASELINE, ctr.SOURCE_SETS, sys.argv)
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        ctr.ROOT = self.tmp
        ctr.BASELINE = self.tmp / "baseline.txt"
        ctr.SOURCE_SETS = {"shared:jvmTest": "results/jvmTest"}
        sys.argv = ["check-test-runs.py"]
        self.addCleanup(self._restore)

    def _restore(self):
        ctr.ROOT, ctr.BASELINE, ctr.SOURCE_SETS, sys.argv = self._saved

    def test_missing_scanner_raises_rather_than_returning_none(self):
        with self.assertRaises(ctr.ScannerUnavailable):
            ctr.expected_fast_classes("shared:jvmTest")

    def test_unimportable_scanner_fails_the_gate(self):
        d = self.tmp / "results" / "jvmTest"
        write_suite(d, "KeptTest", tests=50)
        ctr.BASELINE.write_text("shared:jvmTest 1 50 0\n", encoding="utf-8")
        # Counts sit exactly on their floor, so only the by-results half can fail.
        self.assertEqual(ctr.main(), 1)


class ByResultsEndToEndTest(unittest.TestCase):
    """main() must fail when a declared class produced no report.

    The unit tests above pin the comparison; this one pins the wiring, because a
    check whose result is computed and then never used looks exactly like a
    working check from the outside.
    """

    def setUp(self):
        self._saved = (
            ctr.ROOT,
            ctr.BASELINE,
            ctr.SOURCE_SETS,
            ctr.EXPECTED_CLASS_SOURCES,
            ctr.expected_fast_classes,
            sys.argv,
        )
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        ctr.ROOT = self.tmp
        ctr.BASELINE = self.tmp / "baseline.txt"
        ctr.SOURCE_SETS = {"shared:jvmTest": "results/jvmTest"}
        ctr.EXPECTED_CLASS_SOURCES = {"shared:jvmTest": ("src",)}
        sys.argv = ["check-test-runs.py"]
        self.addCleanup(self._restore)

    def _restore(self):
        (
            ctr.ROOT,
            ctr.BASELINE,
            ctr.SOURCE_SETS,
            ctr.EXPECTED_CLASS_SOURCES,
            ctr.expected_fast_classes,
            sys.argv,
        ) = self._saved

    def _stub_expected(self, *names: str) -> None:
        ctr.expected_fast_classes = lambda _label: set(names)  # noqa: ARG005

    def test_counts_meeting_the_floor_still_fail_on_a_missing_class(self):
        """The whole reason this check exists.

        The counts are exactly at their floor, so every legacy assertion in this
        file is satisfied. Only the by-results comparison can fail here.
        """
        d = self.tmp / "results" / "jvmTest"
        d.mkdir(parents=True)
        write_suite(d, "KeptTest", tests=50)
        self._stub_expected("KeptTest", "VanishedTest")
        ctr.BASELINE.write_text("shared:jvmTest 1 50 0\n", encoding="utf-8")
        self.assertEqual(ctr.main(), 1)

    def test_every_declared_class_executed_passes(self):
        d = self.tmp / "results" / "jvmTest"
        d.mkdir(parents=True)
        write_suite(d, "KeptTest", tests=50)
        self._stub_expected("KeptTest")
        ctr.BASELINE.write_text("shared:jvmTest 1 50 0\n", encoding="utf-8")
        self.assertEqual(ctr.main(), 0)

    def test_fully_qualified_report_name_satisfies_a_source_class_name(self):
        """Regression: the first version compared simple names to FQNs and
        reported every desktopApp and mcp-server class as missing."""
        d = self.tmp / "results" / "jvmTest"
        d.mkdir(parents=True)
        (d / "TEST-x.xml").write_text(
            '<testsuite name="com.singularity.todo.mcp.schema.KoogJsonSchemaBuilderTest"'
            ' tests="7" skipped="0"></testsuite>',
            encoding="utf-8",
        )
        self._stub_expected("KoogJsonSchemaBuilderTest")
        ctr.BASELINE.write_text("shared:jvmTest 1 7 0\n", encoding="utf-8")
        self.assertEqual(ctr.main(), 0)


class UpdateBaselineTest(unittest.TestCase):
    """`--update-baseline` must not destroy floors it did not measure.

    Measured before this was fixed: running it on a machine with no device
    deleted `shared:testAndroidHostTest 117 998 0` outright, because the writer
    emitted only the source sets it happened to observe. That is the same
    failure this file's own header documents — a baseline number that was never
    a measurement of a real run, which then failed a CI job on first use.
    """

    def setUp(self):
        self._saved = (
            ctr.ROOT,
            ctr.BASELINE,
            ctr.SOURCE_SETS,
            ctr.EXPECTED_CLASS_SOURCES,
            sys.argv,
        )
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        ctr.ROOT = self.tmp
        ctr.BASELINE = self.tmp / "baseline.txt"
        ctr.SOURCE_SETS = {
            "shared:jvmTest": "results/jvmTest",
            "shared:testAndroidHostTest": "results/host",
        }
        ctr.EXPECTED_CLASS_SOURCES = {}
        sys.argv = ["check-test-runs.py", "--update-baseline"]
        self.addCleanup(self._restore)

    def _restore(self):
        (
            ctr.ROOT,
            ctr.BASELINE,
            ctr.SOURCE_SETS,
            ctr.EXPECTED_CLASS_SOURCES,
            sys.argv,
        ) = self._saved

    def _write_baseline(self) -> None:
        ctr.BASELINE.write_text(
            "shared:jvmTest 50 0\n"
            "shared:testAndroidHostTest 998 0\n",
            encoding="utf-8",
        )

    def test_a_source_set_that_did_not_run_keeps_its_floor(self):
        self._write_baseline()
        d = self.tmp / "results" / "jvmTest"
        write_suite(d, "C0", tests=60)
        self.assertEqual(ctr.main(), 0)
        text = ctr.BASELINE.read_text(encoding="utf-8")
        self.assertIn("shared:testAndroidHostTest 998 0", text)
        self.assertIn("shared:jvmTest 60 0", text)

    def test_a_rise_is_reported_to_stderr(self):
        """A rise measured from `-Ptest.tags=fast,slow` is not a floor.

        The warning is the whole point: writing a wider run's numbers into a
        floor makes every plain local run look like a regression, which is how
        1003 once got recorded for a source set containing 998 tests.
        """
        self._write_baseline()
        d = self.tmp / "results" / "jvmTest"
        write_suite(d, "C0", tests=60)
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            self.assertEqual(ctr.main(), 0)
        self.assertIn("ROSE", stderr.getvalue())
        self.assertIn("shared:jvmTest", stderr.getvalue())

    def test_hand_written_notes_survive_a_regeneration(self):
        """The regression that produced the markers.

        `--update-baseline` used to rewrite the whole file from a header literal
        held in the script. The notes added to the file afterwards were not in that
        literal, so a routine regeneration deleted them — measured: it destroyed the
        record of the 1003/998 incident, which was the most valuable thing in the
        file. The generated block is now bounded by markers and the prose outside is
        preserved.
        """
        ctr.BASELINE.write_text(
            "# Executed test counts.\n"
            "# A hand-written note about the 1003/998 incident.\n"
            f"{ctr.GENERATED_BEGIN}\n"
            "shared:jvmTest 50 0\n"
            f"{ctr.GENERATED_END}\n",
            encoding="utf-8",
        )
        d = self.tmp / "results" / "jvmTest"
        write_suite(d, "C0", tests=60)
        self.assertEqual(ctr.main(), 0)
        text = ctr.BASELINE.read_text(encoding="utf-8")
        self.assertIn("1003/998 incident", text)
        self.assertIn("shared:jvmTest 60 0", text)

    def test_regenerating_an_already_correct_file_changes_nothing(self):
        """A committed file that reflows on every run trains people to ignore its diff."""
        d = self.tmp / "results" / "jvmTest"
        write_suite(d, "C0", tests=60)
        self.assertEqual(ctr.main(), 0)
        first = ctr.BASELINE.read_text(encoding="utf-8")
        self.assertEqual(ctr.main(), 0)
        self.assertEqual(
            ctr.BASELINE.read_text(encoding="utf-8"),
            first,
            "a second --update-baseline rewrote the file; regeneration must be a no-op "
            "on an already-correct file",
        )

    def test_a_legacy_file_without_markers_loses_nothing(self):
        """The state this shipped in: no markers, whole file is prose."""
        ctr.BASELINE.write_text(
            "# legacy header\n"
            "# a note from before the markers existed\n"
            "shared:jvmTest 50 0\n"
            "shared:testAndroidHostTest 998 0\n",
            encoding="utf-8",
        )
        d = self.tmp / "results" / "jvmTest"
        write_suite(d, "C0", tests=60)
        self.assertEqual(ctr.main(), 0)
        text = ctr.BASELINE.read_text(encoding="utf-8")
        self.assertIn("a note from before the markers existed", text)
        self.assertIn("shared:testAndroidHostTest 998 0", text)
        self.assertIn(ctr.GENERATED_BEGIN, text)
        self.assertIn(ctr.GENERATED_END, text)

    def test_a_drop_is_refused_rather_than_written(self):
        """The tool must not record a floor that no legitimate run produces.

        Found by being bitten: a filtered `--tests <one class>` run leaves one
        class of XML on disk and `--update-baseline` wrote a floor of 1 test for
        a source set that runs 1785, with no warning. The baseline file's own
        header says a drop means "investigate; do not regenerate" — and the tool
        regenerated one silently.
        """
        ctr.BASELINE.write_text("shared:jvmTest 1785 0\n", encoding="utf-8")
        d = self.tmp / "results" / "jvmTest"
        write_suite(d, "C0", tests=1)
        self.assertEqual(ctr.main(), 1)
        self.assertIn(
            "shared:jvmTest 1785 0",
            ctr.BASELINE.read_text(encoding="utf-8"),
            "the floor was rewritten downwards despite the drop",
        )

    def test_allow_drop_permits_a_deliberate_drop(self):
        ctr.BASELINE.write_text("shared:jvmTest 1785 0\n", encoding="utf-8")
        d = self.tmp / "results" / "jvmTest"
        write_suite(d, "C0", tests=1)
        saved = sys.argv
        sys.argv = ["check-test-runs.py", "--update-baseline", "--allow-drop"]
        try:
            self.assertEqual(ctr.main(), 0)
        finally:
            sys.argv = saved
        self.assertIn("shared:jvmTest 1 0", ctr.BASELINE.read_text(encoding="utf-8"))

    def test_a_rise_is_still_written_without_the_flag(self):
        ctr.BASELINE.write_text("shared:jvmTest 50 0\n", encoding="utf-8")
        d = self.tmp / "results" / "jvmTest"
        write_suite(d, "C0", tests=60)
        self.assertEqual(ctr.main(), 0)
        self.assertIn("shared:jvmTest 60 0", ctr.BASELINE.read_text(encoding="utf-8"))

    def test_a_drop_is_not_reported_as_a_rise(self):
        self._write_baseline()
        d = self.tmp / "results" / "jvmTest"
        write_suite(d, "C0", tests=10)
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            ctr.main()
        self.assertNotIn("ROSE", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()

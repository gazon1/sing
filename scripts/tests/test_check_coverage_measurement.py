"""Self-tests for check-coverage-measurement.py.

The gate exists because a coverage number can be produced from a run that executed nothing, and
the number does not say so. A test for such a gate has one job above all: prove the gate goes
red on the case it exists for.

The cases that matter most are the two that a naive implementation gets wrong:

  * **a log with no test task at all** passes a naive "look for UP-TO-DATE" implementation. It
    must fail — that is the case where the ratchet would report "all floors held" having
    measured nothing;
  * **a log with no outcome word** is what an *executed* task looks like. Treating a missing
    outcome as a failure would make the gate red on every healthy run, which is how a gate
    trains people to pass `--allow-cached` and then stop reading it.

No Gradle and no coverage data: the gate's whole input is a log, so the tests are log fixtures.
"""

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

_spec = importlib.util.spec_from_file_location(
    "check_coverage_measurement", SCRIPTS / "check-coverage-measurement.py"
)
subject = importlib.util.module_from_spec(_spec)
# Registered before exec_module: the script declares a @dataclass, and dataclasses resolves
# annotations through sys.modules[cls.__module__] — which is None if the module was never
# registered. A sibling test in this directory loads a script without dataclasses and does not
# need this, so the difference looks like a mystery rather than a cause.
sys.modules[_spec.name] = subject
_spec.loader.exec_module(subject)


EXECUTED = """
> Task :shared:compileKotlinJvm
> Task :shared:jvmTest
> Task :shared:koverXmlReport
BUILD SUCCESSFUL in 9m27s
"""

UP_TO_DATE = """
> Task :shared:compileKotlinJvm UP-TO-DATE
> Task :shared:jvmTest UP-TO-DATE
BUILD SUCCESSFUL in 12s
"""

FROM_CACHE = """
> Task :shared:jvmTest FROM-CACHE
> Task :desktopApp:test FROM-CACHE
BUILD SUCCESSFUL in 8s
"""

NO_TEST_TASK = """
> Task :shared:compileKotlinJvm
> Task :shared:koverXmlReport
BUILD SUCCESSFUL in 3s
"""

MIXED = """
> Task :shared:jvmTest
> Task :detekt-rules:test FROM-CACHE
BUILD SUCCESSFUL in 7m02s
"""

NO_SOURCE = """
> Task :shared:jvmTest
> Task :mcp-server:test NO-SOURCE
BUILD SUCCESSFUL in 6m55s
"""

NON_TEST_TASKS_UP_TO_DATE = """
> Task :shared:compileKotlinJvm UP-TO-DATE
> Task :shared:detekt UP-TO-DATE
> Task :shared:jvmTest
BUILD SUCCESSFUL in 9m30s
"""

# The real log from `just cr`, reduced. Every one of these is an AGP resource task named after
# the test variant it serves; the first version of this gate reported 18 of the 19 findings.
AGP_RESOURCE_TASKS = """
> Task :shared:convertXmlValueResourcesForAndroidHostTest NO-SOURCE
> Task :shared:copyNonXmlValueResourcesForCommonTest NO-SOURCE
> Task :shared:prepareComposeResourcesTaskForJvmTest NO-SOURCE
> Task :shared:generateResourceAccessorsForCommonTest NO-SOURCE
> Task :androidApp:javaPreCompileDebugUnitTest UP-TO-DATE
> Task :desktopApp:convertXmlValueResourcesForTest NO-SOURCE
> Task :androidApp:testDebugUnitTest NO-SOURCE
> Task :shared:jvmTest
BUILD SUCCESSFUL in 9m31s
"""


class ParseTests(unittest.TestCase):
    def test_an_executed_task_has_no_outcome_word(self):
        observations = subject.parse(EXECUTED)
        self.assertEqual([(o.path, o.outcome) for o in observations], [(":shared:jvmTest", None)])

    def test_outcomes_are_read(self):
        self.assertEqual(subject.parse(UP_TO_DATE)[0].outcome, "UP-TO-DATE")
        self.assertEqual(subject.parse(FROM_CACHE)[0].outcome, "FROM-CACHE")

    def test_non_test_tasks_are_not_observations(self):
        # `detekt` ends in neither `test` nor `Test`; `compileKotlinJvm` is not a test task.
        # Judging those would report a healthy cached build as a coverage problem.
        paths = [o.path for o in subject.parse(NON_TEST_TASKS_UP_TO_DATE)]
        self.assertEqual(paths, [":shared:jvmTest"])

    def test_agp_resource_tasks_named_after_test_variants_are_not_test_tasks(self):
        # This is the false-positive class that made the first version unusable: `.*[Tt]est$`
        # matches every one of these resource tasks, and not one of them runs a test.
        #
        # `testDebugUnitTest` is deliberately still in the list: it *is* a real test task, just
        # one with no sources, and it belongs in the parse as a warning rather than being
        # filtered out or promoted to a failure.
        paths = [o.path for o in subject.parse(AGP_RESOURCE_TASKS)]
        self.assertEqual(
            paths,
            [":androidApp:testDebugUnitTest", ":shared:jvmTest"],
            "only the two real test tasks; the seven AGP resource tasks must not be judged",
        )

    def test_a_real_variant_unit_test_is_still_a_test_task(self):
        log = "> Task :androidApp:testDebugUnitTest\n> Task :app:testReleaseUnitTest FROM-CACHE\n"
        paths = [o.path for o in subject.parse(log)]
        self.assertEqual(paths, [":androidApp:testDebugUnitTest", ":app:testReleaseUnitTest"])

    def test_a_line_that_is_not_a_task_line_is_ignored(self):
        log = "some noise\n> Task :shared:jvmTest\ngarbage > Task :x\n"
        self.assertEqual(len(subject.parse(log)), 1)


class CheckTests(unittest.TestCase):
    def assertFails(self, log, allow_cached=False):
        errors, _, _ = subject.check(log, allow_cached=allow_cached)
        self.assertTrue(errors, "expected this log to fail the gate")

    def assertPasses(self, log):
        errors, _, _ = subject.check(log)
        self.assertEqual(errors, [], "expected this log to pass the gate")

    def test_an_executed_run_passes(self):
        self.assertPasses(EXECUTED)

    def test_up_to_date_fails(self):
        self.assertFails(UP_TO_DATE)

    def test_from_cache_fails(self):
        self.assertFails(FROM_CACHE)

    def test_no_test_task_fails(self):
        # The case a naive implementation gets wrong. Nothing ran, so the report is empty of
        # measurement, and a ratchet comparing floors against it would look green.
        self.assertFails(NO_TEST_TASK)

    def test_a_real_run_passes_despite_a_wall_of_resource_tasks(self):
        # The whole log, not a fixture: the gate has to be green on the run it was written for.
        self.assertPasses(AGP_RESOURCE_TASKS)

    def test_a_cached_non_test_task_does_not_fail_the_gate(self):
        # `detekt UP-TO-DATE` alongside an executed jvmTest is the ordinary state of a warm
        # build. Failing here would make the gate red on healthy runs.
        self.assertPasses(NON_TEST_TASKS_UP_TO_DATE)

    def test_one_cached_test_among_executed_ones_still_fails(self):
        self.assertFails(MIXED)

    def test_no_source_is_a_warning_not_a_failure(self):
        # It was an error in the first version, on the reasoning that "a module with no tests
        # is a measurement gap, not a zero". Running it on a real tree showed that failing the
        # ratchet over `:androidApp:testDebugUnitTest` blocks everyone forever over a fact the
        # report already states as 0%.
        errors, warnings, _ = subject.check(NO_SOURCE)
        self.assertEqual(errors, [])
        self.assertTrue(any("contributes no coverage" in w for w in warnings), warnings)

    def test_allow_cached_downgrades_but_does_not_disable(self):
        errors, warnings, downgraded = subject.check(UP_TO_DATE, allow_cached=True)
        self.assertEqual(errors, [])
        self.assertEqual(len(warnings), 1)

    def test_allow_cached_still_fails_on_a_missing_test_task(self):
        # The escape hatch must not become a way to skip the one case that is always wrong.
        errors, _, _ = subject.check(NO_TEST_TASK, allow_cached=True)
        self.assertTrue(errors)

    def test_allow_cached_does_not_change_the_no_source_verdict(self):
        # It is already a warning, so the override has nothing to downgrade. Asserted so that
        # if someone later promotes it back to an error, this test says so.
        errors, warnings, _ = subject.check(NO_SOURCE, allow_cached=True)
        self.assertEqual(errors, [])
        self.assertEqual(len(warnings), 1)

    def test_a_module_with_no_tests_is_exempt_in_both_spellings(self):
        # The live `:androidApp` case: `testDebugUnitTest` is NO-SOURCE and its aggregator
        # `test` is UP-TO-DATE. Both mean "there is nothing to run", not "something ran and
        # produced nothing", and the second one alone would have failed the ratchet forever.
        log = (
            "> Task :shared:jvmTest\n"
            "> Task :androidApp:testDebugUnitTest NO-SOURCE\n"
            "> Task :androidApp:test UP-TO-DATE\n"
        )
        errors, warnings, downgraded = subject.check(log)
        self.assertEqual(errors, [], "a module with no tests is not a measurement failure")
        self.assertEqual(downgraded, 0)
        self.assertTrue(any("has no tests" in w for w in warnings), warnings)

    def test_a_from_cache_task_disproves_the_no_tests_exemption(self):
        # "This module has no tests" must not become a standing pass for a module that IS
        # covered — the exact shape of a gate that quietly stops gating. A FROM-CACHE task
        # proves the module has tests, because the task ran once and produced outputs worth
        # restoring; only an UP-TO-DATE sibling of a NO-SOURCE task reads as benign.
        log = (
            "> Task :shared:jvmTest\n"
            "> Task :androidApp:testDebugUnitTest NO-SOURCE\n"
            "> Task :androidApp:testReleaseUnitTest FROM-CACHE\n"
        )
        errors, _, _ = subject.check(log)
        self.assertTrue(errors, "a cached task means the module is covered and the data is stale")

    def test_an_executed_task_in_the_module_also_lapses_the_exemption(self):
        log = (
            "> Task :androidApp:testDebugUnitTest\n"
            "> Task :androidApp:testReleaseUnitTest UP-TO-DATE\n"
        )
        errors, _, _ = subject.check(log)
        self.assertTrue(errors, "one task executing means the others are judged, not exempted")

    def test_the_up_to_date_message_names_the_remedy(self):
        # A gate that fails without saying what to do gets `--allow-cached` pasted into a CI
        # config once and never looked at again.
        errors, _, _ = subject.check(UP_TO_DATE)
        # And it must not repeat the `just cr RERUN=1` form that issue #86 says does not work.
        self.assertTrue(any("RERUN=1" in e for e in errors), errors)
        self.assertTrue(
            all("environment variable" in e for e in errors),
            "the remedy must name the environment form, since the argument form does not assign",
        )

    def test_the_missing_task_message_names_what_was_not_measured(self):
        errors, _, _ = subject.check(NO_TEST_TASK)
        self.assertTrue(any("measured no code" in e for e in errors), errors)


class RunTests(unittest.TestCase):
    def _write(self, log_text):
        import tempfile

        handle = tempfile.NamedTemporaryFile("w", suffix=".log", delete=False)
        handle.write(log_text)
        handle.close()
        self.addCleanup(Path(handle.name).unlink)
        return handle.name

    def _run(self, log_text, *extra):
        return subject.main([self._write(log_text), *extra])

    def test_exit_zero_on_a_healthy_log(self):
        self.assertEqual(self._run(EXECUTED), 0)

    def test_exit_one_on_a_cached_log(self):
        self.assertEqual(self._run(UP_TO_DATE), 1)

    def test_exit_one_on_a_missing_test_task(self):
        self.assertEqual(self._run(NO_TEST_TASK), 1)

    def test_exit_zero_on_a_cached_log_when_overridden(self):
        self.assertEqual(self._run(UP_TO_DATE, "--allow-cached"), 0)

    def test_the_override_does_not_claim_the_tasks_executed(self):
        # The first version of this script printed "OK — the test tasks in this run executed"
        # on the override path, which is false: the override exists precisely because they did
        # not. A report built from a run that executed nothing is what this whole gate is about,
        # and the override must not reintroduce the lie one level up.
        import io
        import contextlib

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            subject.main([self._write(UP_TO_DATE), "--allow-cached"])
        text = buffer.getvalue()
        self.assertIn("PASSED WITH WARNINGS", text)
        self.assertIn("This is not a measurement.", text)
        self.assertNotIn("OK — the test tasks in this run executed", text)

    def test_a_clean_run_does_not_claim_more_than_it_knows(self):
        # A module with no tests is a warning, but the run is still a real measurement — the
        # summary must not say "passed with warnings" or "not a measurement" for it.
        import io
        import contextlib

        log = (
            "> Task :shared:jvmTest\n"
            "> Task :androidApp:testDebugUnitTest NO-SOURCE\n"
        )
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            subject.main([self._write(log)])
        text = buffer.getvalue()
        self.assertIn("OK — the test tasks in this run executed", text)
        self.assertNotIn("PASSED WITH WARNINGS", text)
        self.assertNotIn("not a measurement", text)

    def test_exit_two_on_an_unreadable_log(self):
        # Distinct from 1: the gate could not run, which is not the same as the gate failing,
        # and a caller that treats them alike will report a measurement problem for a typo.
        self.assertEqual(subject.main(["/nonexistent/log"]), 2)


if __name__ == "__main__":
    unittest.main()

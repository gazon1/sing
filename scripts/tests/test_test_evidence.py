#!/usr/bin/env python3
"""Unit tests for the test-evidence index.

Run with: python3 -m unittest discover -s scripts/tests

Scope
-----
The index is the one place that says *what failed* and *where its evidence is*.
Three issues asked for it (#2664, #2666, #2669), and each of them asked for
one of the properties pinned below:

* **#2664** — a failing test carries a *direct path* to its diagnostics
  bundle, and the association is deterministic: a directory named after the
  class, so two concurrent tests cannot cross-link.
* **#2666** — a report that exists but cannot be read is *incomplete
  evidence*, not an empty successful run.
* **#2669** — a task that never ran is distinguishable from a task that ran
  and selected nothing, and the summary calls out what is missing.

And the outcome model, which is the reason the script exists at all: a task
that was **cancelled** or **timed out** is not a run whose tests failed, and
the report must not present it as one.
"""

from __future__ import annotations

import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / "test-evidence.py"
spec = importlib.util.spec_from_file_location("test_evidence", MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
# The module has to be in `sys.modules` before it runs: a dataclass resolves
# its own module through `sys.modules` when it reads the annotations, and a
# module that was never registered is a module it cannot find.
sys.modules[spec.name] = mod
spec.loader.exec_module(mod)

SUITE = (
    '<testsuite name="{cls}" tests="1">'
    '<testcase classname="{cls}" name="{case}"{file}>{body}</testcase>'
    "</testsuite>"
)


def _xml(body: str, cls: str = "com.example.Suite", case: str = "test", file: str = "") -> str:
    return '<?xml version="1.0" encoding="UTF-8"?>' + SUITE.format(
        cls=cls, case=case, file=file, body=body
    )


FAILURE = '<failure message="assertion failed">stack trace here</failure>'
ERROR = '<error message="java.lang.OutOfMemoryError">oom trace</error>'


class TreeTestCase(unittest.TestCase):
    """A throwaway repository root the script can read."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, relative: str, body: str) -> pathlib.Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
        return path

    def collect(self, expect=None, task_outcome="unknown", globs=None):
        return mod.collect(
            self.root,
            globs or ["**/build/test-results/**/*.xml"],
            expect or [],
            task_outcome,
        )


class TestcaseOutcomeTest(TreeTestCase):
    """The reader's outcomes, in the index's vocabulary."""

    def test_a_failure_is_a_failure(self):
        self.write("shared/build/test-results/jvmTest/TEST-a.xml", _xml(FAILURE))
        evidence = self.collect()
        self.assertEqual([f.outcome for f in evidence.failures], ["failed"])

    def test_an_error_is_an_error_not_a_failure(self):
        # An OOM, a timeout or a crashed fixture is an `error`. Reading it as
        # a plain failure loses the distinction between "the code is wrong" and
        # "the run's infrastructure broke".
        self.write("shared/build/test-results/jvmTest/TEST-a.xml", _xml(ERROR))
        evidence = self.collect()
        self.assertEqual([f.outcome for f in evidence.failures], ["error"])

    def test_a_retry_pass_is_flaky_not_passed(self):
        body = "<flakyFailure message='first attempt failed'>trace</flakyFailure>"
        self.write("shared/build/test-results/jvmTest/TEST-a.xml", _xml(body))
        evidence = self.collect()
        self.assertEqual(evidence.failures, [])
        report = evidence.reports[0]
        self.assertEqual(report.counts.get("flaky"), 1)


class DiagnosticsAssociationTest(TreeTestCase):
    """#2664 — the direct path from a failing test to its bundle."""

    def test_the_bundle_of_the_failing_class_is_named(self):
        self.write("desktopApp/build/test-results/test/TEST-x.xml", _xml(FAILURE, cls="com.example.MenuBarTest"))
        self.write("desktopApp/build/diagnostics/MenuBarTest/attempt-1/tree.txt", "tree")
        evidence = self.collect()
        self.assertEqual(
            evidence.failures[0].diagnostics,
            ["desktopApp/build/diagnostics/MenuBarTest/attempt-1"],
        )

    def test_the_latest_attempt_wins(self):
        self.write("desktopApp/build/test-results/test/TEST-x.xml", _xml(FAILURE, cls="com.example.MenuBarTest"))
        self.write("desktopApp/build/diagnostics/MenuBarTest/attempt-1/tree.txt", "one")
        self.write("desktopApp/build/diagnostics/MenuBarTest/attempt-2/tree.txt", "two")
        evidence = self.collect()
        self.assertEqual(
            evidence.failures[0].diagnostics,
            ["desktopApp/build/diagnostics/MenuBarTest/attempt-2"],
        )

    def test_the_latest_attempt_is_the_highest_number(self):
        # `attempt-10` is LATER than `attempt-2`, and a string comparison
        # says the opposite — the reporter would link a failed test to the
        # bundle of a run nine attempts older.
        self.write("desktopApp/build/test-results/test/TEST-x.xml", _xml(FAILURE, cls="com.example.MenuBarTest"))
        self.write("desktopApp/build/diagnostics/MenuBarTest/attempt-2/tree.txt", "two")
        self.write("desktopApp/build/diagnostics/MenuBarTest/attempt-10/tree.txt", "ten")
        evidence = self.collect()
        self.assertEqual(
            evidence.failures[0].diagnostics,
            ["desktopApp/build/diagnostics/MenuBarTest/attempt-10"],
        )

    def test_a_directory_that_is_not_an_attempt_is_not_guessed_at(self):
        # `attempt-x` is not an attempt the reporter can order, so it is
        # ignored: the numbered bundle is the one it can speak about.
        self.write("desktopApp/build/test-results/test/TEST-x.xml", _xml(FAILURE, cls="com.example.MenuBarTest"))
        self.write("desktopApp/build/diagnostics/MenuBarTest/attempt-1/tree.txt", "one")
        self.write("desktopApp/build/diagnostics/MenuBarTest/attempt-x/tree.txt", "x")
        evidence = self.collect()
        self.assertEqual(
            evidence.failures[0].diagnostics,
            ["desktopApp/build/diagnostics/MenuBarTest/attempt-1"],
        )

    def test_another_class_bundle_is_not_borrowed(self):
        # The association is by class, so a bundle that belongs to a different
        # test is never attached to this one.
        self.write("desktopApp/build/test-results/test/TEST-x.xml", _xml(FAILURE, cls="com.example.MenuBarTest"))
        self.write("desktopApp/build/diagnostics/OtherTest/attempt-1/tree.txt", "tree")
        evidence = self.collect()
        self.assertEqual(evidence.failures[0].diagnostics, [])

    def test_a_nested_test_falls_back_to_its_outer_class(self):
        self.write("shared/build/test-results/jvmTest/TEST-x.xml", _xml(FAILURE, cls="com.example.OuterTest$Inner"))
        self.write("shared/build/diagnostics/OuterTest/coroutines.txt", "dump")
        evidence = self.collect()
        self.assertEqual(
            evidence.failures[0].diagnostics,
            ["shared/build/diagnostics/OuterTest"],
        )


class TaskOutcomeTest(TreeTestCase):
    """#2663 — the task outcome and the testcase outcomes are separate."""

    def test_a_cancelled_task_is_not_rendered_as_a_failed_run(self):
        self.write("shared/build/test-results/jvmTest/TEST-a.xml", _xml(FAILURE))
        evidence = self.collect(task_outcome="cancelled")
        index = mod.render_index(evidence)
        self.assertIn("The run did not finish (`cancelled`)", index)
        self.assertIn("they are not the verdict", index)

    def test_a_timed_out_task_is_not_rendered_as_a_failed_run(self):
        self.write("shared/build/test-results/jvmTest/TEST-a.xml", _xml(FAILURE))
        evidence = self.collect(task_outcome="timed_out")
        self.assertIn("The run did not finish (`timed_out`)", mod.render_index(evidence))

    def test_a_failed_task_renders_the_failing_testcases(self):
        self.write("shared/build/test-results/jvmTest/TEST-a.xml", _xml(FAILURE))
        evidence = self.collect(task_outcome="failed")
        index = mod.render_index(evidence)
        self.assertIn("## Failing testcases (1)", index)
        self.assertNotIn("The run did not finish", index)

    def test_the_task_outcome_vocabulary_is_closed(self):
        # The vocabulary is the contract with the callers: a caller that passes
        # an outcome nobody defined would produce a report nobody can read.
        self.assertEqual(
            set(mod.TASK_OUTCOMES),
            {"passed", "failed", "skipped", "not_run", "cancelled", "timed_out", "unknown"},
        )
        self.assertEqual(
            set(mod.TESTCASE_OUTCOMES),
            {"passed", "failed", "error", "skipped", "flaky", "unknown"},
        )


class IncompleteEvidenceTest(TreeTestCase):
    """#2666/#2669 — missing, unreadable and empty are three different things."""

    def test_a_missing_report_is_not_run(self):
        evidence = self.collect(expect=["desktopApp:test"])
        self.assertEqual(evidence.missing_tasks, ["desktopApp:test"])
        self.assertTrue(evidence.incomplete)
        self.assertIn("no report", mod.render_index(evidence))

    def test_an_empty_report_is_not_missing(self):
        # The task ran and selected nothing. That is a successful empty report,
        # and it is NOT the same thing as a task that never ran.
        self.write("desktopApp/build/test-results/test/TEST-empty.xml", '<?xml version="1.0"?><testsuite name="e" tests="0"/>')
        evidence = self.collect(expect=["desktopApp:test"])
        self.assertEqual(evidence.missing_tasks, [])
        self.assertFalse(evidence.incomplete)
        self.assertIn("0 testcases", mod.render_index(evidence))

    def test_an_unreadable_report_is_incomplete_evidence(self):
        self.write("shared/build/test-results/jvmTest/TEST-broken.xml", "<testsuite")
        evidence = self.collect()
        self.assertTrue(evidence.incomplete)
        self.assertEqual(len(evidence.unreadable), 1)
        self.assertIn("unreadable", mod.render_index(evidence))

    def test_an_unreadable_report_is_not_an_empty_one(self):
        # The reader is strict on purpose: a malformed report must not read as
        # a task that ran and found nothing.
        self.write("shared/build/test-results/jvmTest/TEST-broken.xml", "<testsuite")
        evidence = self.collect(expect=["shared:jvmTest"])
        self.assertTrue(evidence.reports[0].counts == {})

    def test_an_unreadable_report_is_not_a_missing_one(self):
        # The report EXISTS — the task wrote it. What failed is the reader.
        # Calling it a missing report would say twice that the task wrote
        # nothing, and the second statement would be false.
        self.write("shared/build/test-results/jvmTest/TEST-broken.xml", "<testsuite")
        evidence = self.collect(expect=["shared:jvmTest"])
        self.assertEqual(evidence.missing_tasks, [])
        self.assertEqual(len(evidence.unreadable), 1)
        self.assertTrue(evidence.incomplete)

    def test_a_complete_run_says_so(self):
        self.write("shared/build/test-results/jvmTest/TEST-a.xml", _xml(FAILURE))
        evidence = self.collect(expect=["shared:jvmTest"])
        self.assertFalse(evidence.incomplete)
        self.assertIn("None: every expected task produced a readable report.", mod.render_index(evidence))


class ReportIdentityTest(TreeTestCase):
    """The report's own name, read off its path."""

    def test_a_gradle_report_names_its_task(self):
        self.write("shared/build/test-results/jvmTest/TEST-a.xml", _xml(FAILURE))
        self.write("core-domain/build/test-results/jvmTest/TEST-b.xml", _xml(FAILURE))
        evidence = self.collect()
        self.assertEqual(
            sorted(report.task for report in evidence.reports),
            ["core-domain:jvmTest", "shared:jvmTest"],
        )

    def test_an_instrumented_report_names_its_task(self):
        self.write(
            "androidApp/build/outputs/androidTest-results/connected/debug/TEST-a.xml",
            _xml(FAILURE),
        )
        evidence = self.collect(globs=["**/androidTest-results/**/*.xml"])
        self.assertEqual(evidence.reports[0].task, "androidApp:connectedDebugAndroidTest")

    def test_a_maestro_report_names_the_maestro_task(self):
        self.write("build/maestro-results/01-launch-today.xml", _xml(FAILURE, file=' file="Maestro/flows/smoke/01-launch-today.yaml"'))
        evidence = self.collect(globs=["build/maestro-results/*.xml"])
        self.assertEqual(evidence.reports[0].task, "maestro:flows")
        self.assertEqual(evidence.reports[0].module, "maestro")

    def test_a_maestro_failure_finds_its_own_artifact_directory(self):
        # The Maestro tier writes the flow's screenshots and logcat next to the
        # flow's own JUnit file, under the flow's name.
        self.write("build/maestro-results/01-launch-today.xml", _xml(FAILURE))
        self.write("build/maestro-results/01-launch-today/01-launch-today_screen.png", "png")
        evidence = self.collect(globs=["build/maestro-results/*.xml"])
        self.assertEqual(
            evidence.failures[0].diagnostics,
            ["build/maestro-results/01-launch-today"],
        )

    def test_a_nested_module_names_its_whole_path(self):
        # `tools/unwritten-properties` is a Gradle project INSIDE another
        # directory. Taking the first segment of the path would call it
        # `tools` — a module no profile declares, so its reports would look
        # like a task nobody ran. The identity is written the way the profile
        # vocabulary writes it: with colons.
        self.write("tools/unwritten-properties/build/test-results/test/TEST-a.xml", _xml(FAILURE))
        evidence = self.collect()
        self.assertEqual(evidence.reports[0].task, "tools:unwritten-properties:test")
        self.assertEqual(evidence.reports[0].module, "tools:unwritten-properties")

    def test_the_nested_module_is_the_one_the_profile_declares(self):
        # The profile says `tools:unwritten-properties:test`, so the report of
        # that task has to answer to the same name — otherwise the reporter
        # calls a report it is holding missing.
        self.write("tools/unwritten-properties/build/test-results/test/TEST-a.xml", _xml(FAILURE))
        evidence = self.collect(expect=["tools:unwritten-properties:test"])
        self.assertEqual(evidence.missing_tasks, [])


class ProfileAndMetadataTest(TreeTestCase):
    """#2663/#2669 — the profile names the run, the metadata describes it."""

    def run_main(self, *args: str) -> dict:
        code = mod.main(
            ["--root", str(self.root), "--out-dir", "build/evidence", *args]
        )
        self.assertEqual(code, 0)
        manifest = self.root / "build" / "evidence" / "manifest.json"
        return json.loads(manifest.read_text(encoding="utf-8"))

    def test_the_explicit_expectations_win_over_the_profile(self):
        # A run that knows exactly which tasks it ran says so. Merging the
        # profile's tasks into that list would report the tasks the run never
        # promised as missing evidence — an accusation, not a measurement.
        self.write("shared/build/test-results/jvmTest/TEST-a.xml", _xml(FAILURE))
        manifest = self.run_main(
            "--profile", "fast",
            "--expect", "shared:jvmTest",
            "--task-outcome", "failed",
        )
        self.assertEqual(manifest["missing_tasks"], [])
        self.assertEqual(manifest["profile"], "fast")
        # The profile really does declare more than one task, so the assertion
        # above is about the merge and not about a profile with a single task.
        self.assertGreater(len(mod.profile_expectations("fast")), 1)

    def test_the_profile_alone_declares_what_the_run_owes(self):
        manifest = self.run_main("--profile", "fast", "--task-outcome", "passed")
        self.assertEqual(manifest["missing_tasks"], mod.profile_expectations("fast"))

    def test_the_manifest_explains_the_tasks_it_does_not_report(self):
        # A task of the profile that is absent from the reports is not missing
        # evidence — its report arrives later, or the population selected no
        # test of it. The artifact says which, so it explains itself away from
        # the repository's configuration file.
        manifest = self.run_main("--profile", "fast", "--task-outcome", "passed")
        self.assertEqual(manifest["expected_tasks"], mod.profile_expectations("fast"))
        self.assertEqual(manifest["deferred_tasks"], list(mod.wg.profile_deferred("fast")))
        self.assertEqual(manifest["conditional_tasks"], list(mod.wg.profile_conditional("fast")))
        # The three lists are disjoint: a task is in exactly one of them.
        lists = [
            set(manifest["expected_tasks"]),
            set(manifest["deferred_tasks"]),
            set(manifest["conditional_tasks"]),
        ]
        self.assertEqual(lists[0] & lists[1], set())
        self.assertEqual(lists[0] & lists[2], set())
        self.assertEqual(lists[1] & lists[2], set())

    def test_the_index_names_the_tasks_of_the_profile(self):
        code = mod.main(
            ["--root", str(self.root), "--out-dir", "build/evidence", "--profile", "fast"]
        )
        self.assertEqual(code, 0)
        index = (self.root / "build" / "evidence" / "index.md").read_text(encoding="utf-8")
        self.assertIn("## The tasks of the profile", index)
        self.assertIn("deferred:", index)
        self.assertIn("conditional:", index)

    def test_the_population_comes_from_the_profile(self):
        manifest = self.run_main("--profile", "fast", "--task-outcome", "passed")
        self.assertEqual(manifest["population"], "fast")

    def test_the_device_profile_finds_the_instrumented_reports(self):
        # The device tier writes its reports under `androidTest-results`, and
        # a reporter asked for `device` that looked in the JVM place would
        # report a run that has no reports in it.
        self.write(
            "androidApp/build/outputs/androidTest-results/connected/debug/TEST-a.xml",
            _xml(FAILURE),
        )
        manifest = self.run_main("--profile", "device", "--task-outcome", "failed")
        self.assertEqual(manifest["missing_tasks"], [])
        self.assertEqual(
            sorted(report["task"] for report in manifest["reports"]),
            ["androidApp:connectedDebugAndroidTest"],
        )

    def test_the_e2e_profile_finds_the_maestro_reports(self):
        self.write("build/maestro-results/01-launch-today.xml", _xml(FAILURE))
        manifest = self.run_main("--profile", "e2e", "--task-outcome", "failed")
        self.assertEqual(manifest["missing_tasks"], [])
        self.assertEqual(
            sorted(report["task"] for report in manifest["reports"]),
            ["maestro:flows"],
        )

    def test_the_all_profile_finds_the_reports_of_every_tier(self):
        self.write("shared/build/test-results/jvmTest/TEST-a.xml", _xml(FAILURE))
        self.write(
            "androidApp/build/outputs/androidTest-results/connected/debug/TEST-b.xml",
            _xml(FAILURE),
        )
        self.write("build/maestro-results/01-launch-today.xml", _xml(FAILURE))
        manifest = self.run_main("--profile", "all", "--task-outcome", "failed")
        # The union of the three globs, and each tier's report found by its
        # own name — the point of the profile saying where its reports live.
        self.assertEqual(
            sorted(report["task"] for report in manifest["reports"]),
            ["androidApp:connectedDebugAndroidTest", "maestro:flows", "shared:jvmTest"],
        )

    def test_a_metadata_file_describes_the_environment(self):
        # The platform runner already knows the device, the API level and the
        # APK; the workflow passes its file on rather than retyping it.
        self.write("build/android-ui-results/environment.json", json.dumps({
            "kind": "emulator",
            "device": "Pixel 6",
            "api": "36",
            "apk": "app-debug.apk",
            "commit": "abc1234",
        }))
        manifest = self.run_main(
            "--profile", "device",
            "--metadata-file", "build/android-ui-results/environment.json",
        )
        self.assertEqual(manifest["metadata"]["device"], "Pixel 6")
        self.assertEqual(manifest["metadata"]["api"], "36")
        self.assertEqual(manifest["metadata"]["commit"], "abc1234")

    def test_an_unreadable_metadata_file_leaves_the_run_described(self):
        # A missing metadata file must not cost the report: the report is
        # about the evidence, and the environment is a fact about the machine.
        self.write("build/android-ui-results/environment.json", "{not json")
        manifest = self.run_main(
            "--profile", "device",
            "--metadata-file", "build/android-ui-results/environment.json",
        )
        self.assertNotIn("device", manifest["metadata"])


class ManifestTest(TreeTestCase):
    """The manifest is the machine-readable half of the same answer."""

    def test_the_manifest_carries_the_run_identity(self):
        self.write("shared/build/test-results/jvmTest/TEST-a.xml", _xml(FAILURE))
        evidence = self.collect(task_outcome="failed")
        evidence.population = "fast"
        evidence.commit = "abc1234"
        evidence.run_id = "42"
        manifest = mod.render_manifest(evidence)
        self.assertEqual(manifest["population"], "fast")
        self.assertEqual(manifest["commit"], "abc1234")
        self.assertEqual(manifest["run_id"], "42")
        self.assertEqual(manifest["task_outcome"], "failed")
        self.assertEqual(len(manifest["failures"]), 1)
        # It has to survive a round trip: the point of a manifest is that
        # something else reads it.
        self.assertEqual(json.loads(json.dumps(manifest)), manifest)

    def test_the_manifest_paths_are_relative_to_the_repository(self):
        # A manifest that spells out `/home/someone/build/...` is a manifest
        # of one machine, and the evidence is meant to travel between them.
        self.write("shared/build/test-results/jvmTest/TEST-a.xml", _xml(FAILURE))
        evidence = self.collect(task_outcome="failed")
        manifest = mod.render_manifest(evidence)
        self.assertEqual(
            manifest["reports"][0]["path"],
            "shared/build/test-results/jvmTest/TEST-a.xml",
        )


class MarkdownTest(TreeTestCase):
    """The index is read by a person; the report content is not written by us."""

    def test_a_pipe_in_a_test_name_does_not_break_the_table(self):
        body = '<failure message="a | b">trace</failure>'
        self.write(
            "shared/build/test-results/jvmTest/TEST-a.xml",
            _xml(body, cls="com.example.Suite", case="test | pipe"),
        )
        index = mod.render_index(self.collect(task_outcome="failed"))
        rows = [
            line
            for line in index.splitlines()
            if line.startswith("| ") and "com.example.Suite" in line
        ]
        self.assertTrue(rows)
        for row in rows:
            # A row of the failure table has five columns and six pipes; an
            # unescaped pipe in the name would make it seven.
            self.assertEqual(row.count("|") - row.count("\\|"), 6)

    def test_a_backtick_in_a_test_name_does_not_close_the_code_span(self):
        self.write(
            "shared/build/test-results/jvmTest/TEST-a.xml",
            _xml(FAILURE, cls="com.example.Suite", case="test `code`"),
        )
        index = mod.render_index(self.collect(task_outcome="failed"))
        # The fence is doubled, so the backtick in the name stays inside the
        # span instead of ending it.
        self.assertIn("``com.example.Suite.test `code`", index)


if __name__ == "__main__":
    unittest.main()

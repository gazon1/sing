#!/usr/bin/env python3
"""Unit tests for check-test-profiles.py.

The gate's own failure mode is the one it exists to prevent: a reader that
stops recognising the profiles yields an empty finding list, and an empty
finding list reads as a pass. So the fixtures assert on what the gate *saw*,
not on the exit code alone.

Every fixture materialises a fake repository — the profiles file, the
populations file, the Gradle settings, a workflow and the lane file — and
points the gate at it. The gate derives its paths from module-level
constants precisely so that this is possible: a gate whose paths are buried
in a function can only be tested against the real tree, and a gate tested
only against the real tree proves nothing when the real tree is what broke.

Run with: python3 -m unittest discover -s scripts/tests
"""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import tempfile
import textwrap
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / "check-test-profiles.py"
spec = importlib.util.spec_from_file_location("check_test_profiles", MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = mod
spec.loader.exec_module(mod)

PROFILES = """\
fast=shared:jvmTest,desktopApp:test
full=shared:jvmTest,desktopApp:test
device=androidApp:connectedDebugAndroidTest
e2e=maestro:flows
all=shared:jvmTest,desktopApp:test,androidApp:connectedDebugAndroidTest,maestro:flows

fast.tier: jvm
full.tier: jvm
device.tier: android
e2e.tier: maestro
all.tier: all

fast.population: fast
full.population: full

fast.reported: shared:jvmTest,desktopApp:test
full.reported: shared:jvmTest,desktopApp:test
device.reported: androidApp:connectedDebugAndroidTest
e2e.reported: maestro:flows
all.reported: shared:jvmTest,desktopApp:test,androidApp:connectedDebugAndroidTest,maestro:flows

fast.reports: **/build/test-results/**/*.xml
full.reports: **/build/test-results/**/*.xml
device.reports: androidApp/build/outputs/androidTest-results/**/*.xml
e2e.reports: build/maestro-results/**/*.xml
all.reports: **/build/test-results/**/*.xml,androidApp/build/outputs/androidTest-results/**/*.xml,build/maestro-results/**/*.xml
"""

POPULATIONS = """\
fast=fast
full=fast,slow
"""

SETTINGS = """\
include(":shared")
include(":desktopApp")
include(":androidApp")
"""

WORKFLOW = """\
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: tests
        run: ./gradlew --continue :shared:jvmTest :desktopApp:test
"""

HK = """\
["shared:jvmTest"] {
  check = "./gw :shared:jvmTest"
}
"""

#: The instrumented tier is driven by a script, not by a Gradle invocation in
#: the workflow — so the script has to mention the task for the gate to see
#: that somebody runs it.
RUNNER_SCRIPT = """\
#!/usr/bin/env python3
TASK = ":androidApp:connectedDebugAndroidTest"
"""

#: The source sets of the fake repository's modules. They decide whether a
#: task CAN draw testcases at all, and a module without a source set is a
#: module whose task the gate does not expect a report from.
DEFAULT_SOURCE_SETS = {
    "shared": ("jvmTest",),
    "desktopApp": ("test",),
    "androidApp": ("androidTest",),
}


class FakeRepo:
    """A repository the gate can be pointed at."""

    def __init__(
        self,
        profiles: str = PROFILES,
        populations: str = POPULATIONS,
        settings: str = SETTINGS,
        workflow: str = WORKFLOW,
        hk: str = HK,
        runner: str | None = RUNNER_SCRIPT,
        source_sets: dict[str, tuple[str, ...]] | None = None,
    ) -> None:
        self.root = pathlib.Path(tempfile.mkdtemp(prefix="test-profiles-"))
        (self.root / "config").mkdir()
        (self.root / ".github" / "workflows").mkdir(parents=True)
        (self.root / "scripts").mkdir()
        (self.root / "config" / "test-profiles.properties").write_text(profiles, encoding="utf-8")
        (self.root / "config" / "test-populations.properties").write_text(populations, encoding="utf-8")
        (self.root / "settings.gradle.kts").write_text(settings, encoding="utf-8")
        (self.root / ".github" / "workflows" / "ci.yml").write_text(workflow, encoding="utf-8")
        (self.root / "hk.pkl").write_text(hk, encoding="utf-8")
        if runner is not None:
            (self.root / "scripts" / "run-android-ui-tests.py").write_text(runner, encoding="utf-8")
        # The source sets decide whether a task CAN draw testcases, so a fake
        # repository without them would find every task empty and the gate
        # would say nothing at all.
        for module, sets in (source_sets or DEFAULT_SOURCE_SETS).items():
            for name in sets:
                (self.root / module.replace(":", "/") / "src" / name).mkdir(parents=True, exist_ok=True)

    def point_the_gate_at(self) -> None:
        """Repoint the gate's module-level paths at this repository."""
        mod.ROOT = self.root
        mod.WORKFLOWS = self.root / ".github" / "workflows"
        mod.LOCAL_SURFACE = self.root / "hk.pkl"
        mod.SETTINGS = self.root / "settings.gradle.kts"
        mod.PROFILES_FILE = self.root / "config" / "test-profiles.properties"
        mod.POPULATIONS_FILE = self.root / "config" / "test-populations.properties"

    def violations(self) -> list[str]:
        self.point_the_gate_at()
        violations, _notes, _listing = mod.evaluate()
        return violations


class ProfilesGateTest(unittest.TestCase):
    """The findings the gate produces."""

    def setUp(self) -> None:
        # The reader caches by path, and a fake repository is a new path every
        # time — so the cache cannot serve one fixture's file for another's.
        mod.wg.load_profiles.cache_clear()
        mod.wg.load_populations.cache_clear()

    def test_a_consistent_repository_has_no_findings(self) -> None:
        self.assertEqual([], FakeRepo().violations())

    def test_a_profile_the_vocabulary_does_not_contain_is_a_finding(self) -> None:
        profiles = PROFILES + "nightly=shared:jvmTest\n"
        violations = FakeRepo(profiles=profiles).violations()
        self.assertTrue(
            any("'nightly'" in v for v in violations),
            f"the extra profile was not called out: {violations}",
        )

    def test_a_vocabulary_profile_the_file_does_not_declare_is_a_finding(self) -> None:
        profiles = PROFILES.replace("e2e=maestro:flows\n", "")
        violations = FakeRepo(profiles=profiles).violations()
        self.assertTrue(
            any("'e2e'" in v for v in violations),
            f"the missing profile was not called out: {violations}",
        )

    def test_all_must_be_the_union_of_the_others(self) -> None:
        profiles = PROFILES.replace(
            "all=shared:jvmTest,desktopApp:test,androidApp:connectedDebugAndroidTest,maestro:flows",
            "all=shared:jvmTest,desktopApp:test,androidApp:connectedDebugAndroidTest",
        )
        violations = FakeRepo(profiles=profiles).violations()
        self.assertTrue(
            any("'all'" in v and "maestro:flows" in v for v in violations),
            f"the missing union member was not called out: {violations}",
        )

    def test_fast_and_full_must_differ_only_by_their_population(self) -> None:
        profiles = PROFILES.replace(
            "full=shared:jvmTest,desktopApp:test",
            "full=shared:jvmTest,desktopApp:test,shared:archTest",
        )
        violations = FakeRepo(profiles=profiles).violations()
        self.assertTrue(
            any("different task lists" in v for v in violations),
            f"the task-list difference was not called out: {violations}",
        )

    def test_a_profile_naming_an_unknown_module_is_a_finding(self) -> None:
        profiles = PROFILES.replace("shared:jvmTest", "nosuchmodule:jvmTest")
        violations = FakeRepo(profiles=profiles).violations()
        self.assertTrue(
            any("nosuchmodule" in v for v in violations),
            f"the unknown module was not called out: {violations}",
        )

    def test_a_task_that_runs_and_is_declared_nowhere_is_a_finding(self) -> None:
        workflow = WORKFLOW.replace(
            ":shared:jvmTest :desktopApp:test", ":shared:jvmTest :desktopApp:test :core-domain:jvmTest"
        )
        settings = SETTINGS + 'include(":core-domain")\n'
        source_sets = dict(DEFAULT_SOURCE_SETS, **{"core-domain": ("jvmTest",)})
        violations = FakeRepo(
            workflow=workflow, settings=settings, source_sets=source_sets
        ).violations()
        self.assertTrue(
            any("core-domain:jvmTest" in v for v in violations),
            f"the undeclared running task was not called out: {violations}",
        )

    def test_a_task_nobody_runs_is_a_finding(self) -> None:
        profiles = PROFILES.replace(
            "all=shared:jvmTest,desktopApp:test,androidApp:connectedDebugAndroidTest,maestro:flows",
            "all=shared:jvmTest,desktopApp:test,androidApp:connectedDebugAndroidTest,maestro:flows,"
            "shared:archTest",
        )
        violations = FakeRepo(profiles=profiles).violations()
        self.assertTrue(
            any("shared:archTest" in v for v in violations),
            f"the task nobody runs was not called out: {violations}",
        )

    def test_a_population_nobody_declares_is_a_finding(self) -> None:
        profiles = PROFILES.replace("fast.population: fast", "fast.population: quickest")
        violations = FakeRepo(profiles=profiles).violations()
        self.assertTrue(
            any("quickest" in v for v in violations),
            f"the unknown population was not called out: {violations}",
        )

    def test_a_device_profile_may_not_declare_a_population(self) -> None:
        profiles = PROFILES + "device.population: fast\n"
        violations = FakeRepo(profiles=profiles).violations()
        self.assertTrue(
            any("not tag-selected" in v for v in violations),
            f"the population on a device tier was not called out: {violations}",
        )

    def test_a_task_without_a_source_set_is_not_expected(self) -> None:
        # `:androidApp:test` is NO-SOURCE: the module has no `src/test`, so the
        # task cannot draw testcases and no profile has to declare it.
        workflow = WORKFLOW.replace(
            ":shared:jvmTest :desktopApp:test", ":shared:jvmTest :desktopApp:test :androidApp:test"
        )
        violations = FakeRepo(workflow=workflow).violations()
        self.assertFalse(
            any("androidApp:test" in v for v in violations),
            f"a NO-SOURCE task was called out: {violations}",
        )

    def test_a_task_in_none_of_the_three_lists_is_a_finding(self) -> None:
        # A task nobody classified cannot be told apart from a task that
        # never ran, and the reporter has to be able to tell them apart.
        profiles = PROFILES.replace(
            "fast.reported: shared:jvmTest,desktopApp:test",
            "fast.reported: shared:jvmTest",
        )
        violations = FakeRepo(profiles=profiles).violations()
        self.assertTrue(
            any("desktopApp:test" in v and "none of reported" in v for v in violations),
            f"the unclassified task was not called out: {violations}",
        )

    def test_a_task_in_two_of_the_three_lists_is_a_finding(self) -> None:
        profiles = PROFILES.replace(
            "fast.reported: shared:jvmTest,desktopApp:test",
            "fast.reported: shared:jvmTest,desktopApp:test\nfast.conditional: desktopApp:test",
        )
        violations = FakeRepo(profiles=profiles).violations()
        self.assertTrue(
            any("more than one" in v for v in violations),
            f"the double-classified task was not called out: {violations}",
        )

    def test_an_unknown_tier_is_a_finding(self) -> None:
        profiles = PROFILES.replace("device.tier: android", "device.tier: phone")
        violations = FakeRepo(profiles=profiles).violations()
        self.assertTrue(
            any("'phone'" in v for v in violations),
            f"the unknown tier was not called out: {violations}",
        )

    def test_a_profile_without_report_globs_is_a_finding(self) -> None:
        profiles = PROFILES.replace("e2e.reports: build/maestro-results/**/*.xml\n", "")
        violations = FakeRepo(profiles=profiles).violations()
        self.assertTrue(
            any("no report globs" in v for v in violations),
            f"the profile without globs was not called out: {violations}",
        )

    def test_a_workflow_may_not_carry_its_own_expectations(self) -> None:
        workflow = WORKFLOW + """\
      - name: index
        run: |
          python3 scripts/test-evidence.py --profile fast --expect shared:jvmTest
"""
        violations = FakeRepo(workflow=workflow).violations()
        self.assertTrue(
            any("--expect" in v or "expected task" in v for v in violations),
            f"the second list of expectations was not called out: {violations}",
        )


class EvidenceConditionTest(unittest.TestCase):
    """The evidence of a run survives the cancellation of the job."""

    def setUp(self) -> None:
        mod.wg.load_profiles.cache_clear()
        mod.wg.load_populations.cache_clear()

    def _workflow(self, condition: str) -> str:
        return WORKFLOW + f"""\
      - name: Upload JUnit results
        if: ${{{{ {condition} }}}}
        uses: actions/upload-artifact@v7
        with:
          name: junit-results-${{{{ github.run_id }}}}
          path: shared/build/test-results/**/*.xml
"""

    def test_a_cancelled_job_must_not_throw_its_evidence_away(self) -> None:
        violations = FakeRepo(workflow=self._workflow("!cancelled()")).violations()
        self.assertTrue(
            any("!cancelled()" in v for v in violations),
            f"the guarded upload was not called out: {violations}",
        )

    def test_an_upload_without_a_condition_is_a_finding(self) -> None:
        workflow = WORKFLOW + """\
      - name: Upload JUnit results
        uses: actions/upload-artifact@v7
        with:
          name: junit-results
          path: shared/build/test-results/**/*.xml
"""
        violations = FakeRepo(workflow=workflow).violations()
        self.assertTrue(
            any("always()" in v for v in violations),
            f"the unguarded upload was not called out: {violations}",
        )

    def test_an_upload_that_always_runs_is_not_a_finding(self) -> None:
        violations = FakeRepo(workflow=self._workflow("always()")).violations()
        self.assertFalse(
            any("always()" in v for v in violations),
            f"a correct upload was called out: {violations}",
        )


class ScriptMentionTest(unittest.TestCase):
    """What counts as a script that runs a task."""

    def setUp(self) -> None:
        mod.wg.load_profiles.cache_clear()

    def test_a_comment_is_not_a_mention(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / "scripts").mkdir()
            (root / "scripts" / "runner.py").write_text(
                "# :androidApp:connectedDebugAndroidTest is run here\n", encoding="utf-8"
            )
            mod.ROOT = root
            self.assertFalse(mod._script_mentions(":androidApp:connectedDebugAndroidTest"))

    def test_a_test_of_the_gate_is_not_a_mention(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / "scripts" / "tests").mkdir(parents=True)
            (root / "scripts" / "tests" / "test_runner.py").write_text(
                "task = ':androidApp:connectedDebugAndroidTest'\n", encoding="utf-8"
            )
            mod.ROOT = root
            self.assertFalse(mod._script_mentions(":androidApp:connectedDebugAndroidTest"))


class ProfileReaderTest(unittest.TestCase):
    """The reader behind the gate."""

    def setUp(self) -> None:
        mod.wg.load_profiles.cache_clear()

    def test_a_profile_without_tasks_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "profiles.properties"
            path.write_text("fast=\n", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                mod.wg.load_profiles(path)

    def test_a_property_is_not_read_as_a_profile(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "profiles.properties"
            path.write_text(
                textwrap.dedent(
                    """\
                    fast=shared:jvmTest
                    fast.population: fast
                    """
                ),
                encoding="utf-8",
            )
            profiles = mod.wg.load_profiles(path)
            self.assertEqual({"fast"}, set(profiles))
            self.assertEqual("fast", profiles["fast"]["population"])

    def test_an_unknown_profile_is_an_error(self) -> None:
        with self.assertRaises(RuntimeError):
            mod.wg.profile_tasks("nosuchprofile", mod.PROFILES_FILE)


if __name__ == "__main__":
    unittest.main()

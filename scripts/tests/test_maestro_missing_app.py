#!/usr/bin/env python3
"""A flow the app was not there for was not measured — and the run has to say so.

Run with: python3 -m unittest discover -s scripts/tests

Measured on a physical phone (2026-10-28): the package was removed from the
device at 15:59 and the runner carried on. Twenty-six flows failed with
"Package com.singularity.todo is not installed" and five with "Unknown error",
so a 94-flow run reported 55 failures — of which 31 were not measurements at
all. The device never disconnected, so the rule that a lost device ends the
run did not fire.

Three things follow, and this file holds a test for each:

* a missing app is not a failing flow. It is the absence of the flow, and the
  report must not say otherwise;
* the run can carry on once the app is back, so one missing package does not
  cost the whole run;
* a run that skipped a flow has to say which one it skipped, and it is not a
  green run.
"""
from __future__ import annotations

import dataclasses
import json
import pathlib
import tempfile
import unittest
import unittest.mock

from emulator._startup import Acquisition
from emulator._types import EXIT_OK
from emulator.maestro import _cli
from emulator.maestro._runner import AppMissingError, run_one_flow
from emulator.maestro._types import DeviceDeps

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent

#: What Maestro writes when the app is not on the device.
_MISSING_APP_REPORT = """<?xml version='1.0' encoding='UTF-8'?>
<testsuites>
  <testsuite name="Test Suite" device="RMX3938" tests="1" failures="1">
    <testcase id="flow" name="flow" status="ERROR">
      <failure>Package com.singularity.todo is not installed</failure>
    </testcase>
  </testsuite>
</testsuites>
"""

#: What Maestro writes for a flow that passed.
_CLEAN_REPORT = """<?xml version='1.0' encoding='UTF-8'?>
<testsuites>
  <testsuite name="Test Suite" device="RMX3938" tests="1" failures="0">
    <testcase id="flow" name="flow" status="PASSED"/>
  </testsuite>
</testsuites>
"""


#: The CLI prints the flow's path relative to the repository root, so a test
#: flow has to live inside the repository. `build/` is where the run puts its
#: own output and is not tracked, so it is the right place for a fixture.
_FIXTURE_DIR = ROOT / "build" / "maestro-test-flows"


def _flow(tmp: str, name: str) -> pathlib.Path:
    _FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    path = _FIXTURE_DIR / f"{name}.yaml"
    path.write_text("appId: com.singularity.todo\nname: " + name + "\n", encoding="utf-8")
    return path


def _deps(
    *,
    app_present=lambda serial: True,
    install_ok=True,
    report: str | None = _CLEAN_REPORT,
    maestro_calls: list | None = None,
) -> DeviceDeps:
    """A bundle whose device and Maestro answers the test's script."""
    calls = maestro_calls if maestro_calls is not None else []
    installed = {"value": True}

    def fake_try_run(cmd, **kwargs):
        if cmd and cmd[0] == "maestro":
            calls.append(cmd)
            if report is not None and "--output" in cmd:
                out = pathlib.Path(cmd[cmd.index("--output") + 1])
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_text(report, encoding="utf-8")
            return unittest.mock.Mock(
                returncode=0 if report is not None and "<failure>" not in report else 1,
                stdout="",
                stderr="",
            )
        return unittest.mock.Mock(returncode=0, stdout="", stderr="")

    def fake_install(serial):
        if install_ok:
            installed["value"] = True
        return unittest.mock.Mock(returncode=0 if install_ok else 1, stdout="", stderr="")

    return DeviceDeps(
        try_run=fake_try_run,
        device_alive=lambda s: True,
        device_model=lambda s: "RMX3938",
        maestro_saw_device_failure=lambda stderr: False,
        apk_sha_from_device=lambda s: "abc123def456",
        gradle_install_debug=fake_install,
        acquire_device=lambda pin: Acquisition("emulator-5554", EXIT_OK),
        adb=lambda *a, **kw: "",
        adb_on=lambda s, *a, **kw: "",
        reset_adb_server=lambda: None,
        wait_for_app_resumed=lambda s, timeout=20: True,
        cleanup_run_fixtures=lambda *a, **kw: None,
        capture_artifacts=lambda *a, **kw: None,
        git_sha=lambda deps: "abc123def456",
        app_installed=lambda s: app_present(s) and installed["value"],
        device_api_level=lambda s: 35,
    )


class TheAppIsNotThereTest(unittest.TestCase):
    """A flow the app was not there for carries no verdict."""

    def test_a_missing_app_before_the_flow_raises_instead_of_failing_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            flow = _flow(tmp, "some-flow")
            junit = pathlib.Path(tmp) / "reports"
            with self.assertRaises(AppMissingError) as caught:
                run_one_flow(
                    flow_path=flow,
                    serial="emulator-5554",
                    junit_dir=junit,
                    skip_install=True,
                    allow_stale=True,
                    max_retries=1,
                    expected_sha="",
                    deps=_deps(app_present=lambda serial: False),
                )
            self.assertIn("some-flow", str(caught.exception))

    def test_a_missing_app_before_the_flow_leaves_no_report(self):
        """A report that says "the app was not installed" is read as a verdict."""
        with tempfile.TemporaryDirectory() as tmp:
            flow = _flow(tmp, "some-flow")
            junit = pathlib.Path(tmp) / "reports"
            with self.assertRaises(AppMissingError):
                run_one_flow(
                    flow_path=flow,
                    serial="emulator-5554",
                    junit_dir=junit,
                    skip_install=True,
                    allow_stale=True,
                    max_retries=1,
                    expected_sha="",
                    deps=_deps(app_present=lambda serial: False),
                )
            self.assertFalse(
                (junit / "some-flow.xml").exists(),
                "a report that carries the missing-package failure would be read "
                "as a failing flow by the gate",
            )

    def test_the_app_disappearing_mid_flow_removes_the_report(self):
        """The package was there when the flow started and gone when it ended."""
        answers = iter([True, False])

        with tempfile.TemporaryDirectory() as tmp:
            flow = _flow(tmp, "some-flow")
            junit = pathlib.Path(tmp) / "reports"
            with self.assertRaises(AppMissingError):
                run_one_flow(
                    flow_path=flow,
                    serial="emulator-5554",
                    junit_dir=junit,
                    skip_install=True,
                    allow_stale=True,
                    max_retries=1,
                    expected_sha="",
                    deps=_deps(
                        app_present=lambda serial: next(answers),
                        report=_MISSING_APP_REPORT,
                    ),
                )
            self.assertFalse(
                (junit / "some-flow.xml").exists(),
                "the report of a flow that was never measured must not be left "
                "behind for the gate to read as a failure",
            )


class TheRunRecoversTest(unittest.TestCase):
    """One missing package must not cost the whole run."""

    def _invoke(self, deps, junit_dir, flow, *extra):
        from click.testing import CliRunner

        orig = _cli._default_device_deps
        try:
            _cli._default_device_deps = lambda: deps
            return CliRunner().invoke(
                _cli.main,
                [
                    "--flow", str(flow),
                    "--skip-install", "--allow-stale-apk",
                    "--junit-dir", str(junit_dir),
                    *extra,
                ],
            )
        finally:
            _cli._default_device_deps = orig

    def test_the_flow_is_recorded_as_not_measured(self):
        with tempfile.TemporaryDirectory() as tmp:
            flow = _flow(tmp, "flow-a")
            junit = pathlib.Path(tmp) / "reports"
            deps = _deps(app_present=lambda serial: False)
            result = self._invoke(deps, junit, flow)
            self.assertEqual(result.exit_code, 1, result.output)
            summary = json.loads((junit / "run-summary.json").read_text(encoding="utf-8"))
            self.assertIn("flow-a", summary["not_measured"])
            self.assertNotIn("flow-a", summary["failed"])

    def test_the_run_does_not_report_a_green_run(self):
        """A flow that was not measured is not a flow that passed."""
        with tempfile.TemporaryDirectory() as tmp:
            flow = _flow(tmp, "flow-a")
            junit = pathlib.Path(tmp) / "reports"
            result = self._invoke(_deps(app_present=lambda serial: False), junit, flow)
            self.assertEqual(result.exit_code, 1, result.output)

    def test_the_environment_the_run_happened_in_is_written_down(self):
        """A floor belongs to an environment, so the run has to name its own."""
        with tempfile.TemporaryDirectory() as tmp:
            flow = _flow(tmp, "flow-a")
            junit = pathlib.Path(tmp) / "reports"
            self._invoke(_deps(app_present=lambda serial: False), junit, flow)
            environment = json.loads(
                (junit / "environment.json").read_text(encoding="utf-8")
            )
            self.assertEqual(environment["kind"], "emulator")
            self.assertEqual(environment["api"], 35)
            self.assertEqual(environment["device"], "RMX3938")


class TheRunResumesTest(unittest.TestCase):
    """Ninety-four flows take two hours; a resume must not throw them away."""

    def _invoke(self, deps, junit_dir, flow, *extra):
        from click.testing import CliRunner

        orig = _cli._default_device_deps
        try:
            _cli._default_device_deps = lambda: deps
            return CliRunner().invoke(
                _cli.main,
                [
                    "--flow", str(flow),
                    "--skip-install", "--allow-stale-apk",
                    "--junit-dir", str(junit_dir),
                    *extra,
                ],
            )
        finally:
            _cli._default_device_deps = orig

    def test_a_flow_with_a_clean_report_is_not_run_again(self):
        with tempfile.TemporaryDirectory() as tmp:
            flow = _flow(tmp, "flow-a")
            junit = pathlib.Path(tmp) / "reports"
            junit.mkdir(parents=True)
            (junit / "flow-a.xml").write_text(_CLEAN_REPORT, encoding="utf-8")
            calls: list = []
            result = self._invoke(
                _deps(maestro_calls=calls), junit, flow, "--resume"
            )
            self.assertEqual(result.exit_code, 0, result.output)
            self.assertEqual(calls, [], "a finished flow was run again")

    def test_a_flow_that_failed_is_run_again(self):
        """A failed flow is not a finished flow; that is the point of a resume."""
        with tempfile.TemporaryDirectory() as tmp:
            flow = _flow(tmp, "flow-a")
            junit = pathlib.Path(tmp) / "reports"
            junit.mkdir(parents=True)
            (junit / "flow-a.xml").write_text(_MISSING_APP_REPORT, encoding="utf-8")
            calls: list = []
            self._invoke(_deps(maestro_calls=calls), junit, flow, "--resume")
            self.assertEqual(len(calls), 1, "a failed flow was not run again")

    def test_without_the_flag_the_flow_runs_again(self):
        with tempfile.TemporaryDirectory() as tmp:
            flow = _flow(tmp, "flow-a")
            junit = pathlib.Path(tmp) / "reports"
            junit.mkdir(parents=True)
            (junit / "flow-a.xml").write_text(_CLEAN_REPORT, encoding="utf-8")
            calls: list = []
            self._invoke(_deps(maestro_calls=calls), junit, flow)
            self.assertEqual(len(calls), 1, "the run ignored the report that was there")


class AFlakIsNotDebtTest(unittest.TestCase):
    """A flow that passes on the second try is a passing flow — and not a
    reliable one. The two facts must not be merged."""

    def test_the_attempt_count_is_part_of_the_verdict(self):
        with tempfile.TemporaryDirectory() as tmp:
            flow = _flow(tmp, "some-flow")
            junit = pathlib.Path(tmp) / "reports"
            answers = {"n": 0}

            def fake_try_run(cmd, **kwargs):
                if cmd and cmd[0] == "maestro":
                    answers["n"] += 1
                    out = pathlib.Path(cmd[cmd.index("--output") + 1])
                    out.parent.mkdir(parents=True, exist_ok=True)
                    if answers["n"] == 1:
                        # A device-level signature: the host was busy, the
                        # device was fine. The runner retries it.
                        out.write_text(
                            "<testsuites><testsuite name='Test Suite' tests='1' "
                            "failures='1'><testcase id='flow' name='flow' "
                            "status='ERROR'><failure>Device server died"
                            "</failure></testcase></testsuite></testsuites>",
                            encoding="utf-8",
                        )
                        return unittest.mock.Mock(
                            returncode=1, stdout="", stderr="Device server died"
                        )
                    out.write_text(_CLEAN_REPORT, encoding="utf-8")
                    return unittest.mock.Mock(returncode=0, stdout="", stderr="")
                return unittest.mock.Mock(returncode=0, stdout="", stderr="")

            deps = dataclasses.replace(
                _deps(),
                try_run=fake_try_run,
                maestro_saw_device_failure=lambda stderr: "Device server died" in stderr,
                junit_reports_device_failure=lambda report: False,
            )
            with unittest.mock.patch(
                "emulator.maestro._runner.time.sleep", lambda _s: None
            ):
                ok, _run_id, attempts = run_one_flow(
                    flow_path=flow,
                    serial="emulator-5554",
                    junit_dir=junit,
                    skip_install=True,
                    allow_stale=True,
                    max_retries=2,
                    expected_sha="",
                    deps=deps,
                )
            self.assertTrue(ok, "the flow did not pass on the retry")
            self.assertEqual(attempts, 2, "the attempt count did not say it needed two")


if __name__ == "__main__":
    unittest.main()

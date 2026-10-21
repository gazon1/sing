#!/usr/bin/env python3
"""The runner must tell a dead device from a broken flow — and build the right APK.

Run with: python3 -m unittest discover -s scripts/tests

Two defects made a whole E2E suite look broken while it measured nothing:

* The APK the local runner installed was assembled *without* the auth bypass, so
  the app opened on the "Connect your server" screen and every flow failed on
  ``nav_tab_today`` after a 45-second timeout. The E2E workflow assembles its
  APK with ``-PTEST_AUTH_BYPASS=true``; the local install did not, so the local
  run measured a different binary than CI does.
* A device that died mid-flow was counted as a broken flow. Maestro writes the
  death into the flow's JUnit report, not into the process's stderr, so a runner
  that reads stderr alone never retries what it should have retried.
"""
from __future__ import annotations

import dataclasses
import pathlib
import tempfile
import unittest
import unittest.mock

from emulator._device import DeviceHealth
from emulator._startup import Acquisition
from emulator._types import EXIT_NOT_READY, EXIT_OK
from emulator.maestro import _cli
from emulator.maestro._device import (
    gradle_install_debug,
    junit_reports_device_failure,
    maestro_saw_device_failure,
)
from emulator.maestro._runner import DeviceLostError, run_one_flow
from emulator.maestro._types import DeviceDeps

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent

#: What Maestro writes into a JUnit report when the device died under it.
_DEAD_DEVICE_REPORT = """<?xml version='1.0' encoding='UTF-8'?>
<testsuites>
  <testsuite name="Test Suite" device="Medium_Phone" tests="1" failures="1">
    <testcase id="smoke" name="smoke" status="ERROR">
      <failure>maestro.android.DeviceServerDiedException: Device server died \
during 'deviceInfo' on emulator-5554: StatusRuntimeException: UNAVAILABLE
\tat maestro.android.AndroidDeviceConnection.onGrpcDeath</failure>
    </testcase>
  </testsuite>
</testsuites>
"""

#: What Maestro writes when the flow itself failed: an assertion about the app.
_BROKEN_FLOW_REPORT = """<?xml version='1.0' encoding='UTF-8'?>
<testsuites>
  <testsuite name="Test Suite" device="Medium_Phone" tests="1" failures="1">
    <testcase id="smoke" name="smoke" status="ERROR">
      <failure>Assertion is false: id: nav_tab_today is visible</failure>
    </testcase>
  </testsuite>
</testsuites>
"""


class JUnitReportReadingTest(unittest.TestCase):
    """The report is where the device's death is written down."""

    def test_a_dead_device_is_recognised(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = pathlib.Path(tmp) / "flow.xml"
            report.write_text(_DEAD_DEVICE_REPORT, encoding="utf-8")
            self.assertTrue(junit_reports_device_failure(report))

    def test_a_broken_flow_is_not_a_dead_device(self):
        """An assertion failure is the flow's own, and is not retried."""
        with tempfile.TemporaryDirectory() as tmp:
            report = pathlib.Path(tmp) / "flow.xml"
            report.write_text(_BROKEN_FLOW_REPORT, encoding="utf-8")
            self.assertFalse(junit_reports_device_failure(report))

    def test_an_assertion_that_says_unavailable_is_not_a_dead_device(self):
        """The app's own words must not be read as the device's death.

        A loose pattern would turn every flow that asserts on an error message
        containing "unavailable" into a device failure, and retry it forever.
        """
        with tempfile.TemporaryDirectory() as tmp:
            report = pathlib.Path(tmp) / "flow.xml"
            report.write_text(
                "<failure>Assertion is false: text 'Service unavailable' is visible</failure>",
                encoding="utf-8",
            )
            self.assertFalse(junit_reports_device_failure(report))

    def test_a_missing_report_is_not_a_dead_device(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(
                junit_reports_device_failure(pathlib.Path(tmp) / "never-written.xml")
            )


class StderrReadingTest(unittest.TestCase):
    """The process output keeps its own, looser signatures."""

    def test_device_server_died_in_stderr(self):
        self.assertTrue(maestro_saw_device_failure("Device server died"))

    def test_a_plain_assertion_in_stderr_is_not_a_device_failure(self):
        self.assertFalse(
            maestro_saw_device_failure("Assertion is false: id: nav_tab_today is visible")
        )


class InstallBuildTest(unittest.TestCase):
    """The installed APK must be the one the E2E workflow assembles."""

    def test_the_install_carries_the_auth_bypass(self):
        captured: list[list[str]] = []

        def fake_run(args, **kwargs):
            captured.append(args)
            cp = unittest.mock.Mock()
            cp.returncode = 0
            cp.stdout = ""
            cp.stderr = ""
            return cp

        with unittest.mock.patch(
            "emulator.maestro._device._subprocess_run", fake_run
        ):
            gradle_install_debug("emulator-5554")

        self.assertEqual(len(captured), 1)
        self.assertIn(
            "-PTEST_AUTH_BYPASS=true",
            captured[0],
            "the installed APK would open on the sign-in screen, and every "
            "assertion about the shell would be made against the screen in "
            "front of the one it describes",
        )


def _deps(report_bodies: list[str | None]) -> DeviceDeps:
    """A bundle whose fake Maestro writes `report_bodies` in turn.

    A `None` entry means the attempt wrote no report at all.
    """
    calls: list[pathlib.Path] = []

    def fake_try_run(cmd, **kwargs):
        if cmd and cmd[0] == "maestro":
            flow = pathlib.Path(cmd[2])
            calls.append(flow)
            out = cmd[cmd.index("--output") + 1]
            body = report_bodies[len(calls) - 1] if len(calls) <= len(report_bodies) else None
            if body is not None:
                pathlib.Path(out).write_text(body, encoding="utf-8")
            cp = unittest.mock.Mock()
            cp.returncode = 0 if body is not None and "<failure>" not in body else 1
            cp.stdout = ""
            cp.stderr = ""
            return cp
        cp = unittest.mock.Mock()
        cp.returncode = 0
        cp.stdout = ""
        cp.stderr = ""
        return cp

    noop = unittest.mock.Mock(returncode=0, stdout="", stderr="")
    return DeviceDeps(
        try_run=fake_try_run,
        device_alive=lambda s: True,
        device_model=lambda s: "Pixel_8",
        maestro_saw_device_failure=lambda stderr: False,
        apk_sha_from_device=lambda s: "abc123def456",
        gradle_install_debug=lambda s: noop,
        acquire_device=lambda pin: Acquisition("emulator-5554", EXIT_OK),
        adb=lambda *a, **kw: "",
        adb_on=lambda s, *a, **kw: "",
        reset_adb_server=lambda: None,
        wait_for_app_resumed=lambda s, t=20: True,
        cleanup_run_fixtures=lambda *a, **kw: None,
        capture_artifacts=lambda *a, **kw: None,
        git_sha=lambda deps: "abc123def456",
        probe_device_health=lambda s: DeviceHealth("alive", "it answered"),
        emulator_process_alive=lambda: True,
    )


class RunnerReadsTheReportTest(unittest.TestCase):
    """The runner must act on what the report says, not only on stderr."""

    def test_a_dead_device_written_only_to_the_report_is_retried(self):
        """The signature lived in the report; stderr was empty.

        Without reading the report this is a broken flow: counted, not retried,
        and the suite reports a defect that is really a device that fell over.
        """
        deps = _deps([
            _DEAD_DEVICE_REPORT,
            "<testsuites><testsuite name='Test Suite' tests='1' failures='0'/></testsuites>",
        ])
        flow = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"

        with unittest.mock.patch("emulator.maestro._runner.time.sleep", lambda _s: None):
            ok, _run_id, _attempts = run_one_flow(
                flow_path=flow,
                serial="emulator-5554",
                junit_dir=pathlib.Path(tempfile.mkdtemp()),
                skip_install=True,
                allow_stale=True,
                max_retries=2,
                expected_sha="",
                deps=deps,
            )

        self.assertTrue(ok, "the flow was not retried after the device died")

    def test_a_broken_flow_is_not_retried(self):
        """An assertion failure is the flow's own verdict; retrying hides it."""
        deps = _deps([_BROKEN_FLOW_REPORT])
        flow = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"

        with unittest.mock.patch("emulator.maestro._runner.time.sleep", lambda _s: None):
            ok, _run_id, _attempts = run_one_flow(
                flow_path=flow,
                serial="emulator-5554",
                junit_dir=pathlib.Path(tempfile.mkdtemp()),
                skip_install=True,
                allow_stale=True,
                max_retries=2,
                expected_sha="",
                deps=deps,
            )

        self.assertFalse(ok)


class InstallRecoveryTest(unittest.TestCase):
    """A device that dies under the install must not end the whole run."""

    def _deps(self, *, alive: bool, install_results: list[int]):
        calls: list[str] = []
        installed: list[str] = []

        def fake_install(serial):
            calls.append(serial)
            rc = install_results[len(calls) - 1]
            cp = unittest.mock.Mock()
            cp.returncode = rc
            cp.stdout = ""
            cp.stderr = ""
            return cp

        acquired: list[str] = []

        def fake_acquire(pin):
            acquired.append(pin)
            return Acquisition("emulator-5554", EXIT_OK)

        noop = unittest.mock.Mock(returncode=0, stdout="", stderr="")
        deps = DeviceDeps(
            try_run=lambda cmd, **kw: noop,
            device_alive=lambda s: alive,
            device_model=lambda s: "Pixel_8",
            maestro_saw_device_failure=lambda stderr: False,
            apk_sha_from_device=lambda s: "abc123def456",
            gradle_install_debug=fake_install,
            acquire_device=fake_acquire,
            adb=lambda *a, **kw: "",
            adb_on=lambda s, *a, **kw: "",
            reset_adb_server=lambda: None,
            wait_for_app_resumed=lambda s, t=20: True,
            cleanup_run_fixtures=lambda *a, **kw: None,
            capture_artifacts=lambda *a, **kw: None,
            git_sha=lambda deps: "abc123def456",
            probe_device_health=lambda s: DeviceHealth(
                "alive" if alive else "gone", "the test says so"
            ),
            emulator_process_alive=lambda: False,
        )
        return deps, calls, acquired

    def test_a_device_that_died_under_the_install_is_rebooted_and_retried(self):
        deps, calls, acquired = self._deps(alive=False, install_results=[1, 0])
        serial = _cli._install_debug("emulator-5554", deps)
        self.assertEqual(serial, "emulator-5554")
        self.assertEqual(calls, ["emulator-5554", "emulator-5554"])
        self.assertEqual(acquired, ["emulator-5554"], "the AVD was not rebooted")

    def test_a_build_failure_is_not_retried(self):
        """The device answers: the build failed, and a retry cannot fix that."""
        deps, calls, acquired = self._deps(alive=True, install_results=[1])
        self.assertIsNone(_cli._install_debug("emulator-5554", deps))
        self.assertEqual(calls, ["emulator-5554"], "a failing build was retried")
        self.assertEqual(acquired, [])

    def test_a_successful_install_is_not_retried(self):
        deps, calls, acquired = self._deps(alive=True, install_results=[0])
        self.assertEqual(_cli._install_debug("emulator-5554", deps), "emulator-5554")
        self.assertEqual(calls, ["emulator-5554"])


class ALostDeviceStopsTheRunTest(unittest.TestCase):
    """A device that cannot be recovered ends the run; it does not fail it.

    Measured on a physical phone (2026-10-28): the wireless adb connection
    dropped on the seventh flow, and the runner carried on — every remaining
    flow "failed" in four seconds, and the report read as ninety broken flows
    instead of one lost device. The report has to name the cause.
    """

    def _deps(self):
        """The fake Maestro writes a report that says the device died."""
        base = _deps([_DEAD_DEVICE_REPORT])
        noop = unittest.mock.Mock(returncode=0, stdout="", stderr="")
        return dataclasses.replace(
            base,
            try_run=base.try_run,
            device_alive=lambda s: False,
            # The acquisition fails: no device, and none could be started.
            acquire_device=lambda pin: Acquisition(None, EXIT_NOT_READY),
            adb=lambda *a, **kw: "",
            adb_on=lambda s, *a, **kw: "",
            reset_adb_server=lambda: None,
            wait_for_app_resumed=lambda s, t=20: True,
            cleanup_run_fixtures=lambda *a, **kw: None,
            capture_artifacts=lambda *a, **kw: None,
            probe_device_health=lambda s: DeviceHealth("gone", "the test says so"),
            emulator_process_alive=lambda: False,
        )

    def test_an_unrecoverable_device_raises_instead_of_failing_the_flow(self):
        with tempfile.TemporaryDirectory() as tmp:
            flow = pathlib.Path(tmp) / "some-flow.yaml"
            flow.write_text("appId: com.singularity.todo\n", encoding="utf-8")
            with self.assertRaises(DeviceLostError):
                run_one_flow(
                    flow_path=flow,
                    serial="emulator-5554",
                    junit_dir=pathlib.Path(tmp) / "reports",
                    skip_install=True,
                    allow_stale=True,
                    max_retries=1,
                    expected_sha="",
                    deps=self._deps(),
                )

    def test_the_error_names_the_device_and_the_reason(self):
        with tempfile.TemporaryDirectory() as tmp:
            flow = pathlib.Path(tmp) / "some-flow.yaml"
            flow.write_text("appId: com.singularity.todo\n", encoding="utf-8")
            with self.assertRaises(DeviceLostError) as caught:
                run_one_flow(
                    flow_path=flow,
                    serial="emulator-5554",
                    junit_dir=pathlib.Path(tmp) / "reports",
                    skip_install=True,
                    allow_stale=True,
                    max_retries=1,
                    expected_sha="",
                    deps=self._deps(),
                )
            self.assertEqual(caught.exception.serial, "emulator-5554")
            self.assertIn("emulator-5554", str(caught.exception))
            self.assertTrue(caught.exception.reason)


if __name__ == "__main__":
    unittest.main()

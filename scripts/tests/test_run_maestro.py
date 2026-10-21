#!/usr/bin/env python3
"""Unit tests for maestro package (flow tag matching, collection, and integration).

Run with: python3 -m unittest discover -s scripts/tests

These tests cover the pure functions: flow_tags, flow_has_tag, collect_flows,
filter_flows_by_tag, as well as FakeMaestro integration tests.
"""
from __future__ import annotations

import pathlib
import tempfile
import unittest
import unittest.mock
import urllib.parse
import os

import emulator.maestro
import emulator.maestro._artifacts as _artifacts
import emulator.maestro._cli as _cli
import emulator.maestro._device as _device
import emulator.maestro._fixtures as _fixtures
import emulator.maestro._provenance as _provenance

# Submodules for patching _cli.main() (which still uses module globals).
import emulator.maestro._subprocess as _subprocess

# DeviceDeps for the new testable signature of run_one_flow.
from emulator._device import DeviceHealth
from emulator._startup import Acquisition
from emulator._types import EXIT_NOT_READY, EXIT_OK, EXIT_TIMEOUT

# Import from the package directly.
from emulator.maestro import (
    FakeMaestro,
    FakeMaestroResult,
    collect_flows,
    filter_flows_by_tag,
    flow_has_tag,
    flow_tags,
    run_one_flow,
)
from emulator.maestro._types import EXIT_FAIL, EXIT_PASS, DeviceDeps

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent

# The CLI takes a device lock before it runs anything, and the lock is a real
# file in the real lock directory. A test that drives the CLI must not take
# the lock of the device a developer's run is using — and must not be refused
# because one is in progress.
_LOCK_DIR = tempfile.mkdtemp(prefix="maestro-lock-test-")
os.environ["MAESTRO_LOCK_DIR"] = _LOCK_DIR


# ── pure function tests ────────────────────────────────────────────────────────

class FlowTagsTest(unittest.TestCase):

    def test_regression_tag_found(self):
        f = ROOT / "Maestro/flows/tasks/01-set-priority.yaml"
        self.assertTrue(flow_has_tag(f, "regression"))

    def test_tasks_tag_found(self):
        f = ROOT / "Maestro/flows/tasks/01-set-priority.yaml"
        self.assertTrue(flow_has_tag(f, "tasks"))

    def test_smoke_tag_not_present(self):
        f = ROOT / "Maestro/flows/tasks/01-set-priority.yaml"
        self.assertFalse(flow_has_tag(f, "smoke"))

    def test_no_tags_returns_empty(self):
        flows = list((ROOT / "Maestro/flows/agenda").glob("*.yaml"))
        if flows:
            tags = flow_tags(flows[0])
            self.assertIsInstance(tags, list)

    def test_multiple_tags_all_found(self):
        f = ROOT / "Maestro/flows/tasks/01-set-priority.yaml"
        tags = flow_tags(f)
        self.assertIn("regression", tags)
        self.assertIn("tasks", tags)


class CollectFlowsTest(unittest.TestCase):

    def test_directory_returns_sorted_yaml_files(self):
        flows_dir = ROOT / "Maestro/flows/tasks"
        flows = collect_flows(flows_dir)
        self.assertGreater(len(flows), 0)
        self.assertTrue(all(f.suffix == ".yaml" for f in flows))
        names = [f.name for f in flows]
        self.assertEqual(names, sorted(names, key=str.lower))

    def test_single_file_returns_list_of_that_file(self):
        f = ROOT / "Maestro/flows/tasks/01-set-priority.yaml"
        flows = collect_flows(f)
        self.assertEqual(len(flows), 1)
        self.assertEqual(flows[0], f)

    def test_directory_with_no_yaml_returns_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            empty_dir = pathlib.Path(tmp) / "empty"
            empty_dir.mkdir()
            flows = collect_flows(empty_dir)
            self.assertEqual(flows, [])


class FilterFlowsByTagTest(unittest.TestCase):

    def test_filter_by_regression_tag(self):
        flows_dir = ROOT / "Maestro/flows/tasks"
        all_flows = collect_flows(flows_dir)
        tagged = filter_flows_by_tag(all_flows, ["regression"])
        self.assertGreater(len(tagged), 0)
        for f in tagged:
            self.assertTrue(flow_has_tag(f, "regression"))

    def test_filter_by_unknown_tag_returns_empty(self):
        flows_dir = ROOT / "Maestro/flows/tasks"
        all_flows = collect_flows(flows_dir)
        tagged = filter_flows_by_tag(all_flows, ["nonexistent-tag-xyz"])
        self.assertEqual(len(tagged), 0)

    def test_empty_tags_returns_all_flows(self):
        flows_dir = ROOT / "Maestro/flows/tasks"
        all_flows = collect_flows(flows_dir)
        result = filter_flows_by_tag(all_flows, [])
        self.assertEqual(result, all_flows)

    def test_comma_tag(self):
        flows_dir = ROOT / "Maestro/flows/tasks"
        all_flows = collect_flows(flows_dir)
        result = filter_flows_by_tag(all_flows, ["regression"])
        self.assertGreater(len(result), 0)


class MaestroHelpersInventoryTest(unittest.TestCase):
    """Verify the helpers/ directory is in the expected state."""

    def test_live_helpers_all_used(self):
        live = [
            "launch-clean.yaml",
            "seed-task.yaml",
            "seed.yaml",
            "relaunch.yaml",
            "calendar-day-cell.yaml",
            "calendar-tap-day-cell.yaml",
            "session-signedout.yaml",
            "settings-account-tab.yaml",
        ]
        for name in live:
            helper = ROOT / "Maestro/helpers" / name
            self.assertTrue(helper.exists(), f"{name} should exist")
            used = [
                flow
                for flow in (ROOT / "Maestro/flows").rglob("*.yaml")
                if name in flow.read_text(encoding="utf-8")
            ]
            self.assertTrue(
                used,
                f"{name} is a live helper and no flow uses it",
            )

    def test_deprecated_helpers_are_stubs(self):
        deprecated = ["open-menu-item.yaml", "start-focus-task.yaml"]
        for name in deprecated:
            path = ROOT / "Maestro/helpers" / name
            self.assertTrue(path.exists(), f"{name} should exist")
            text = path.read_text()
            self.assertIn("DEPRECATED", text, f"{name} should be marked DEPRECATED")


# ── integration tests ───────────────────────────────────────────────────────────

def _make_deps(
    fake_maestro: FakeMaestro,
    *,
    serial: str = "emulator-5554",
    maestro_result_sha: str = "abc123def456",
    device_alive: bool = True,
) -> DeviceDeps:
    """Build a test DeviceDeps bundle wrapping FakeMaestro.

    No module globals are modified — all fakes are enclosed in the returned
    struct and passed directly to ``run_one_flow(deps=...)``.
    """
    alive = [device_alive]
    #: (serial, args) for every device-scoped adb call, in order.
    recorded: list[tuple[str, tuple[str, ...]]] = []

    def fake_try_run(cmd, **kwargs):
        if cmd and cmd[0] == "maestro":
            return fake_maestro.test(pathlib.Path(cmd[2]))
        cp = unittest.mock.Mock()
        cp.returncode = 0
        cp.stdout = ""
        cp.stderr = ""
        return cp

    def fake_adb(*args, **kw):
        if args and args[0] == "wait-for-device":
            return "" if alive[0] else None
        if args and args[0] == "start-server":
            return "" if alive[0] else None
        if args and args[0] == "devices":
            return f"List of devices attached\n{serial}\tdevice\n" if alive[0] else ""
        if len(args) >= 3 and args[0] == "-s" and args[2] == "get-state":
            return "device" if alive[0] else "unknown"
        if len(args) >= 5 and args[0] == "-s" and args[2] == "shell" and args[3] == "echo" and args[4] == "ping":
            return "ping" if alive[0] else None
        if len(args) >= 5 and args[0] == "-s" and args[2] == "shell" and args[3] == "dumpsys":
            return f"versionName=1.0.0+{maestro_result_sha}"
        if len(args) >= 4 and args[0] == "-s" and args[2] == "shell" and args[3] == "getprop":
            return serial if alive[0] else ""
        return None

    def fake_adb_on(serial, *args, **kw):
        """Device-scoped adb. Re-routes through fake_adb with `-s` prepended so
        one table answers both forms, and records every call for assertions."""
        recorded.append((serial, args))
        return fake_adb("-s", serial, *args, **kw)

    noop_cp = unittest.mock.Mock()
    noop_cp.returncode = 0
    noop_cp.stdout = ""
    noop_cp.stderr = ""

    def fake_maestro_saw_device_failure(stderr):
        return "devices connected" in stderr or "Device server died" in stderr

    def fake_device_model(s):
        return "Pixel_8"

    def fake_wait_for_app_resumed(s, timeout=20):
        return True

    def fake_cleanup_run_fixtures(serial, run_id, mode, deps):
        pass

    def fake_capture_artifacts(serial, flow_name, artifact_dir, deps):
        pass

    def fake_git_sha(deps):
        return "abc123def456"

    def fake_acquire_device(pin):
        # Models the real contract: ACQUIRES (a device comes back, or an exit
        # code explaining why not). The old fake returned a bare serial and
        # modelled only a readiness re-check, which is what let the "no device
        # when SERIAL is empty" bug through.
        return Acquisition(serial if alive[0] else None,
                           EXIT_OK if alive[0] else EXIT_NOT_READY)

    return DeviceDeps(
        try_run=fake_try_run,
        device_alive=lambda s: alive[0],
        device_model=fake_device_model,
        maestro_saw_device_failure=fake_maestro_saw_device_failure,
        apk_sha_from_device=lambda s: fake_maestro._device_sha,
        gradle_install_debug=lambda s: noop_cp,
        acquire_device=fake_acquire_device,
        adb=fake_adb,
        adb_on=fake_adb_on,
        reset_adb_server=lambda: None,
        wait_for_app_resumed=fake_wait_for_app_resumed,
        cleanup_run_fixtures=fake_cleanup_run_fixtures,
        capture_artifacts=fake_capture_artifacts,
        git_sha=fake_git_sha,
        # The health probes are faked rather than defaulted: the real ones talk
        # to the adb on whichever host runs the tests, and a test must not
        # depend on a device being attached.
        probe_device_health=lambda s: DeviceHealth(
            "alive" if alive[0] else "gone", "the test says so"
        ),
        emulator_process_alive=lambda: alive[0],
    )


class RunMaestroIntegrationTest(unittest.TestCase):
    """Exercise run_one_flow via the new DeviceDeps parameter (no module patching)."""

    def test_a_busy_host_retries_without_resetting_the_adb_server(self):
        """A device-level signature while the device answers is a busy host.

        Resetting the adb server would hide the device from every other client
        for the seconds it takes to come back, so the recovery has to be a
        plain retry.
        """
        fake = FakeMaestro([
            FakeMaestroResult(1, stderr="Device server died"),
            FakeMaestroResult(0, stdout="flow passed"),
        ])
        resets: list[str] = []
        deps = _make_deps(fake)
        deps = DeviceDeps(**{
            **deps.__dict__,
            "reset_adb_server": lambda: resets.append("reset"),
            "probe_device_health": lambda s: DeviceHealth("alive", "it answered"),
            "emulator_process_alive": lambda: True,
        })
        flow = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"

        with unittest.mock.patch("emulator.maestro._runner.time.sleep", lambda _s: None):
            ok, _run_id = run_one_flow(
                flow_path=flow,
                serial="emulator-5554",
                junit_dir=pathlib.Path(tempfile.mkdtemp()),
                skip_install=True,
                allow_stale=True,
                max_retries=2,
                expected_sha="",
                deps=deps,
            )

        self.assertTrue(ok)
        self.assertEqual(2, len(fake.calls), "the flow was retried once")
        self.assertEqual([], resets, "a healthy device must not have its adb server reset")

    def test_a_device_that_is_gone_boots_the_avd_again(self):
        """No emulator process means there is nothing to reset — boot it again."""
        fake = FakeMaestro([FakeMaestroResult(1, stderr="0 devices connected")])
        resets: list[str] = []
        acquired: list[str] = []
        deps = _make_deps(fake)
        deps = DeviceDeps(**{
            **deps.__dict__,
            "device_alive": lambda s: False,
            "reset_adb_server": lambda: resets.append("reset"),
            "probe_device_health": lambda s: DeviceHealth("gone", "no answer"),
            "emulator_process_alive": lambda: False,
            "acquire_device": lambda pin: (
                acquired.append(pin)
                or Acquisition("emulator-5554", EXIT_OK)
            ),
        })
        flow = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"

        with unittest.mock.patch("emulator.maestro._runner.time.sleep", lambda _s: None):
            ok, _run_id = run_one_flow(
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
        self.assertTrue(acquired, "the AVD was booted again")
        self.assertEqual({"emulator-5554"}, set(acquired))
        self.assertEqual([], resets, "there was no emulator to reset the server for")

    def test_run_one_flow_passes_on_maestro_success(self):
        fake = FakeMaestro([FakeMaestroResult(0, stdout="flow passed")])
        deps = _make_deps(fake)
        flow = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"
        ok, run_id = run_one_flow(
            flow, serial="emulator-5554",
            junit_dir=pathlib.Path("/tmp/junit"),
            skip_install=False, allow_stale=False,
            max_retries=0, expected_sha="abc123def456",
            deps=deps,
        )
        self.assertTrue(ok)
        self.assertIsNotNone(run_id)
        self.assertEqual(len(fake.calls), 1)
        self.assertEqual(fake.calls[0], flow)

    def test_run_one_flow_fails_on_maestro_nonzero(self):
        fake = FakeMaestro([FakeMaestroResult(1, stderr="Assertion failed")])
        deps = _make_deps(fake)
        flow = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"
        ok, run_id = run_one_flow(
            flow, serial="emulator-5554",
            junit_dir=pathlib.Path("/tmp/junit"),
            skip_install=False, allow_stale=False,
            max_retries=0, expected_sha="abc123def456",
            deps=deps,
        )
        self.assertFalse(ok)
        self.assertIsNotNone(run_id)

    def test_run_one_flow_retries_on_device_failure_then_succeeds(self):
        device_error = FakeMaestroResult(1, stderr="0 devices connected")
        success = FakeMaestroResult(0)
        fake = FakeMaestro([device_error, success])
        deps = _make_deps(fake, device_alive=True)
        flow = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"
        ok, run_id = run_one_flow(
            flow, serial="emulator-5554",
            junit_dir=pathlib.Path("/tmp/junit"),
            skip_install=False, allow_stale=False,
            max_retries=2, expected_sha="abc123def456",
            deps=deps,
        )
        self.assertTrue(ok)
        self.assertEqual(len(fake.calls), 2)

    def test_run_one_flow_exhausts_retries_and_returns_false(self):
        device_error = FakeMaestroResult(1, stderr="0 devices connected")
        fake = FakeMaestro([device_error, device_error, device_error])
        deps = _make_deps(fake, device_alive=True)
        flow = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"
        ok, run_id = run_one_flow(
            flow, serial="emulator-5554",
            junit_dir=pathlib.Path("/tmp/junit"),
            skip_install=False, allow_stale=False,
            max_retries=2, expected_sha="abc123def456",
            deps=deps,
        )
        self.assertFalse(ok)
        self.assertEqual(len(fake.calls), 3)

    def test_run_one_flow_checks_apk_sha_on_retry_after_relaunch(self):
        device_error = FakeMaestroResult(1, stderr="Device server died")
        success = FakeMaestroResult(0)
        fake = FakeMaestro([device_error, success])
        fake.set_device_sha("old000old000")  # stale APK on relaunched device
        deps = _make_deps(fake, maestro_result_sha="abc123def456", device_alive=True)
        flow = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"
        ok, run_id = run_one_flow(
            flow, serial="emulator-5554",
            junit_dir=pathlib.Path("/tmp/junit"),
            skip_install=False, allow_stale=False,
            max_retries=2, expected_sha="abc123def456",
            deps=deps,
        )
        # SHA mismatch after device relaunch → signals caller to reinstall
        self.assertFalse(ok)

    def test_skip_install_skips_gradle_install(self):
        """Verify --skip-install prevents gradle_install_debug from being called.

        The test patches _cli._default_device_deps so main() receives a fully-faked
        DeviceDeps (device_alive=True, adb=noop, wait_for_app_resumed=True, etc.)
        without touching any module globals. It wraps gradle_install_debug to assert
        it is never called when --skip-install is set.
        """
        fake_maestro = FakeMaestro([FakeMaestroResult(0)])

        def fake_try_run(cmd, **kw):
            if cmd and cmd[0] == "maestro":
                # Maestro writes the JUnit report it was asked for; the fake
                # has to as well, or the flow looks like one that never ran.
                if "--output" in cmd:
                    report = pathlib.Path(cmd[cmd.index("--output") + 1])
                    report.parent.mkdir(parents=True, exist_ok=True)
                    report.write_text(
                        '<?xml version="1.0" encoding="UTF-8"?>\n'
                        '<testsuite name="smoke" tests="1" failures="0"/>\n'
                    )
                return fake_maestro.test(pathlib.Path(cmd[2]))
            cp = unittest.mock.Mock()
            cp.returncode = 0
            cp.stdout = ""
            cp.stderr = ""
            return cp

        # Build a DeviceDeps that is fully fake — all device primitives are noop/fast.
        noop_cp = unittest.mock.Mock()
        noop_cp.returncode = 0
        noop_cp.stdout = ""
        noop_cp.stderr = ""

        gradle_install_mock = unittest.mock.Mock(return_value=noop_cp)

        fake_deps = DeviceDeps(
            try_run=fake_try_run,
            device_alive=lambda s: True,
            device_model=lambda s: "Pixel_8",
            maestro_saw_device_failure=lambda stderr: False,
            apk_sha_from_device=lambda s: "abc123def456",
            gradle_install_debug=gradle_install_mock,
            acquire_device=lambda pin: Acquisition("emulator-5554", EXIT_OK),
            adb=lambda *a, **kw: "",
            adb_on=lambda serial, *a, **kw: "",
            reset_adb_server=lambda: None,
            wait_for_app_resumed=lambda s, timeout=20: True,
            cleanup_run_fixtures=lambda serial, run_id, mode, deps: None,
            capture_artifacts=lambda serial, name, artifact_dir, deps: None,
            git_sha=lambda deps: "abc123def456",
        )

        # Patch _cli module so main() gets the fake deps.
        orig_default_deps = _cli._default_device_deps
        try:
            _cli._default_device_deps = lambda: fake_deps

            from click.testing import CliRunner
            from emulator.maestro import EXIT_PASS
            runner = CliRunner()
            result = runner.invoke(_cli.main, [
                "--flow", str(ROOT / "Maestro/flows/smoke/01-launch-today.yaml"),
                "--serial", "emulator-5554",
                "--skip-install",
                "--allow-stale-apk",
            ])
            self.assertEqual(result.exit_code, EXIT_PASS, f"output={result.output}")

            self.assertEqual(
                gradle_install_mock.call_count, 0,
                "gradle_install_debug should NOT be called when --skip-install is set",
            )
        finally:
            _cli._default_device_deps = orig_default_deps


class AcquireDeviceRegressionTest(unittest.TestCase):
    """`run-maestro` with no SERIAL must acquire a device, not fail.

    This is the regression that took out `just gm agenda`: `_cli.main` evaluated
    `deps.ensure_device(serial) if serial else None`, so an empty SERIAL — which
    is exactly what `tests::gate-maestro` passes — short-circuited to None and
    returned EXIT_FAIL before adb was ever called. The only CLI-level test that
    existed always passed `--serial`, so the branch was never executed.
    """

    FLOW = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"

    def _invoke(self, deps, argv):
        from click.testing import CliRunner

        orig = _cli._default_device_deps
        try:
            _cli._default_device_deps = lambda: deps
            return CliRunner().invoke(_cli.main, argv)
        finally:
            _cli._default_device_deps = orig

    @staticmethod
    def _deps(acquire, *, ok=True, write_report=True):
        cp = unittest.mock.Mock(returncode=0, stdout="", stderr="")

        def fake_try_run(cmd, **kw):
            if cmd and cmd[0] == "maestro":
                # A real Maestro run writes the JUnit report it was asked for.
                # The fake has to do the same, or every flow looks like one that
                # never reported — which is a failure the CLI now reports.
                if write_report and "--output" in cmd:
                    report = pathlib.Path(cmd[cmd.index("--output") + 1])
                    report.parent.mkdir(parents=True, exist_ok=True)
                    report.write_text(
                        '<?xml version="1.0" encoding="UTF-8"?>\n'
                        '<testsuite name="smoke" tests="1" failures="0"/>\n'
                    )
                m = unittest.mock.Mock(returncode=0, stdout="flow passed", stderr="")
                return m
            return cp

        return DeviceDeps(
            try_run=fake_try_run,
            device_alive=lambda s: True,
            device_model=lambda s: "Pixel_8",
            maestro_saw_device_failure=lambda stderr: False,
            apk_sha_from_device=lambda s: "abc123def456",
            gradle_install_debug=lambda s: cp,
            acquire_device=acquire,
            adb=lambda *a, **kw: "",
            adb_on=lambda serial, *a, **kw: "",
            reset_adb_server=lambda: None,
            wait_for_app_resumed=lambda s, timeout=20: True,
            cleanup_run_fixtures=lambda serial, run_id, mode, deps: None,
            capture_artifacts=lambda serial, name, artifact_dir, deps: None,
            git_sha=lambda deps: "abc123def456",
        )

    def test_no_serial_still_acquires_and_runs(self):
        seen: list[str] = []

        def acquire(pin):
            seen.append(pin)
            return Acquisition("emulator-5554", EXIT_OK)

        result = self._invoke(
            self._deps(acquire),
            ["--flow", str(self.FLOW), "--skip-install", "--allow-stale-apk"],
        )
        self.assertEqual(result.exit_code, EXIT_PASS, f"output={result.output}")
        self.assertEqual(seen, [""], "acquisition must run even with an empty pin")

    def test_acquired_serial_reaches_the_flow(self):
        """The serial from acquisition must be the one flows are run against.

        Using the original (empty) serial downstream would target nothing while
        acquisition appeared to have succeeded.
        """
        adb_calls: list[str] = []
        deps = self._deps(lambda pin: Acquisition("emulator-5554", EXIT_OK))
        deps = DeviceDeps(**{**deps.__dict__,
                             "adb_on": lambda serial, *a, **kw: adb_calls.append(serial) or ""})

        result = self._invoke(
            deps, ["--flow", str(self.FLOW), "--skip-install", "--allow-stale-apk"]
        )
        self.assertEqual(result.exit_code, EXIT_PASS, f"output={result.output}")
        self.assertTrue(adb_calls, "no device-scoped adb call was made")
        self.assertEqual(set(adb_calls), {"emulator-5554"})

    def test_acquisition_failure_reports_failure_to_the_shell(self):
        """A failed acquisition must reach the shell as a non-zero exit.

        Click 8 standalone mode discards a callback's return value and exits 0,
        so this command once reported success on every failure — and
        `tests::gate-maestro` runs under `set -e`. The attribution string itself
        goes to stderr (loguru binds sys.stderr at import, so CliRunner cannot
        capture it); the exit code is the part a caller depends on.
        """
        result = self._invoke(
            self._deps(lambda pin: Acquisition(None, EXIT_TIMEOUT), ok=False),
            ["--flow", str(self.FLOW), "--skip-install", "--allow-stale-apk"],
        )
        self.assertEqual(result.exit_code, EXIT_FAIL)

    def test_passing_flows_exit_zero(self):
        result = self._invoke(
            self._deps(lambda pin: Acquisition("emulator-5554", EXIT_OK)),
            ["--flow", str(self.FLOW), "--skip-install", "--allow-stale-apk"],
        )
        self.assertEqual(result.exit_code, EXIT_PASS)


class UnreportedFlowTest(unittest.TestCase):
    """A flow that reports nothing must not be counted as a flow that passed.

    A suite is a directory, and a run reports the flows it ran. A flow that
    Maestro never executed leaves no JUnit file: it is in neither the passed
    list nor the failed list, the run ends with "flows passed", and the green
    says nothing about the flow that was supposed to run. The report has to
    exist, and it has to be this run's report — a file left over from an
    earlier run is not evidence.
    """

    FLOW = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"

    def _invoke(self, deps, junit_dir):
        from click.testing import CliRunner

        orig = _cli._default_device_deps
        try:
            _cli._default_device_deps = lambda: deps
            return CliRunner().invoke(
                _cli.main,
                [
                    "--flow", str(self.FLOW),
                    "--skip-install", "--allow-stale-apk",
                    "--junit-dir", str(junit_dir),
                ],
            )
        finally:
            _cli._default_device_deps = orig

    @staticmethod
    def _deps(*, write_report):
        cp = unittest.mock.Mock(returncode=0, stdout="", stderr="")

        def fake_try_run(cmd, **kw):
            if cmd and cmd[0] == "maestro":
                if write_report and "--output" in cmd:
                    report = pathlib.Path(cmd[cmd.index("--output") + 1])
                    report.parent.mkdir(parents=True, exist_ok=True)
                    report.write_text(
                        '<?xml version="1.0" encoding="UTF-8"?>\n'
                        '<testsuite name="smoke" tests="1" failures="0"/>\n'
                    )
                return unittest.mock.Mock(returncode=0, stdout="flow passed", stderr="")
            return cp

        return DeviceDeps(
            try_run=fake_try_run,
            device_alive=lambda s: True,
            device_model=lambda s: "Pixel_8",
            maestro_saw_device_failure=lambda stderr: False,
            apk_sha_from_device=lambda s: "abc123def456",
            gradle_install_debug=lambda s: cp,
            acquire_device=lambda pin: Acquisition("emulator-5554", EXIT_OK),
            adb=lambda *a, **kw: "",
            adb_on=lambda serial, *a, **kw: "",
            reset_adb_server=lambda: None,
            wait_for_app_resumed=lambda s, timeout=20: True,
            cleanup_run_fixtures=lambda serial, run_id, mode, deps: None,
            capture_artifacts=lambda serial, name, artifact_dir, deps: None,
            git_sha=lambda deps: "abc123def456",
        )

    def test_a_flow_that_writes_no_report_fails_the_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._invoke(self._deps(write_report=False), pathlib.Path(tmp))
        self.assertEqual(result.exit_code, EXIT_FAIL)

    def test_a_flow_that_reports_passes_the_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._invoke(self._deps(write_report=True), pathlib.Path(tmp))
        self.assertEqual(result.exit_code, EXIT_PASS, f"output={result.output}")

    def test_a_report_from_an_earlier_run_does_not_count(self):
        """A stale file in the report directory is not this run's evidence."""
        with tempfile.TemporaryDirectory() as tmp:
            junit_dir = pathlib.Path(tmp)
            stale = junit_dir / f"{self.FLOW.stem}.xml"
            stale.parent.mkdir(parents=True, exist_ok=True)
            stale.write_text(
                '<?xml version="1.0" encoding="UTF-8"?>\n'
                '<testsuite name="smoke" tests="1" failures="0"/>\n'
            )
            old = stale.stat().st_mtime
            import os
            os.utime(stale, (old - 3600, old - 3600))
            result = self._invoke(self._deps(write_report=False), junit_dir)
        self.assertEqual(result.exit_code, EXIT_FAIL)


class FixtureCleanupTest(unittest.TestCase):
    """The cleanup link must name the fixtures it is supposed to remove.

    The app deletes a task, note or project whose title *starts with* one of
    the comma-separated prefixes in ``cleanup=``. So the property that matters
    is not "the link was sent" but "the prefixes the app will read are real
    prefixes of the titles that were seeded".

    It was not, and the failure was invisible. The value was built as
    ``f"'{run_id},Buy%20milk'"`` — shell-style quotes and a pre-encoded
    space — and then URL-quoted again, so the app read the prefixes
    ``"'r4797858390"`` and ``"Buy%20milk'"``. Neither matched a title. Every
    fixture stayed on the device for the rest of the shard, and the nightly
    run of 2026-10-20 failed 43 flows across all four shards because of it:
    a flow that asserts an empty list failed on the *previous* flow's data,
    and a flow that asserts its own task failed on another flow's task being
    in the way.

    These tests assert the property, not the implementation: they decode the
    link the app would receive and check the prefixes against real titles.
    """

    RUN_ID = "r4797858390"

    def _cleanup_prefixes(self, mode: str = "run") -> list[str]:
        """The prefixes the app reads, taken from the link the runner sends."""
        calls: list[str] = []

        deps = DeviceDeps(
            try_run=lambda cmd, **kw: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
            device_alive=lambda s: True,
            device_model=lambda s: "Pixel_8",
            maestro_saw_device_failure=lambda stderr: False,
            apk_sha_from_device=lambda s: "abc123def456",
            gradle_install_debug=lambda s: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
            acquire_device=lambda pin: Acquisition("emulator-5554", EXIT_OK),
            adb=lambda *a, **kw: "",
            adb_on=lambda serial, *a, **kw: calls.append(" ".join(a)),
            reset_adb_server=lambda: None,
            wait_for_app_resumed=lambda s, timeout=20: True,
            cleanup_run_fixtures=lambda serial, run_id, mode, deps: None,
            capture_artifacts=lambda serial, name, artifact_dir, deps: None,
            git_sha=lambda deps: "abc123def456",
        )

        with unittest.mock.patch.object(_fixtures.time, "sleep", lambda _s: None):
            _fixtures.cleanup_run_fixtures("emulator-5554", self.RUN_ID, mode, deps)

        self.assertEqual(len(calls), 1, f"expected exactly one cleanup link, got {calls}")
        link = calls[0]
        self.assertIn("todo-debug://seed?cleanup=", link)
        raw = link.split("todo-debug://seed?cleanup=", 1)[1]
        decoded = urllib.parse.unquote(raw)
        return [p.strip() for p in decoded.split(",") if p.strip()]

    def test_the_run_id_is_one_of_the_prefixes(self):
        prefixes = self._cleanup_prefixes()
        self.assertIn(self.RUN_ID, prefixes)

    def test_a_seeded_title_starts_with_one_of_the_prefixes(self):
        """The property the app actually uses: titles are matched by prefix."""
        prefixes = self._cleanup_prefixes()
        for title in (
            f"{self.RUN_ID} Anonymous task",
            f"{self.RUN_ID}_ClearSearchTask2026",
            "Buy milk",
        ):
            self.assertTrue(
                any(title.startswith(p) for p in prefixes),
                f"{title!r} is matched by none of the prefixes {prefixes!r}",
            )

    def test_the_fixed_seed_title_is_swept_too(self):
        self.assertIn("Buy milk", self._cleanup_prefixes())

    def test_the_prefixes_carry_no_quoting(self):
        """Quotes and pre-encoded spaces are what broke it; they must not come back."""
        prefixes = self._cleanup_prefixes()
        for prefix in prefixes:
            self.assertNotIn("'", prefix)
            self.assertNotIn("%", prefix)

    def test_the_all_mode_sweeps_everything_instead(self):
        calls: list[str] = []
        deps = DeviceDeps(
            try_run=lambda cmd, **kw: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
            device_alive=lambda s: True,
            device_model=lambda s: "Pixel_8",
            maestro_saw_device_failure=lambda stderr: False,
            apk_sha_from_device=lambda s: "abc123def456",
            gradle_install_debug=lambda s: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
            acquire_device=lambda pin: Acquisition("emulator-5554", EXIT_OK),
            adb=lambda *a, **kw: "",
            adb_on=lambda serial, *a, **kw: calls.append(" ".join(a)),
            reset_adb_server=lambda: None,
            wait_for_app_resumed=lambda s, timeout=20: True,
            cleanup_run_fixtures=lambda serial, run_id, mode, deps: None,
            capture_artifacts=lambda serial, name, artifact_dir, deps: None,
            git_sha=lambda deps: "abc123def456",
        )
        with unittest.mock.patch.object(_fixtures.time, "sleep", lambda _s: None):
            _fixtures.cleanup_run_fixtures("emulator-5554", self.RUN_ID, "all", deps)
        self.assertIn("todo-debug://seed?reset=content", calls[0])

    def test_the_off_mode_sends_nothing(self):
        calls: list[str] = []
        deps = DeviceDeps(
            try_run=lambda cmd, **kw: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
            device_alive=lambda s: True,
            device_model=lambda s: "Pixel_8",
            maestro_saw_device_failure=lambda stderr: False,
            apk_sha_from_device=lambda s: "abc123def456",
            gradle_install_debug=lambda s: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
            acquire_device=lambda pin: Acquisition("emulator-5554", EXIT_OK),
            adb=lambda *a, **kw: "",
            adb_on=lambda serial, *a, **kw: calls.append(" ".join(a)),
            reset_adb_server=lambda: None,
            wait_for_app_resumed=lambda s, timeout=20: True,
            cleanup_run_fixtures=lambda serial, run_id, mode, deps: None,
            capture_artifacts=lambda serial, name, artifact_dir, deps: None,
            git_sha=lambda deps: "abc123def456",
        )
        with unittest.mock.patch.object(_fixtures.time, "sleep", lambda _s: None):
            _fixtures.cleanup_run_fixtures("emulator-5554", self.RUN_ID, "off", deps)
        self.assertEqual(calls, [])


class FailedFlowCleanupTest(unittest.TestCase):
    """A failing flow's fixtures must not survive into the next flow.

    Cleaning on success only is deliberate — a failing flow keeps its data so
    the failure can be triaged against it. But "until the next flow starts" is
    long enough. Leaving the data for the rest of the shard makes every later
    flow that asserts an empty list fail on the previous flow's leftovers, and
    a suite whose failures cascade reports one defect as forty.

    Two scopes, and the default is the wide one. In the default scope the
    device is wiped *before every flow*, so a flow's result cannot depend on
    what ran before it — that is the property the suite needs to mean
    anything. In the narrow scope the wipe knows the run id and the one fixed
    title, so a flow's fixtures are swept but a row the suite forgot to name
    survives, and the order of the flows starts to matter.
    """

    def _run(self, tmp: str, cleanup_scope: str | None = None):
        """The repository's smoke flows, the first of which fails.

        Returns the cleanup calls and the number of them that had already
        happened when each flow started.
        """
        flows = ROOT / "Maestro/flows/smoke"

        cleanup_calls: list[str] = []
        cleanup_before: dict[str, int] = {}
        attempts: list[str] = []

        def fake_try_run(cmd, **kw):
            flow = pathlib.Path(cmd[2]).stem
            attempts.append(flow)
            cleanup_before[flow] = len(cleanup_calls)
            if len(attempts) == 1:
                # The first flow fails.
                return unittest.mock.Mock(returncode=1, stdout="flow failed", stderr="")
            report = pathlib.Path(cmd[cmd.index("--output") + 1])
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text(
                '<?xml version="1.0" encoding="UTF-8"?>\n'
                f'<testsuite name="{flow}" tests="1" failures="0"/>\n'
            )
            return unittest.mock.Mock(returncode=0, stdout="flow passed", stderr="")

        deps = DeviceDeps(
            try_run=fake_try_run,
            device_alive=lambda s: True,
            device_model=lambda s: "Pixel_8",
            maestro_saw_device_failure=lambda stderr: False,
            apk_sha_from_device=lambda s: "abc123def456",
            gradle_install_debug=lambda s: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
            acquire_device=lambda pin: Acquisition("emulator-5554", EXIT_OK),
            adb=lambda *a, **kw: "",
            adb_on=lambda serial, *a, **kw: "",
            reset_adb_server=lambda: None,
            wait_for_app_resumed=lambda s, timeout=20: True,
            cleanup_run_fixtures=lambda serial, run_id, mode, deps: cleanup_calls.append(run_id),
            capture_artifacts=lambda serial, name, artifact_dir, deps: None,
            git_sha=lambda deps: "abc123def456",
        )

        from click.testing import CliRunner

        argv = [
            "--flow", str(flows),
            "--skip-install", "--allow-stale-apk",
            "--junit-dir", str(pathlib.Path(tmp) / "junit"),
        ]
        if cleanup_scope is not None:
            argv += ["--maestro-cleanup", cleanup_scope]

        orig = _cli._default_device_deps
        try:
            _cli._default_device_deps = lambda: deps
            CliRunner().invoke(_cli.main, argv)
        finally:
            _cli._default_device_deps = orig

        return cleanup_calls, cleanup_before, attempts

    def test_the_failing_flow_is_swept_before_the_next_one_starts(self):
        with tempfile.TemporaryDirectory() as tmp:
            cleanup_calls, cleanup_before, attempts = self._run(tmp)

        self.assertGreaterEqual(len(attempts), 2, "the smoke suite has fewer than two flows")
        second = attempts[1]
        self.assertGreaterEqual(
            cleanup_before[second], 1,
            f"the failing flow's fixtures were still on the device when "
            f"{second} started (cleanup calls so far: {cleanup_before[second]})",
        )
        self.assertEqual(
            len(cleanup_calls), len(attempts),
            f"every flow must be swept exactly once: {cleanup_calls}",
        )

    def test_every_flow_starts_on_a_wiped_device(self):
        """The default scope wipes before every flow, the first one included.

        The device carries whatever the run before this one left on it, and a
        flow that asserts an empty list must not be able to see it.
        """
        with tempfile.TemporaryDirectory() as tmp:
            cleanup_calls, cleanup_before, attempts = self._run(tmp)

        for index, flow in enumerate(attempts, start=1):
            self.assertEqual(
                cleanup_before[flow], index,
                f"{flow} started with {cleanup_before[flow]} wipe(s) before it; "
                f"exactly one wipe per flow is what isolates them",
            )

    def test_a_passing_flow_is_swept_immediately(self):
        """In the narrow scope a passing flow is swept on success.

        The default scope wipes before the next flow instead, and that is
        enough there. The narrow scope cannot: its sweep only knows the run
        id of the flow that failed, so a flow that succeeded has to be swept
        while its run id is still the one in hand.
        """
        with tempfile.TemporaryDirectory() as tmp:
            cleanup_calls, cleanup_before, attempts = self._run(tmp, cleanup_scope="run")

        last = attempts[-1]
        self.assertEqual(cleanup_before[last], len(attempts) - 1)

    def test_the_default_scope_wipes_the_content(self):
        """The default scope is a content wipe, not a prefix sweep.

        A prefix sweep only knows the titles the runner was told about, and
        the suite keeps adding fixed titles to that list; a content wipe knows
        everything the previous flow created, which is what isolates the
        flows from each other.
        """
        modes: list[str] = []
        flows = ROOT / "Maestro/flows/smoke"

        def fake_try_run(cmd, **kw):
            flow = pathlib.Path(cmd[2]).stem
            report = pathlib.Path(cmd[cmd.index("--output") + 1])
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text(
                '<?xml version="1.0" encoding="UTF-8"?>\n'
                f'<testsuite name="{flow}" tests="1" failures="0"/>\n'
            )
            return unittest.mock.Mock(returncode=0, stdout="flow passed", stderr="")

        deps = DeviceDeps(
            try_run=fake_try_run,
            device_alive=lambda s: True,
            device_model=lambda s: "Pixel_8",
            maestro_saw_device_failure=lambda stderr: False,
            apk_sha_from_device=lambda s: "abc123def456",
            gradle_install_debug=lambda s: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
            acquire_device=lambda pin: Acquisition("emulator-5554", EXIT_OK),
            adb=lambda *a, **kw: "",
            adb_on=lambda serial, *a, **kw: "",
            reset_adb_server=lambda: None,
            wait_for_app_resumed=lambda s, timeout=20: True,
            cleanup_run_fixtures=lambda serial, run_id, mode, deps: modes.append(mode),
            capture_artifacts=lambda serial, name, artifact_dir, deps: None,
            git_sha=lambda deps: "abc123def456",
        )

        from click.testing import CliRunner

        with tempfile.TemporaryDirectory() as tmp:
            orig = _cli._default_device_deps
            try:
                _cli._default_device_deps = lambda: deps
                CliRunner().invoke(
                    _cli.main,
                    [
                        "--flow", str(flows),
                        "--skip-install", "--allow-stale-apk",
                        "--junit-dir", str(pathlib.Path(tmp) / "junit"),
                    ],
                )
            finally:
                _cli._default_device_deps = orig

        self.assertTrue(modes, "the run swept nothing at all")
        self.assertEqual(set(modes), {"all"})


class SessionResetTest(unittest.TestCase):
    """Every flow must start from the session the app starts with.

    The E2E build keeps the session in the process: the auth repository is a
    stub that reports ``Session.SignedIn`` from the first frame and holds the
    session in memory. A flow that ends signed out therefore hands its session
    to whatever runs next, and the flow after it lands on the sign-in screen
    instead of the agenda — a failure that reads like a product defect and is
    not one.

    The reset is a fresh process — force-stop, then start — and it is a
    different kind of fixture from the row sweep: the sweep removes the rows
    a flow seeded, the reset restores the session. These tests assert the
    property, not the implementation: the app is restarted before every flow,
    so no flow decides the session of the flow after it.
    """

    def _deps(self, timeline):
        cp = unittest.mock.Mock(returncode=0, stdout="", stderr="")

        def fake_try_run(cmd, **kw):
            if cmd and cmd[0] == "maestro":
                flow = pathlib.Path(cmd[2]).stem
                timeline.append(("maestro", flow))
                report = pathlib.Path(cmd[cmd.index("--output") + 1])
                report.parent.mkdir(parents=True, exist_ok=True)
                report.write_text(
                    '<?xml version="1.0" encoding="UTF-8"?>\n'
                    f'<testsuite name="{flow}" tests="1" failures="0"/>\n'
                )
            return cp

        def fake_adb_on(serial, *a, **kw):
            timeline.append(("adb", " ".join(a)))
            return ""

        return DeviceDeps(
            try_run=fake_try_run,
            device_alive=lambda s: True,
            device_model=lambda s: "Pixel_8",
            maestro_saw_device_failure=lambda stderr: False,
            apk_sha_from_device=lambda s: "abc123def456",
            gradle_install_debug=lambda s: cp,
            acquire_device=lambda pin: Acquisition("emulator-5554", EXIT_OK),
            adb=lambda *a, **kw: "",
            adb_on=fake_adb_on,
            reset_adb_server=lambda: None,
            wait_for_app_resumed=lambda s, timeout=20: True,
            cleanup_run_fixtures=lambda serial, run_id, mode, deps: None,
            capture_artifacts=lambda serial, name, artifact_dir, deps: None,
            git_sha=lambda deps: "abc123def456",
        )

    def _run_two_flows(self, tmp):
        """Run the smoke flows through the CLI and return the timeline of what happened."""
        # The flows have to live under the repository root: the CLI logs them
        # relative to it.
        flows = ROOT / "Maestro/flows/smoke"
        timeline: list[tuple[str, str]] = []
        deps = self._deps(timeline)

        from click.testing import CliRunner

        orig = _cli._default_device_deps
        try:
            _cli._default_device_deps = lambda: deps
            result = CliRunner().invoke(
                _cli.main,
                [
                    "--flow", str(flows),
                    "--skip-install", "--allow-stale-apk",
                    "--junit-dir", str(pathlib.Path(tmp) / "junit"),
                ],
            )
        finally:
            _cli._default_device_deps = orig
        return timeline, result

    def test_the_app_is_restarted_before_every_flow(self):
        """A flow that ends signed out must not decide the next flow's session."""
        with tempfile.TemporaryDirectory() as tmp:
            timeline, result = self._run_two_flows(tmp)

        self.assertEqual(result.exit_code, EXIT_PASS, f"output={result.output}")
        maestro_at = [i for i, (kind, _) in enumerate(timeline) if kind == "maestro"]
        self.assertGreaterEqual(len(maestro_at), 2, f"expected two flows, timeline={timeline}")
        for index, position in enumerate(maestro_at):
            window = timeline[maestro_at[index - 1] if index else 0:position]
            calls = [value for kind, value in window if kind == "adb"]
            self.assertTrue(
                any("am force-stop" in call for call in calls),
                f"the app was not stopped before the flow: {window}",
            )
            self.assertTrue(
                any("am start" in call for call in calls),
                f"the app was not started before the flow: {window}",
            )

    def test_the_reset_restarts_the_app_on_the_device_it_was_given(self):
        """The reset is device-scoped: `adb -s <serial>`, not the default device."""
        calls: list[str] = []
        deps = DeviceDeps(
            try_run=lambda cmd, **kw: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
            device_alive=lambda s: True,
            device_model=lambda s: "Pixel_8",
            maestro_saw_device_failure=lambda stderr: False,
            apk_sha_from_device=lambda s: "abc123def456",
            gradle_install_debug=lambda s: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
            acquire_device=lambda pin: Acquisition("emulator-5554", EXIT_OK),
            adb=lambda *a, **kw: "",
            adb_on=lambda serial, *a, **kw: calls.append(" ".join(a)),
            reset_adb_server=lambda: None,
            wait_for_app_resumed=lambda s, timeout=20: True,
            cleanup_run_fixtures=lambda serial, run_id, mode, deps: None,
            capture_artifacts=lambda serial, name, artifact_dir, deps: None,
            git_sha=lambda deps: "abc123def456",
        )
        with unittest.mock.patch.object(_fixtures.time, "sleep", lambda _s: None):
            _fixtures.reset_session("emulator-5554", deps)
        self.assertTrue(any("am force-stop" in call for call in calls), calls)
        self.assertTrue(any("am start" in call for call in calls), calls)

    def test_the_reset_does_nothing_on_a_dead_device(self):
        calls: list[str] = []
        deps = DeviceDeps(
            try_run=lambda cmd, **kw: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
            device_alive=lambda s: False,
            device_model=lambda s: "Pixel_8",
            maestro_saw_device_failure=lambda stderr: False,
            apk_sha_from_device=lambda s: "abc123def456",
            gradle_install_debug=lambda s: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
            acquire_device=lambda pin: Acquisition(None, EXIT_NOT_READY),
            adb=lambda *a, **kw: "",
            adb_on=lambda serial, *a, **kw: calls.append(" ".join(a)),
            reset_adb_server=lambda: None,
            wait_for_app_resumed=lambda s, timeout=20: True,
            cleanup_run_fixtures=lambda serial, run_id, mode, deps: None,
            capture_artifacts=lambda serial, name, artifact_dir, deps: None,
            git_sha=lambda deps: "abc123def456",
        )
        with unittest.mock.patch.object(_fixtures.time, "sleep", lambda _s: None):
            _fixtures.reset_session("emulator-5554", deps)
        self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()

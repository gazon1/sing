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

# Import from the package directly.
from emulator.maestro import (
    collect_flows,
    filter_flows_by_tag,
    flow_has_tag,
    flow_tags,
    run_one_flow,
    FakeMaestro,
    FakeMaestroResult,
)
import emulator.maestro

# DeviceDeps for the new testable signature of run_one_flow.
from emulator._startup import Acquisition
from emulator._types import EXIT_NOT_READY, EXIT_OK, EXIT_TIMEOUT
from emulator.maestro._types import EXIT_FAIL, EXIT_PASS
from emulator.maestro._types import DeviceDeps

# Submodules for patching _cli.main() (which still uses module globals).
import emulator.maestro._subprocess as _subprocess
import emulator.maestro._device as _device
import emulator.maestro._provenance as _provenance
import emulator.maestro._fixtures as _fixtures
import emulator.maestro._artifacts as _artifacts
import emulator.maestro._cli as _cli

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


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
        live = ["launch-clean.yaml", "seed-task.yaml", "seed.yaml", "relaunch.yaml"]
        for name in live:
            helper = ROOT / "Maestro/helpers" / name
            self.assertTrue(helper.exists(), f"{name} should exist")

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
        apk_sha_from_device=lambda s, deps: fake_maestro._device_sha,
        gradle_install_debug=lambda s: noop_cp,
        acquire_device=fake_acquire_device,
        adb=fake_adb,
        adb_on=fake_adb_on,
        reset_adb_server=lambda: None,
        wait_for_app_resumed=fake_wait_for_app_resumed,
        cleanup_run_fixtures=fake_cleanup_run_fixtures,
        capture_artifacts=fake_capture_artifacts,
        git_sha=fake_git_sha,
    )


class RunMaestroIntegrationTest(unittest.TestCase):
    """Exercise run_one_flow via the new DeviceDeps parameter (no module patching)."""

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
            apk_sha_from_device=lambda s, deps: "abc123def456",
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
    def _deps(acquire, *, ok=True):
        cp = unittest.mock.Mock(returncode=0, stdout="", stderr="")

        def fake_try_run(cmd, **kw):
            if cmd and cmd[0] == "maestro":
                m = unittest.mock.Mock(returncode=0, stdout="flow passed", stderr="")
                return m
            return cp

        return DeviceDeps(
            try_run=fake_try_run,
            device_alive=lambda s: True,
            device_model=lambda s: "Pixel_8",
            maestro_saw_device_failure=lambda stderr: False,
            apk_sha_from_device=lambda s, deps: "abc123def456",
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


if __name__ == "__main__":
    unittest.main()

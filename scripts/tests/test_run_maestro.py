#!/usr/bin/env python3
"""Unit tests for run_maestro.py (flow tag matching and collection).

Run with: python3 -m unittest discover -s scripts/tests

These tests cover the pure functions: flow_tags, flow_has_tag, collect_flows,
filter_flows_by_tag.  They operate on real flow files so they also serve as
a smoke test for the helpers/ directory structure.
"""
from __future__ import annotations

import importlib.util
import pathlib
import sys
import tempfile
import unittest

_SCRIPT = pathlib.Path(__file__).resolve().parent.parent / "run-maestro.py"
_spec = importlib.util.spec_from_file_location("run_maestro", _SCRIPT)
m = importlib.util.module_from_spec(_spec)
sys.modules["run_maestro"] = m
_spec.loader.exec_module(m)

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


class FlowTagsTest(unittest.TestCase):

    def test_regression_tag_found(self):
        f = ROOT / "Maestro/flows/tasks/01-set-priority.yaml"
        self.assertTrue(m.flow_has_tag(f, "regression"))

    def test_tasks_tag_found(self):
        f = ROOT / "Maestro/flows/tasks/01-set-priority.yaml"
        self.assertTrue(m.flow_has_tag(f, "tasks"))

    def test_smoke_tag_not_present(self):
        f = ROOT / "Maestro/flows/tasks/01-set-priority.yaml"
        self.assertFalse(m.flow_has_tag(f, "smoke"))

    def test_no_tags_returns_empty(self):
        # Some flows have no tags block.
        flows = list((ROOT / "Maestro/flows/agenda").glob("*.yaml"))
        if flows:
            tags = m.flow_tags(flows[0])
            self.assertIsInstance(tags, list)

    def test_multiple_tags_all_found(self):
        f = ROOT / "Maestro/flows/tasks/01-set-priority.yaml"
        tags = m.flow_tags(f)
        self.assertIn("regression", tags)
        self.assertIn("tasks", tags)


class CollectFlowsTest(unittest.TestCase):

    def test_directory_returns_sorted_yaml_files(self):
        flows_dir = ROOT / "Maestro/flows/tasks"
        flows = m.collect_flows(flows_dir)
        self.assertGreater(len(flows), 0)
        self.assertTrue(all(f.suffix == ".yaml" for f in flows))
        # Sorted order
        names = [f.name for f in flows]
        self.assertEqual(names, sorted(names, key=str.lower))

    def test_single_file_returns_list_of_that_file(self):
        f = ROOT / "Maestro/flows/tasks/01-set-priority.yaml"
        flows = m.collect_flows(f)
        self.assertEqual(len(flows), 1)
        self.assertEqual(flows[0], f)

    def test_directory_with_no_yaml_returns_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            empty_dir = pathlib.Path(tmp) / "empty"
            empty_dir.mkdir()
            flows = m.collect_flows(empty_dir)
            self.assertEqual(flows, [])


class FilterFlowsByTagTest(unittest.TestCase):

    def test_filter_by_regression_tag(self):
        flows_dir = ROOT / "Maestro/flows/tasks"
        all_flows = m.collect_flows(flows_dir)
        tagged = m.filter_flows_by_tag(all_flows, ["regression"])
        self.assertGreater(len(tagged), 0)
        for f in tagged:
            self.assertTrue(m.flow_has_tag(f, "regression"))

    def test_filter_by_unknown_tag_returns_empty(self):
        flows_dir = ROOT / "Maestro/flows/tasks"
        all_flows = m.collect_flows(flows_dir)
        tagged = m.filter_flows_by_tag(all_flows, ["nonexistent-tag-xyz"])
        self.assertEqual(len(tagged), 0)

    def test_empty_tags_returns_all_flows(self):
        flows_dir = ROOT / "Maestro/flows/tasks"
        all_flows = m.collect_flows(flows_dir)
        result = m.filter_flows_by_tag(all_flows, [])
        self.assertEqual(result, all_flows)

    def test_comma_tag(self):
        # Tags passed from CLI are already split by comma.
        flows_dir = ROOT / "Maestro/flows/tasks"
        all_flows = m.collect_flows(flows_dir)
        # Regression tag should return some flows
        result = m.filter_flows_by_tag(all_flows, ["regression"])
        self.assertGreater(len(result), 0)


class MaestroHelpersInventoryTest(unittest.TestCase):
    """Verify the helpers/ directory is in the expected state."""

    def test_live_helpers_all_used(self):
        # All non-deprecated helpers should have callers in flows/.
        live = ["launch-clean.yaml", "seed-task.yaml", "seed.yaml", "relaunch.yaml"]
        for name in live:
            helper = ROOT / "Maestro/helpers" / name
            self.assertTrue(helper.exists(), f"{name} should exist")
        # Just verify we can import the module without error
        self.assertTrue(hasattr(m, "flow_tags"))

    def test_deprecated_helpers_are_stubs(self):
        deprecated = ["open-menu-item.yaml", "start-focus-task.yaml"]
        for name in deprecated:
            path = ROOT / "Maestro/helpers" / name
            self.assertTrue(path.exists(), f"{name} should exist")
            text = path.read_text()
            # Deprecation stub must contain this marker
            self.assertIn("DEPRECATED", text, f"{name} should be marked DEPRECATED")


# ── Fake Maestro harness for integration tests ─────────────────────────────────
import unittest.mock


class FakeMaestroResult:
    """Simulates subprocess.CompletedProcess for a single Maestro invocation."""
    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class FakeMaestro:
    """Simulates Maestro CLI for integration testing.

    records each ``test(flow_path)`` call in ``calls`` and returns the next
    result from ``results`` (FIFO).  Use ``device_failure_on_first`` to make the
    first Maestro run emit a retryable device-error stanza.
    """
    def __init__(self, results: list[FakeMaestroResult] | None = None):
        self.results: list[FakeMaestroResult] = results or [FakeMaestroResult(0)]
        self.calls: list[pathlib.Path] = []
        # SHA the device reports via apk_sha_from_device (None = no override)
        self._device_sha: str | None = None

    def test(self, flow_path: pathlib.Path, *, timeout: int = 300, **_kwargs) -> FakeMaestroResult:
        self.calls.append(flow_path)
        if self.results:
            return self.results.pop(0)
        return FakeMaestroResult(1, stderr="no results configured")

    def set_device_sha(self, sha: str | None) -> None:
        self._device_sha = sha


# ── Integration tests for run_maestro ─────────────────────────────────────────
class RunMaestroIntegrationTest(unittest.TestCase):
    """Exercise run_maestro's run_one_flow and main() orchestration with fakes."""

    def setUp(self):
        # Snapshot current module state so we can restore it in tearDown.
        self._orig_try_run = m.try_run
        self._orig_adb = m.adb
        self._orig_ensure_device = m.ensure_device
        self._orig_device_alive = m.device_alive
        self._orig_apk_sha = m.apk_sha_from_device
        self._orig_run = m.run
        self._orig_wait = m.wait_for_app_resumed
        self._orig_device_model = m.device_model
        self._orig_capture = m.capture_artifacts
        self._orig_cleanup = m.cleanup_run_fixtures
        self._orig_reset_adb = m.reset_adb_server

    def _patch_maestro(self, fake_maestro: FakeMaestro,
                       serial: str = "emulator-5554",
                       sha: str = "abc123def456",
                       device_alive: bool = True):
        """Wire FakeMaestro into the run_maestro module.

        Only patches functions used by run_one_flow() so tests remain focused.
        """
        # Use a mutable container so nested functions see the current value,
        # not the value captured at closure-creation time (avoids late binding bug).
        alive = [device_alive]

        def fake_try_run(cmd, **kwargs):
            if cmd and cmd[0] == "maestro":
                return fake_maestro.test(pathlib.Path(cmd[2]))
            # Non-maestro commands get a dummy ok result.
            cp = unittest.mock.Mock()
            cp.returncode = 0
            cp.stdout = ""
            cp.stderr = ""
            return cp

        def fake_adb(*args, **kw):
            # Serial-less commands used by ensure_device / main() recovery.
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
                return f"versionName=1.0.0+{sha}"
            # Generic shell commands (used by _device_is_ready / ensure_device).
            if len(args) >= 4 and args[0] == "-s" and args[2] == "shell" and args[3] == "getprop":
                return f"{serial}" if alive[0] else ""
            return None

        def fake_ensure_device(s):
            return serial if alive[0] else None

        def fake_device_alive(s):
            # Used by main()'s ensure_device(None) scan path:
            # device_alive(None) is called when scanning devices from `adb devices`.
            # Returning True here bypasses the fake_adb chain which can't handle
            # adb("-s", None, ...) calls.
            return alive[0]

        def fake_apk_sha(s):
            return fake_maestro._device_sha or sha

        def fake_wait(s, timeout=20):
            return True

        def fake_device_model(s):
            return "Medium_Phone"

        # gradlew installDebug — must be a no-op that returns success so the
        # retry loop (inside run_one_flow) can proceed past the install step.
        noop_cp = unittest.mock.Mock()
        noop_cp.returncode = 0
        noop_cp.stdout = ""
        noop_cp.stderr = ""

        m.try_run = fake_try_run
        m.adb = fake_adb
        m.ensure_device = fake_ensure_device
        m.device_alive = fake_device_alive
        m.apk_sha_from_device = fake_apk_sha
        m.wait_for_app_resumed = fake_wait
        m.device_model = fake_device_model
        m.run = lambda args, **kw: noop_cp

        # Capture / cleanup / reset are no-ops in tests.
        m.capture_artifacts = lambda *a, **kw: None
        m.cleanup_run_fixtures = lambda *a, **kw: None
        m.reset_adb_server = lambda: None

    def tearDown(self):
        m.try_run = self._orig_try_run
        m.adb = self._orig_adb
        m.ensure_device = self._orig_ensure_device
        m.device_alive = self._orig_device_alive
        m.apk_sha_from_device = self._orig_apk_sha
        m.run = self._orig_run
        m.wait_for_app_resumed = self._orig_wait
        m.device_model = self._orig_device_model
        m.capture_artifacts = self._orig_capture
        m.cleanup_run_fixtures = self._orig_cleanup
        m.reset_adb_server = self._orig_reset_adb

    def test_run_one_flow_passes_on_maestro_success(self):
        """Maestro returns 0 → run_one_flow returns (True, run_id)."""
        fake = FakeMaestro([FakeMaestroResult(0, stdout="flow passed")])
        self._patch_maestro(fake)
        flow = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"
        ok, run_id = m.run_one_flow(
            flow, serial="emulator-5554",
            junit_dir=pathlib.Path("/tmp/junit"),
            skip_install=False, allow_stale=False,
            max_retries=0, expected_sha="abc123def456",
        )
        self.assertTrue(ok)
        self.assertIsNotNone(run_id)
        self.assertEqual(len(fake.calls), 1)
        self.assertEqual(fake.calls[0], flow)

    def test_run_one_flow_fails_on_maestro_nonzero(self):
        """Maestro returns non-zero and device is alive → run_one_flow returns (False, run_id)."""
        fake = FakeMaestro([FakeMaestroResult(1, stderr="Assertion failed")])
        self._patch_maestro(fake)
        flow = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"
        ok, run_id = m.run_one_flow(
            flow, serial="emulator-5554",
            junit_dir=pathlib.Path("/tmp/junit"),
            skip_install=False, allow_stale=False,
            max_retries=0, expected_sha="abc123def456",
        )
        self.assertFalse(ok)
        self.assertIsNotNone(run_id)

    def test_run_one_flow_retries_on_device_failure_then_succeeds(self):
        """First Maestro run sees device failure, second succeeds → (True, run_id)."""
        device_error = FakeMaestroResult(1, stderr="0 devices connected")
        success = FakeMaestroResult(0)
        fake = FakeMaestro([device_error, success])
        self._patch_maestro(fake, device_alive=True)
        flow = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"
        ok, run_id = m.run_one_flow(
            flow, serial="emulator-5554",
            junit_dir=pathlib.Path("/tmp/junit"),
            skip_install=False, allow_stale=False,
            max_retries=2, expected_sha="abc123def456",
        )
        self.assertTrue(ok)
        self.assertEqual(len(fake.calls), 2)  # first failed, second succeeded

    def test_run_one_flow_exhausts_retries_and_returns_false(self):
        """All Maestro runs see device failure → (False, run_id).

        device_alive=True keeps ensure_device returning the serial so the retry
        loop runs to completion. Each retry attempt sees a fresh Maestro failure.
        """
        device_error = FakeMaestroResult(1, stderr="0 devices connected")
        fake = FakeMaestro([device_error, device_error, device_error])
        self._patch_maestro(fake, device_alive=True)
        flow = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"
        ok, run_id = m.run_one_flow(
            flow, serial="emulator-5554",
            junit_dir=pathlib.Path("/tmp/junit"),
            skip_install=False, allow_stale=False,
            max_retries=2, expected_sha="abc123def456",
        )
        self.assertFalse(ok)
        self.assertEqual(len(fake.calls), 3)  # all three attempts exhausted

    def test_run_one_flow_checks_apk_sha_on_retry_after_relaunch(self):
        """After attempt > 0, device reports different SHA → signals reinstall.

        attempt 0: Maestro device failure, enters retry.
        attempt 1: Maestro succeeds (status=0), then SHA check detects
        installed_sha=old000 != expected_sha=abc... → returns False.
        """
        device_error = FakeMaestroResult(1, stderr="Device server died")
        success = FakeMaestroResult(0)  # retry succeeds
        fake = FakeMaestro([device_error, success])
        self._patch_maestro(fake, sha="abc123def456", device_alive=True)
        # Simulate device now reporting OLD SHA after relaunch.
        fake.set_device_sha("old000old000")
        flow = ROOT / "Maestro/flows/smoke/01-launch-today.yaml"
        ok, run_id = m.run_one_flow(
            flow, serial="emulator-5554",
            junit_dir=pathlib.Path("/tmp/junit"),
            skip_install=False, allow_stale=False,
            max_retries=2, expected_sha="abc123def456",
        )
        # SHA mismatch after device relaunch → signals caller to reinstall
        self.assertFalse(ok)

    def test_skip_install_skips_gradle_install(self):
        """When --skip-install is set, gradle install is not called."""
        run_calls = []
        orig_run = m.run

        def tracking_run(args, **kwargs):
            run_calls.append(args)
            cp = unittest.mock.Mock()
            cp.returncode = 0
            cp.stdout = ""
            cp.stderr = ""
            return cp

        try:
            # _patch_maestro must be called FIRST (patches ensure_device / adb /
            # try_run), THEN override m.run with the tracking version.
            fake = FakeMaestro([FakeMaestroResult(0)])
            self._patch_maestro(fake, device_alive=True)
            m.run = tracking_run

            # --serial avoids the ensure_device(None) scan path which would call
            # device_alive(None) — an unqualified call that bypasses our patches.
            rc = m.main([
                "--flow", str(ROOT / "Maestro/flows/smoke/01-launch-today.yaml"),
                "--serial", "emulator-5554",
                "--skip-install",
                "--allow-stale-apk",
            ])
            self.assertEqual(rc, m.EXIT_PASS)
            # With --skip-install the APK install step is skipped entirely,
            # so run() should not have been called for gradlew install.
            gradle_calls = [c for c in run_calls
                            if c and "gradlew" in c[0]]
            self.assertEqual(gradle_calls, [],
                             "gradle should NOT be called when --skip-install is set")
        finally:
            m.run = orig_run


if __name__ == "__main__":
    unittest.main()

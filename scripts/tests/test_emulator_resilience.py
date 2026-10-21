#!/usr/bin/env python3
"""Unit tests for the emulator layer's resilience to a loaded host.

Run with: python3 -m unittest discover -s scripts/tests

The failure this covers is not exotic. On a host running several builds at
once, the adb round trip misses its deadline while the device is working
perfectly well. A runner that reads "no answer" as "the device is gone" then
restarts the adb server — and the restart hides the device from every other
client for the four seconds it takes to come back, so one busy host turns into
several failures at once.

So the device layer has to answer three questions apart: is the device gone, is
it slow, and is an emulator process running at all.
"""
from __future__ import annotations

import os
import pathlib
import tempfile
import unittest
import unittest.mock

import emulator._device as _device
from emulator._device import (
    ADB_TIMEOUT_RETRIES,
    CommandOutcome,
    DeviceHealth,
    adb_outcome,
    device_alive,
    emulator_process_alive,
    probe_device_health,
    try_run_detailed,
)


class RunDetailedTest(unittest.TestCase):
    def test_a_timeout_is_not_a_failure(self) -> None:
        with unittest.mock.patch(
            "emulator._device.subprocess.run",
            side_effect=_device.subprocess.TimeoutExpired(cmd=["adb"], timeout=5),
        ):
            outcome = try_run_detailed(["adb", "devices"], timeout=5)

        self.assertFalse(outcome.ok)
        self.assertTrue(outcome.timed_out)
        self.assertIsNone(outcome.code)

    def test_a_non_zero_exit_is_a_failure_and_not_a_timeout(self) -> None:
        with unittest.mock.patch(
            "emulator._device.subprocess.run",
            return_value=_device.subprocess.CompletedProcess(
                ["adb"], 1, "", "error: no devices found"
            ),
        ):
            outcome = try_run_detailed(["adb", "devices"], timeout=5)

        self.assertFalse(outcome.ok)
        self.assertFalse(outcome.timed_out)
        self.assertEqual(1, outcome.code)
        self.assertIn("no devices", outcome.stderr)

    def test_a_missing_binary_is_a_failure_and_not_a_timeout(self) -> None:
        with unittest.mock.patch(
            "emulator._device.subprocess.run", side_effect=OSError("no adb")
        ):
            outcome = try_run_detailed(["adb", "devices"], timeout=5)

        self.assertFalse(outcome.ok)
        self.assertFalse(outcome.timed_out)


class AdbRetryTest(unittest.TestCase):
    def test_a_timed_out_call_is_retried(self) -> None:
        calls = []

        def fake_run(args, **kwargs):
            calls.append(args)
            if len(calls) == 1:
                raise _device.subprocess.TimeoutExpired(cmd=args, timeout=5)
            return _device.subprocess.CompletedProcess(args, 0, "device\n", "")

        with unittest.mock.patch("emulator._device.subprocess.run", side_effect=fake_run):
            with unittest.mock.patch("emulator._device.time.sleep"):
                outcome = adb_outcome("devices", timeout=5)

        self.assertTrue(outcome.ok)
        self.assertEqual(2, len(calls), "one timeout, then the retry that worked")

    def test_a_failed_call_is_not_retried(self) -> None:
        calls = []

        def fake_run(args, **kwargs):
            calls.append(args)
            return _device.subprocess.CompletedProcess(args, 1, "", "no devices")

        with unittest.mock.patch("emulator._device.subprocess.run", side_effect=fake_run):
            outcome = adb_outcome("devices", timeout=5)

        self.assertFalse(outcome.ok)
        self.assertEqual(1, len(calls))

    def test_a_call_that_keeps_timing_out_gives_up(self) -> None:
        calls = []

        def fake_run(args, **kwargs):
            calls.append(args)
            raise _device.subprocess.TimeoutExpired(cmd=args, timeout=5)

        with unittest.mock.patch("emulator._device.subprocess.run", side_effect=fake_run):
            with unittest.mock.patch("emulator._device.time.sleep"):
                outcome = adb_outcome("devices", timeout=5)

        self.assertFalse(outcome.ok)
        self.assertTrue(outcome.timed_out)
        self.assertEqual(ADB_TIMEOUT_RETRIES + 1, len(calls))


class HealthTest(unittest.TestCase):
    def test_a_device_that_answers_is_alive(self) -> None:
        outcome = CommandOutcome(True, "ping", "", 0, False)
        with unittest.mock.patch(
            "emulator._device.adb_outcome", return_value=outcome
        ):
            health = probe_device_health("emulator-5554")

        self.assertEqual("alive", health.state)

    def test_a_device_that_does_not_answer_in_time_is_slow_and_not_gone(self) -> None:
        outcome = CommandOutcome(False, "", "", None, True)
        with unittest.mock.patch(
            "emulator._device.adb_outcome", return_value=outcome
        ):
            health = probe_device_health("emulator-5554")

        self.assertEqual("slow", health.state)
        self.assertIn("busy", health.detail)

    def test_a_device_adb_does_not_know_is_gone(self) -> None:
        outcome = CommandOutcome(False, "", "error: device 'x' not found", 1, False)
        with unittest.mock.patch(
            "emulator._device.adb_outcome", return_value=outcome
        ):
            health = probe_device_health("emulator-5554")

        self.assertEqual("gone", health.state)
        self.assertIn("not found", health.detail)

    def test_no_serial_is_gone(self) -> None:
        self.assertEqual("gone", probe_device_health("").state)


class DeviceAliveTest(unittest.TestCase):
    def test_a_state_check_alone_is_not_enough(self) -> None:
        """adb keeps saying `device` after the render thread has died.

        The skill and ADR 2026-09-29 record that `get-state` stays `device` for
        a while after the crash, so a liveness check that only asks adb's state
        sees a healthy device that cannot serve one command.
        """
        outcome = CommandOutcome(True, "device", "", 0, False)
        with unittest.mock.patch(
            "emulator._device.adb_outcome", return_value=outcome
        ) as call:
            self.assertTrue(device_alive("emulator-5554"))

        self.assertIn("shell", call.call_args.args)
        self.assertIn("echo", call.call_args.args)

    def test_an_empty_serial_is_not_alive(self) -> None:
        self.assertFalse(device_alive(""))


class EmulatorProcessTest(unittest.TestCase):
    def _fake_proc(self, root: pathlib.Path, exe: str) -> None:
        pid_dir = root / "4242"
        pid_dir.mkdir()
        os.symlink(exe, pid_dir / "exe")

    def test_a_running_emulator_is_found(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self._fake_proc(root, "/opt/sdk/emulator/qemu-system-x86_64")
            self.assertTrue(emulator_process_alive(root))

    def test_a_host_without_an_emulator_is_not_found(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self._fake_proc(root, "/usr/bin/some-other-program")
            self.assertFalse(emulator_process_alive(root))

    def test_a_process_without_an_exe_link_is_skipped(self) -> None:
        """A process that exited between the listing and the read has no exe."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / "4242").mkdir()
            self.assertFalse(emulator_process_alive(root))

    def test_a_missing_proc_root_is_not_a_crash(self) -> None:
        self.assertFalse(emulator_process_alive(pathlib.Path("/nonexistent-proc")))


if __name__ == "__main__":
    unittest.main()

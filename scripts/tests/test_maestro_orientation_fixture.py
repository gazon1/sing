#!/usr/bin/env python3
"""Unit tests for the orientation fixture: what a flow leaves on the device.

Run with: python3 -m unittest discover -s scripts/tests

A flow that rotates the device and then fails leaves it rotated. Maestro reads
the screen size once and keeps it, so the next flow is measured against a
landscape layout with a portrait width — every visibility check on it comes out
false, and one defect is reported as forty. The orientation is put back before
the next flow runs, and these tests pin that it is.
"""
from __future__ import annotations

import pathlib
import unittest
import unittest.mock

import emulator.maestro._fixtures as _fixtures

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


def _recording_deps(calls: list[str], alive: bool = True) -> "object":
    """A `DeviceDeps` whose adb calls are recorded."""
    from emulator._startup import Acquisition
    from emulator._types import EXIT_OK
    from emulator.maestro._types import DeviceDeps

    return DeviceDeps(
        try_run=lambda cmd, **kw: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
        device_alive=lambda s: alive,
        device_model=lambda s: "Pixel_8",
        maestro_saw_device_failure=lambda stderr: False,
        apk_sha_from_device=lambda s: "abc123def456",
        gradle_install_debug=lambda s: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
        acquire_device=lambda pin: Acquisition("emulator-5554", EXIT_OK),
        adb=lambda *a, **kw: "",
        adb_on=lambda serial, *a, **kw: calls.append(" ".join(a)),
        reset_adb_server=lambda: None,
        wait_for_app_resumed=lambda s, timeout=20: True,
        wait_for_main_activity_resumed=lambda s, timeout=30: True,
        cleanup_run_fixtures=lambda serial, mode, deps: None,
        capture_artifacts=lambda serial, name, artifact_dir, deps: None,
        git_sha=lambda deps: "abc123def456",
    )


class TheOrientationFixtureTest(unittest.TestCase):
    """The device is put back in portrait before the next flow."""

    def test_the_rotation_is_undone(self) -> None:
        calls: list[str] = []
        _fixtures.reset_orientation("emulator-5554", _recording_deps(calls))
        self.assertIn("shell settings put system user_rotation 0", calls)

    def test_the_auto_rotation_is_off(self) -> None:
        # With accelerometer rotation on, the emulator turns the screen back on
        # its own and the fixture would not hold.
        calls: list[str] = []
        _fixtures.reset_orientation("emulator-5554", _recording_deps(calls))
        self.assertIn("shell settings put system accelerometer_rotation 0", calls)

    def test_a_dead_device_is_not_touched(self) -> None:
        calls: list[str] = []
        _fixtures.reset_orientation("emulator-5554", _recording_deps(calls, alive=False))
        self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()

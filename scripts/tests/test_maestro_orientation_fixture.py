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


def _recording_deps(
    calls: list[str],
    alive: bool = True,
    settings: dict[str, str] | None = None,
) -> "object":
    """A `DeviceDeps` whose adb calls are recorded.

    `settings` answers `settings get system <key>`; without it the setting is
    unreadable, which is what a device that refuses the query looks like.
    """
    from emulator._startup import Acquisition
    from emulator._types import EXIT_OK
    from emulator.maestro._types import DeviceDeps

    answers = settings if settings is not None else {}

    def adb_on(serial: str, *a: str, **kw: object) -> str | None:
        calls.append(" ".join(a))
        if len(a) >= 5 and a[1] == "settings" and a[2] == "get":
            return answers.get(a[4])
        return None

    return DeviceDeps(
        try_run=lambda cmd, **kw: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
        device_alive=lambda s: alive,
        device_model=lambda s: "Pixel_8",
        maestro_saw_device_failure=lambda stderr: False,
        apk_sha_from_device=lambda s: "abc123def456",
        gradle_install_debug=lambda s: unittest.mock.Mock(returncode=0, stdout="", stderr=""),
        acquire_device=lambda pin: Acquisition("emulator-5554", EXIT_OK),
        adb=lambda *a, **kw: "",
        adb_on=adb_on,
        reset_adb_server=lambda: None,
        wait_for_app_resumed=lambda s, timeout=20: True,
        wait_for_main_activity_resumed=lambda s, timeout=30: True,
        cleanup_run_fixtures=lambda serial, mode, deps: None,
        capture_artifacts=lambda serial, name, artifact_dir, deps: None,
        git_sha=lambda deps: "abc123def456",
    )


class TheOrientationFixtureTest(unittest.TestCase):
    """The device is put back in portrait before the next flow."""

    def setUp(self) -> None:
        # The fixture remembers what it took per device; the tests do not
        # share a device, so the memory is cleared between them.
        _fixtures._TAKEN_ROTATION.clear()

    def tearDown(self) -> None:
        _fixtures._TAKEN_ROTATION.clear()

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


class TheOrientationIsGivenBackTest(unittest.TestCase):
    """A fixture gives back what it took from the device."""

    def setUp(self) -> None:
        _fixtures._TAKEN_ROTATION.clear()

    def tearDown(self) -> None:
        _fixtures._TAKEN_ROTATION.clear()

    def test_the_device_settings_are_put_back(self) -> None:
        calls: list[str] = []
        deps = _recording_deps(
            calls,
            settings={"accelerometer_rotation": "1", "user_rotation": "2"},
        )
        _fixtures.reset_orientation("emulator-5554", deps)
        _fixtures.restore_orientation("emulator-5554", deps)
        self.assertIn("shell settings put system accelerometer_rotation 1", calls)
        self.assertIn("shell settings put system user_rotation 2", calls)

    def test_the_orientation_is_put_back_before_the_policy(self) -> None:
        # The orientation is where the device is; the policy is what decides
        # where it is allowed to go, so it is put back last.
        calls: list[str] = []
        deps = _recording_deps(
            calls,
            settings={"accelerometer_rotation": "1", "user_rotation": "2"},
        )
        _fixtures.reset_orientation("emulator-5554", deps)
        calls.clear()
        _fixtures.restore_orientation("emulator-5554", deps)
        puts = [c for c in calls if c.startswith("shell settings put")]
        self.assertEqual(
            [
                "shell settings put system user_rotation 2",
                "shell settings put system accelerometer_rotation 1",
            ],
            puts,
        )

    def test_the_settings_are_taken_only_once(self) -> None:
        # After the first flow the settings on the device are the fixture's
        # own; reading them again would remember those as the device's.
        calls: list[str] = []
        deps = _recording_deps(
            calls,
            settings={"accelerometer_rotation": "1", "user_rotation": "2"},
        )
        _fixtures.reset_orientation("emulator-5554", deps)
        _fixtures.reset_orientation("emulator-5554", deps)
        reads = [c for c in calls if "settings get" in c]
        self.assertEqual(2, len(reads))

    def test_an_unreadable_setting_still_gives_the_readable_one_back(self) -> None:
        # A device that answers for one setting and not for the other is still
        # a device whose readable setting has to be given back.
        calls: list[str] = []
        deps = _recording_deps(calls, settings={"user_rotation": "2"})
        _fixtures.reset_orientation("emulator-5554", deps)
        calls.clear()
        _fixtures.restore_orientation("emulator-5554", deps)
        self.assertIn("shell settings put system user_rotation 2", calls)
        self.assertNotIn("shell settings put system accelerometer_rotation 2", calls)

    def test_nothing_readable_leaves_nothing_to_restore(self) -> None:
        calls: list[str] = []
        deps = _recording_deps(calls)
        _fixtures.reset_orientation("emulator-5554", deps)
        calls.clear()
        _fixtures.restore_orientation("emulator-5554", deps)
        self.assertEqual([], calls)

    def test_a_dead_device_is_given_nothing(self) -> None:
        calls: list[str] = []
        deps = _recording_deps(
            calls,
            alive=False,
            settings={"accelerometer_rotation": "1", "user_rotation": "2"},
        )
        _fixtures.reset_orientation("emulator-5554", deps)
        calls.clear()
        _fixtures.restore_orientation("emulator-5554", deps)
        self.assertEqual([], calls)

    def test_the_giving_back_happens_once(self) -> None:
        calls: list[str] = []
        deps = _recording_deps(
            calls,
            settings={"accelerometer_rotation": "1", "user_rotation": "2"},
        )
        _fixtures.reset_orientation("emulator-5554", deps)
        _fixtures.restore_orientation("emulator-5554", deps)
        calls.clear()
        _fixtures.restore_orientation("emulator-5554", deps)
        self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""The runner must read the resumed activity the way the device prints it.

Run with: python3 -m unittest discover -s scripts/tests

Two waiters guard the fixtures: ``wait_for_app_resumed`` after the session
reset, and ``wait_for_main_activity_resumed`` after the content reset. The
second one exists because "the app is in front" is no proof that the reset is
*done* — the debug seed activity is in front while the reset thread is still
running, and the runner's next step (a force-stop) can kill it half way
through, leaving the tables the reset empties last populated.

Both waiters were dead on a modern phone. They looked for
``topResumedActivity=``, and an API 35 device prints:

    ResumedActivity: ActivityRecord{5a1b95b u0 com.singularity.todo/.MainActivity t3273 d0}

There is no ``topResumedActivity`` in it at all, so the wait always ran out
its timeout and answered "not resumed" — which turned the wait into exactly
the fixed sleep the ADR says it replaced, with a worse name. The caller threw
the answer away, so nothing said so.

These tests pin the reading of the line: the shape the device actually
prints, the older shape, the shape of a *different* activity (the debug seed
activity must not pass for the main one), and the empty answer.
"""
from __future__ import annotations

import unittest
import unittest.mock

from emulator.maestro import _device

#: What an API 35 phone prints for the resumed activity. Measured on a
#: realme RMX3938 (0O65321F32204DD5), API 35 — `dumpsys activity activities`
#: has no ``topResumedActivity`` line on this device.
API_35_DUMP = """\
  Resumed activities in task display areas (from top to bottom):
    Resumed: ActivityRecord{5a1b95b u0 com.singularity.todo/.MainActivity t3273 d0}

  ResumedActivity: ActivityRecord{5a1b95b u0 com.singularity.todo/.MainActivity t3273 d0}
"""

#: The shape older Android versions print.
LEGACY_DUMP = """\
  topResumedActivity=ActivityRecord{8f3 u0 com.singularity.todo/.MainActivity t42}
"""

#: The debug seed activity — the app's package, a different activity. The
#: runner's reset runs *on* it, so a wait that accepted it would answer
#: "resumed" while the reset was still going.
SEED_ACTIVITY_DUMP = """\
  ResumedActivity: ActivityRecord{1f0a11 u0 com.singularity.todo/.debug.DebugSeedActivity t3274 d0}
"""

#: Some other app in front.
OTHER_APP_DUMP = """\
  ResumedActivity: ActivityRecord{99 u0 com.android.settings/.Settings t1 d0}
"""


class TheResumedLineTest(unittest.TestCase):
    """The line is read out of the dump, not guessed at."""

    def test_the_api_35_shape_is_found(self) -> None:
        line = _device.resumed_activity_line(API_35_DUMP)
        self.assertIn("com.singularity.todo/.MainActivity", line)

    def test_the_legacy_shape_is_found(self) -> None:
        line = _device.resumed_activity_line(LEGACY_DUMP)
        self.assertIn("com.singularity.todo/.MainActivity", line)

    def test_a_dump_without_a_resumed_activity_yields_nothing(self) -> None:
        self.assertEqual(_device.resumed_activity_line(""), "")
        self.assertEqual(_device.resumed_activity_line(None), "")
        self.assertEqual(_device.resumed_activity_line("nothing to see here"), "")

    def test_the_line_of_the_task_list_is_not_the_answer(self) -> None:
        """The task list holds several lines; the answer is the top one.

        ``Resumed activities in task display areas`` is a *header*, and the
        ``Resumed:`` line under it is one task's entry. The global answer is
        the ``ResumedActivity:`` line. Taking the first match of either shape
        would read the task list's entry, which is not the top of the stack.
        """
        line = _device.resumed_activity_line(API_35_DUMP)
        self.assertTrue(line.startswith("ResumedActivity:"), line)


class TheWaitersTest(unittest.TestCase):
    """The waiters answer against what the device prints."""

    def _with_dump(self, dump: str):
        return unittest.mock.patch.object(
            _device, "adb_on", return_value=dump,
        )

    def test_the_app_is_seen_as_resumed_on_an_api_35_phone(self) -> None:
        with self._with_dump(API_35_DUMP), unittest.mock.patch.object(
            _device.time, "sleep",
        ):
            self.assertTrue(_device.wait_for_app_resumed("serial", timeout=2))

    def test_the_app_is_seen_as_resumed_on_an_older_phone(self) -> None:
        with self._with_dump(LEGACY_DUMP), unittest.mock.patch.object(
            _device.time, "sleep",
        ):
            self.assertTrue(_device.wait_for_app_resumed("serial", timeout=2))

    def test_another_app_in_front_is_not_the_app(self) -> None:
        with self._with_dump(OTHER_APP_DUMP), unittest.mock.patch.object(
            _device.time, "sleep",
        ):
            self.assertFalse(_device.wait_for_app_resumed("serial", timeout=1))

    def test_the_main_activity_is_seen_as_resumed_on_an_api_35_phone(self) -> None:
        with self._with_dump(API_35_DUMP), unittest.mock.patch.object(
            _device.time, "sleep",
        ):
            self.assertTrue(
                _device.wait_for_main_activity_resumed("serial", timeout=2),
            )

    def test_the_main_activity_is_seen_as_resumed_on_an_older_phone(self) -> None:
        with self._with_dump(LEGACY_DUMP), unittest.mock.patch.object(
            _device.time, "sleep",
        ):
            self.assertTrue(
                _device.wait_for_main_activity_resumed("serial", timeout=2),
            )

    def test_the_seed_activity_is_not_the_main_activity(self) -> None:
        """The seed activity runs *on* the reset — accepting it proves nothing.

        The seed activity lives in the app's own package, so a matcher that
        looked for the package alone would answer "the main activity is
        resumed" while the reset thread was still running.
        """
        with self._with_dump(SEED_ACTIVITY_DUMP), unittest.mock.patch.object(
            _device.time, "sleep",
        ):
            self.assertFalse(
                _device.wait_for_main_activity_resumed("serial", timeout=1),
            )

    def test_the_seed_activity_is_still_the_app(self) -> None:
        """The app is in front either way — it is the same package."""
        with self._with_dump(SEED_ACTIVITY_DUMP), unittest.mock.patch.object(
            _device.time, "sleep",
        ):
            self.assertTrue(_device.wait_for_app_resumed("serial", timeout=1))


class TheWaitIsNotASleepTest(unittest.TestCase):
    """A wait that answers "no" must have actually looked."""

    def test_the_app_wait_gives_up_only_after_its_timeout(self) -> None:
        with unittest.mock.patch.object(_device, "adb_on", return_value=""), \
                unittest.mock.patch.object(_device.time, "sleep") as slept:
            self.assertFalse(_device.wait_for_app_resumed("serial", timeout=3))
        self.assertEqual(slept.call_count, 3)

    def test_the_main_activity_wait_gives_up_only_after_its_timeout(self) -> None:
        with unittest.mock.patch.object(_device, "adb_on", return_value=""), \
                unittest.mock.patch.object(_device.time, "sleep") as slept:
            self.assertFalse(
                _device.wait_for_main_activity_resumed("serial", timeout=2),
            )
        self.assertEqual(slept.call_count, 2)

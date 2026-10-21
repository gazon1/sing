#!/usr/bin/env python3
"""One Maestro run per device.

Run with: python3 -m unittest discover -s scripts/tests

Two runs on one emulator do not queue behind each other: the device servers
kill each other, and every flow fails within a second with a signature that
reads like a broken suite. The lock refuses the second run instead.
"""
from __future__ import annotations

import os
import pathlib
import tempfile
import unittest

from emulator.maestro._lock import DeviceLock, DeviceLocked


class DeviceLockTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def test_the_lock_is_taken_and_given_back(self):
        lock = DeviceLock("emulator-5554", lock_dir=self.dir)
        lock.acquire()
        self.assertTrue(lock.path.is_file())
        self.assertEqual(str(os.getpid()), lock.path.read_text().strip())
        lock.release()
        self.assertFalse(lock.path.exists())

    def test_a_second_run_on_the_same_device_is_refused(self):
        first = DeviceLock("emulator-5554", lock_dir=self.dir)
        first.acquire()
        self.addCleanup(first.release)
        second = DeviceLock("emulator-5554", lock_dir=self.dir)
        with self.assertRaises(DeviceLocked) as caught:
            second.acquire()
        self.assertEqual(caught.exception.owner, os.getpid())

    def test_another_device_has_its_own_lock(self):
        first = DeviceLock("emulator-5554", lock_dir=self.dir)
        first.acquire()
        self.addCleanup(first.release)
        other = DeviceLock("emulator-5556", lock_dir=self.dir)
        other.acquire()
        self.addCleanup(other.release)

    def test_a_lock_left_by_a_dead_run_is_taken_over(self):
        """A killed run must not lock the device out of use."""
        lock = DeviceLock("emulator-5554", lock_dir=self.dir)
        lock.path.write_text("999999999\n", encoding="utf-8")
        lock.acquire()
        self.assertEqual(str(os.getpid()), lock.path.read_text().strip())
        lock.release()

    def test_an_unreadable_lock_is_taken_over(self):
        lock = DeviceLock("emulator-5554", lock_dir=self.dir)
        lock.path.write_text("not a pid\n", encoding="utf-8")
        lock.acquire()
        lock.release()

    def test_releasing_a_lock_somebody_else_takes_over_leaves_it_alone(self):
        lock = DeviceLock("emulator-5554", lock_dir=self.dir)
        lock.acquire()
        # Somebody else took the device over while we were not looking.
        lock.path.write_text("424242\n", encoding="utf-8")
        lock.release()
        self.assertTrue(lock.path.is_file(), "we deleted a lock we no longer own")

    def test_releasing_without_holding_does_nothing(self):
        lock = DeviceLock("emulator-5554", lock_dir=self.dir)
        lock.release()
        self.assertFalse(lock.path.exists())


class LockDirectoryTest(unittest.TestCase):
    """The directory is read when the lock is taken, not when the module loads."""

    def test_the_environment_moves_the_lock(self):
        with tempfile.TemporaryDirectory() as tmp:
            lock = DeviceLock("emulator-5554")
            previous = os.environ.get("MAESTRO_LOCK_DIR")
            os.environ["MAESTRO_LOCK_DIR"] = tmp
            try:
                lock.acquire()
                self.assertEqual(lock.dir, pathlib.Path(tmp))
                self.assertTrue(lock.path.is_file())
                lock.release()
            finally:
                if previous is None:
                    os.environ.pop("MAESTRO_LOCK_DIR", None)
                else:
                    os.environ["MAESTRO_LOCK_DIR"] = previous


if __name__ == "__main__":
    unittest.main()

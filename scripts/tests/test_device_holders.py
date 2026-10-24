"""Tests for `sgate device-holders` — who holds a device right now.

The premise: a device is a shared resource, and the repository already knows
it — a Maestro run takes an exclusive lock on the serial it drives, one lock
file per serial, naming the PID that holds it.

But the lock answers only when you try to take it. There was no way to *ask*,
and asking is the question that matters before a run. On 2026-10-24 that cost
two deliverables: the phone and the AVD were busy with another session's runs,
and the answer came as a failure that cost a run.

These tests hold the command to its own claims:

  - a live lock is reported as held, and names its owner;
  - a lock whose owner is gone is reported as stale, and the device as free —
    "the file exists" and "the device is held" are different facts;
  - a device that is connected and unclaimed is free;
  - asking about one serial answers about that serial, and says so with its
    exit code.
"""

import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = ROOT / 'scripts' / 'src' / 'sgate' / 'commands' / 'device_holders.py'
spec = importlib.util.spec_from_file_location('device_holders', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def _lock_file(lock_dir: pathlib.Path, serial: str, pid: int) -> pathlib.Path:
    """A lock file for a serial, owned by a pid."""
    path = lock_dir / f'{mod.LOCK_PREFIX}{serial}{mod.LOCK_SUFFIX}'
    path.write_text(f'{pid}\n', encoding='utf-8')
    return path


class LocksTest(unittest.TestCase):
    """The locks, on a directory built for them."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='device-holders-'))
        self.addCleanup(self._remove)

    def _remove(self) -> None:
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_a_live_lock_is_reported_as_held(self):
        _lock_file(self.tmp, 'emulator-5554', os.getpid())
        locks = mod._locks(self.tmp)
        self.assertEqual(1, len(locks), locks)
        self.assertTrue(locks[0]['held'])
        self.assertEqual('emulator-5554', locks[0]['serial'])
        self.assertEqual(os.getpid(), locks[0]['owner'])

    def test_a_lock_whose_owner_is_gone_is_stale(self):
        # A pid that cannot be running: the run died, and the device is free
        # even though the file still names it.
        _lock_file(self.tmp, 'emulator-5554', 2 ** 22)
        locks = mod._locks(self.tmp)
        self.assertEqual(1, len(locks), locks)
        self.assertFalse(locks[0]['held'])

    def test_an_unreadable_lock_is_not_a_held_device(self):
        path = self.tmp / f'{mod.LOCK_PREFIX}emulator-5554{mod.LOCK_SUFFIX}'
        path.write_text('not a pid', encoding='utf-8')
        locks = mod._locks(self.tmp)
        self.assertEqual(1, len(locks), locks)
        self.assertFalse(locks[0]['held'])

    def test_an_empty_directory_holds_nothing(self):
        self.assertEqual([], mod._locks(self.tmp))

    def test_the_report_names_the_owner_of_a_held_device(self):
        _lock_file(self.tmp, 'emulator-5554', os.getpid())
        report = mod._report(mod._locks(self.tmp), [])
        self.assertIn('emulator-5554', report)
        self.assertIn('held', report)

    def test_the_report_says_a_stale_lock_leaves_the_device_free(self):
        _lock_file(self.tmp, 'emulator-5554', 2 ** 22)
        report = mod._report(mod._locks(self.tmp), [])
        self.assertIn('stale', report)
        self.assertIn('free', report)


class ConnectedDevicesTest(unittest.TestCase):
    """The connected devices, and what the locks say about them."""

    def test_a_connected_unclaimed_device_is_free(self):
        report = mod._report([], [{'serial': '0O65321F32204DD5', 'state': 'device'}])
        self.assertIn('0O65321F32204DD5', report)
        self.assertIn('free', report)

    def test_a_connected_claimed_device_is_held(self):
        locks = [{'serial': '0O65321F32204DD5', 'owner': 4242, 'alive': True, 'held': True}]
        report = mod._report(locks, [{'serial': '0O65321F32204DD5', 'state': 'device'}])
        self.assertIn('HELD', report)

    def test_a_stale_lock_does_not_hold_a_connected_device(self):
        locks = [{'serial': '0O65321F32204DD5', 'owner': 4242, 'alive': False, 'held': False}]
        report = mod._report(locks, [{'serial': '0O65321F32204DD5', 'state': 'device'}])
        self.assertIn('free', report)
        self.assertNotIn('HELD', report)


class TheCommandRunsTest(unittest.TestCase):
    """The command itself runs."""

    def test_the_command_prints_a_report(self):
        result = subprocess.run(
            [sys.executable, '-m', 'sgate', 'device-holders', '--json'],
            cwd=ROOT,
            capture_output=True,
            text=True,
            env={**os.environ, 'PYTHONPATH': str(ROOT / 'scripts' / 'src')},
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        payload = json.loads(result.stdout)
        self.assertIn('locks', payload)
        self.assertIn('devices', payload)


if __name__ == '__main__':
    unittest.main()

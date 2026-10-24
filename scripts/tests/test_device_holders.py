"""Tests for `sgate device-holders` — who holds a device right now.

The premise: a device is a shared resource, and the repository already knows
it — a Maestro run takes an exclusive lock on the serial it drives, one lock
file per serial, naming the PID that holds it.

But the lock answers only when you try to take it. There was no way to *ask*,
and asking is the question that matters before a run. On 2026-10-24 that cost
two deliverables: the phone and the AVD were busy with another session's runs,
and the answer came as a failure that cost a run.

These tests hold the command to its own claims:

  - it finds the lock the emulator's own API writes — the file names come
    from the emulator package, not from a copy in the command, so the two
    cannot drift apart (a drifted copy reports every device as free while a
    device is held, and no test can see the difference);
  - a live lock is reported as held and names its owner; a lock whose owner
    is gone is stale, and the device is free — "the file exists" and "the
    device is held" are different facts;
  - asking about one serial answers about that serial, and says so with its
    exit code;
  - the command takes nothing: it does not create, change or remove a lock.
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

# This checkout's package root, before the modules under test are loaded: the
# command imports the emulator package, and without this line the import
# resolves to whatever is installed on the machine.
sys.path.insert(0, str(ROOT / 'scripts' / 'src'))

from emulator.maestro._lock import LOCK_PREFIX, LOCK_SUFFIX, DeviceLock  # noqa: E402

MODULE_PATH = ROOT / 'scripts' / 'src' / 'sgate' / 'commands' / 'device_holders.py'
spec = importlib.util.spec_from_file_location('device_holders', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def _dead_pid() -> int:
    """A pid that is certainly not running: a child that has been reaped."""
    child = subprocess.Popen([sys.executable, '-c', 'pass'])
    child.wait()
    return child.pid


class LockNamesTest(unittest.TestCase):
    """The file names come from the emulator package, not from a copy."""

    def test_the_command_reads_the_names_from_the_emulator_package(self):
        self.assertEqual((LOCK_PREFIX, LOCK_SUFFIX), mod._lock_names())

    def test_the_command_has_no_copy_of_the_names(self):
        # A second copy of the name is a second answer to "which file holds
        # this device?". The names are read from the package, not written
        # out here.
        source = MODULE_PATH.read_text(encoding='utf-8')
        self.assertNotIn('singularity-maestro-', source)


class LocksTest(unittest.TestCase):
    """The locks, on a directory built for them."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='device-holders-'))
        self.addCleanup(self._remove)

    def _remove(self) -> None:
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _lock(self, serial: str, pid: int) -> pathlib.Path:
        """A lock file for a serial, written the way the emulator writes it."""
        path = DeviceLock(serial, lock_dir=self.tmp).path
        path.write_text(f'{pid}\n', encoding='utf-8')
        return path

    def test_a_lock_the_emulator_wrote_is_found(self):
        self._lock('emulator-5554', os.getpid())
        locks = mod._locks(self.tmp)
        self.assertEqual(1, len(locks), locks)
        self.assertEqual('emulator-5554', locks[0]['serial'])

    def test_a_live_lock_is_reported_as_held(self):
        self._lock('emulator-5554', os.getpid())
        locks = mod._locks(self.tmp)
        self.assertTrue(locks[0]['held'])
        self.assertEqual(os.getpid(), locks[0]['owner'])

    def test_a_lock_whose_owner_is_gone_is_stale(self):
        # A run that died: the device is free even though the file names it.
        self._lock('emulator-5554', _dead_pid())
        locks = mod._locks(self.tmp)
        self.assertFalse(locks[0]['held'])

    def test_an_unreadable_lock_is_not_a_held_device(self):
        path = DeviceLock('emulator-5554', lock_dir=self.tmp).path
        path.write_text('not a pid', encoding='utf-8')
        locks = mod._locks(self.tmp)
        self.assertFalse(locks[0]['held'])
        self.assertIsNone(locks[0]['owner'])
        self.assertIn('unreadable', mod._report(locks, []))

    def test_an_empty_directory_holds_nothing(self):
        self.assertEqual([], mod._locks(self.tmp))

    def test_the_report_names_the_owner_of_a_held_device(self):
        self._lock('emulator-5554', os.getpid())
        report = mod._report(mod._locks(self.tmp), [])
        self.assertIn('emulator-5554', report)
        self.assertIn('held', report)

    def test_the_report_says_a_stale_lock_leaves_the_device_free(self):
        self._lock('emulator-5554', _dead_pid())
        report = mod._report(mod._locks(self.tmp), [])
        self.assertIn('stale', report)
        self.assertIn('free', report)

    def test_the_command_takes_nothing(self):
        # The command reads. It creates no lock, changes no lock and removes
        # no lock — the guarantee the report's own claim rests on.
        path = self._lock('emulator-5554', os.getpid())
        before = {p.name: (p.read_bytes(), p.stat().st_mtime_ns) for p in self.tmp.iterdir()}
        mod._locks(self.tmp)
        mod._report(mod._locks(self.tmp), [])
        after = {p.name: (p.read_bytes(), p.stat().st_mtime_ns) for p in self.tmp.iterdir()}
        self.assertEqual(before, after)
        self.assertTrue(path.exists())


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
    """The command itself runs, and answers about the device it is asked about."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='device-holders-run-'))
        self.addCleanup(self._remove)

    def _remove(self) -> None:
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _run(self, *args: str):
        return subprocess.run(
            [sys.executable, '-m', 'sgate', 'device-holders', *args],
            cwd=ROOT,
            capture_output=True,
            text=True,
            env={
                **os.environ,
                'PYTHONPATH': str(ROOT / 'scripts' / 'src'),
                'MAESTRO_LOCK_DIR': str(self.tmp),
            },
            check=False,
        )

    def test_the_command_prints_a_report(self):
        result = self._run('--json')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        payload = json.loads(result.stdout)
        self.assertIn('locks', payload)
        self.assertIn('devices', payload)

    def test_a_held_device_is_answered_with_a_non_zero_exit_code(self):
        path = DeviceLock('emulator-5554', lock_dir=self.tmp).path
        path.write_text(f'{os.getpid()}\n', encoding='utf-8')
        result = self._run('--serial', 'emulator-5554')
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn('emulator-5554', result.stdout)

    def test_a_free_device_is_answered_with_a_zero_exit_code(self):
        result = self._run('--serial', 'emulator-5554')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_a_stale_lock_answers_zero(self):
        path = DeviceLock('emulator-5554', lock_dir=self.tmp).path
        path.write_text(f'{_dead_pid()}\n', encoding='utf-8')
        result = self._run('--serial', 'emulator-5554')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_the_command_leaves_the_lock_directory_alone(self):
        path = DeviceLock('emulator-5554', lock_dir=self.tmp).path
        path.write_text(f'{os.getpid()}\n', encoding='utf-8')
        before = {p.name: p.read_bytes() for p in self.tmp.iterdir()}
        self._run('--serial', 'emulator-5554')
        after = {p.name: p.read_bytes() for p in self.tmp.iterdir()}
        self.assertEqual(before, after)

    def test_the_command_leaves_a_stale_lock_alone(self):
        # A stale lock is the one a command that "cleans up" would remove,
        # and removing it is taking the device's claim away from whoever
        # wrote it. The command takes nothing — stale or not.
        path = DeviceLock('emulator-5554', lock_dir=self.tmp).path
        path.write_text(f'{_dead_pid()}\n', encoding='utf-8')
        before = {p.name: p.read_bytes() for p in self.tmp.iterdir()}
        self._run('--serial', 'emulator-5554')
        after = {p.name: p.read_bytes() for p in self.tmp.iterdir()}
        self.assertEqual(before, after)

    def test_an_unreadable_lock_directory_is_an_error_not_an_empty_report(self):
        # "No device is claimed" and "I could not read the directory" are
        # different facts, and only one of them is a device being free.
        unreadable = self.tmp / 'unreadable'
        unreadable.mkdir()
        unreadable.chmod(0o000)
        self.addCleanup(unreadable.chmod, 0o755)
        result = subprocess.run(
            [sys.executable, '-m', 'sgate', 'device-holders'],
            cwd=ROOT,
            capture_output=True,
            text=True,
            env={
                **os.environ,
                'PYTHONPATH': str(ROOT / 'scripts' / 'src'),
                'MAESTRO_LOCK_DIR': str(unreadable),
            },
            check=False,
        )
        self.assertEqual(3, result.returncode, result.stdout + result.stderr)
        self.assertNotIn('no device is claimed', result.stdout)

    def test_adb_that_cannot_be_asked_is_not_an_empty_device_list(self):
        # `adb` missing from PATH is "adb did not answer", not "no device is
        # attached" — and the report says which one it is.
        result = subprocess.run(
            [sys.executable, '-m', 'sgate', 'device-holders'],
            cwd=ROOT,
            capture_output=True,
            text=True,
            env={
                **os.environ,
                'PATH': '/nonexistent',
                'PYTHONPATH': str(ROOT / 'scripts' / 'src'),
                'MAESTRO_LOCK_DIR': str(self.tmp),
            },
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('adb did not answer', result.stdout)

    def test_an_unknown_serial_says_so_in_json_too(self):
        result = self._run('--serial', 'nope-1234', '--json')
        payload = json.loads(result.stdout)
        self.assertEqual('nope-1234', payload['unknown'])


if __name__ == '__main__':
    unittest.main()

"""Tests for maestro-flows.py — the opt-in Maestro UI flows.

The flows are the slowest thing in the repository and they need a device, so
they run only when they are asked for. The cases below are the ways that
"asked for" could go wrong:

  - without `RUN_MAESTRO=1` nothing runs at all — not a slow run, and not a
    failed one;
  - without the `maestro` CLI on PATH the step says so in a sentence, instead
    of failing with a traceback from a missing binary;
  - when the flows do run and fail, the step fails: a green device lane over a
    red suite is the one outcome nobody wants.

And the pin the flows are given: it names the device of this lane, and it is
dropped as soon as that device is no longer attached — a pin on a device that
is gone is a run that cannot happen.
"""

import importlib.util
import os
import pathlib
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'maestro-flows.py'
spec = importlib.util.spec_from_file_location('maestro_flows', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class MaestroTestCase(unittest.TestCase):
    """The serial file points at a temporary directory, not the checkout.

    The file says which device the instrumentation step used, and a test that
    read the real one would pin the flows to whatever device this machine
    happens to have attached.
    """

    def setUp(self):
        self._temporary = tempfile.TemporaryDirectory()
        self._original_serial_file = mod.SERIAL_FILE
        self._original_adb = mod.adb_devices
        mod.SERIAL_FILE = pathlib.Path(self._temporary.name) / 'device-serial.txt'
        mod.adb_devices = lambda: 'List of devices attached\nemulator-5554\tdevice\n'

    def tearDown(self):
        mod.SERIAL_FILE = self._original_serial_file
        mod.adb_devices = self._original_adb
        self._temporary.cleanup()


class _Environment:
    """The environment the script reads, restored after every test."""

    def __init__(self, **values):
        self.values = values
        self._saved = {}

    def __enter__(self):
        for key, value in self.values.items():
            self._saved[key] = os.environ.get(key)
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        return self

    def __exit__(self, *exc):
        for key, value in self._saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        return False


class NotAskedForTest(MaestroTestCase):
    """Without `RUN_MAESTRO=1` the flows do not run."""

    def setUp(self):
        super().setUp()
        self._original = mod.run_maestro
        self._called = False

        def _called_maestro(*args, **kwargs):
            self._called = True
            return 0

        mod.run_maestro = _called_maestro

    def tearDown(self):
        mod.run_maestro = self._original
        super().tearDown()

    def test_nothing_runs_without_the_flag(self):
        with _Environment(RUN_MAESTRO=None):
            self.assertEqual(0, mod.main([]))
        self.assertFalse(self._called)

    def test_the_flag_has_to_be_exactly_one(self):
        with _Environment(RUN_MAESTRO='0'):
            self.assertEqual(0, mod.main([]))
        self.assertFalse(self._called)


class MissingCliTest(MaestroTestCase):
    """Without the `maestro` CLI the step explains itself instead of crashing."""

    def setUp(self):
        super().setUp()
        self._original_which = mod.shutil.which
        self._original_maestro = mod.run_maestro
        self._called = False

        def _called_maestro(*args, **kwargs):
            self._called = True
            return 0

        mod.shutil.which = lambda name: None
        mod.run_maestro = _called_maestro

    def tearDown(self):
        mod.shutil.which = self._original_which
        mod.run_maestro = self._original_maestro
        super().tearDown()

    def test_a_missing_cli_is_skipped_not_a_traceback(self):
        with _Environment(RUN_MAESTRO='1'):
            self.assertEqual(0, mod.main([]))
        self.assertFalse(self._called)


class FailingFlowsTest(MaestroTestCase):
    """When the flows run and fail, the step fails."""

    def setUp(self):
        super().setUp()
        self._original_which = mod.shutil.which
        self._original_maestro = mod.run_maestro
        mod.shutil.which = lambda name: '/usr/bin/maestro'
        mod.run_maestro = lambda serial, tags, skip_install: 1

    def tearDown(self):
        mod.shutil.which = self._original_which
        mod.run_maestro = self._original_maestro
        super().tearDown()

    def test_a_failing_run_fails_the_step(self):
        with _Environment(RUN_MAESTRO='1'):
            self.assertEqual(1, mod.main([]))

    def test_the_environment_reaches_the_runner(self):
        seen = {}

        def _record(serial, tags, skip_install):
            seen['serial'] = serial
            seen['tags'] = tags
            seen['skip_install'] = skip_install
            return 0

        mod.run_maestro = _record
        with _Environment(RUN_MAESTRO='1', MAESTRO_TAGS='agenda', SKIP_INSTALL='1'):
            self.assertEqual(0, mod.main([]))
        self.assertEqual('agenda', seen['tags'])
        self.assertEqual('1', seen['skip_install'])


class SerialPinTest(MaestroTestCase):
    """The pin is the device of this lane, and only while it is attached.

    A serial that names a device that is gone is not a pin: the runner would
    fail on it, and letting the runner choose a device is what happened before
    the lane was split apart.
    """

    def test_no_file_means_no_pin(self):
        with _Environment(SERIAL=None):
            self.assertEqual('', mod.read_serial())

    def test_an_attached_device_is_pinned(self):
        mod.SERIAL_FILE.write_text('emulator-5554\n', encoding='utf-8')
        with _Environment(SERIAL=None):
            self.assertEqual('emulator-5554', mod.read_serial())

    def test_a_device_that_is_gone_is_not_pinned(self):
        mod.SERIAL_FILE.write_text('emulator-9999\n', encoding='utf-8')
        with _Environment(SERIAL=None):
            self.assertEqual('', mod.read_serial())

    def test_a_device_that_is_not_ready_is_not_pinned(self):
        mod.SERIAL_FILE.write_text('emulator-5554\n', encoding='utf-8')
        mod.adb_devices = lambda: 'List of devices attached\nemulator-5554\toffline\n'
        with _Environment(SERIAL=None):
            self.assertEqual('', mod.read_serial())

    def test_the_callers_own_pin_is_used_as_it_is(self):
        mod.SERIAL_FILE.write_text('emulator-5554\n', encoding='utf-8')
        with _Environment(SERIAL='emulator-9999'):
            self.assertEqual('emulator-9999', mod.read_serial())

    def test_a_dropped_pin_leaves_the_runner_its_choice(self):
        mod.SERIAL_FILE.write_text('emulator-9999\n', encoding='utf-8')
        seen = {}

        def _record(serial, tags, skip_install):
            seen['serial'] = serial
            return 0

        original_which = mod.shutil.which
        original_maestro = mod.run_maestro
        mod.shutil.which = lambda name: '/usr/bin/maestro'
        mod.run_maestro = _record
        try:
            with _Environment(RUN_MAESTRO='1', SERIAL=None):
                self.assertEqual(0, mod.main([]))
        finally:
            mod.shutil.which = original_which
            mod.run_maestro = original_maestro
        self.assertEqual('', seen['serial'])


if __name__ == '__main__':
    unittest.main()

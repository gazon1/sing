"""Tests for android-instrumentation.py — the instrumented tier on a real device.

The device is the one part of the loop that cannot be tested by running it,
so the part that can be tested is the decision: *which* device to run on, and
whether to run at all.

Each case below is a way the tier could report green while the device tier
never ran:

  - a device that is `offline` or `unauthorized` is not a device to run on;
    picking one turns a missing device into a failed tier;
  - `SKIP_ADB=1` means the run is not wanted, and the script must not reach
    for adb at all — a "skip" that still lists devices is a skip that can fail;
  - a Gradle run that fails is reported, but it does not take the loop with it:
    the failure is the device lane's business, not this script's.
"""

import importlib.util
import os
import pathlib
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'android-instrumentation.py'
spec = importlib.util.spec_from_file_location('android_instrumentation', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class PickSerialTest(unittest.TestCase):
    """`adb devices` prints a header and one line per device; only `device` is ready."""

    def test_no_device_attached_is_no_serial(self):
        self.assertIsNone(mod.pick_serial(
            'List of devices attached\n\n'
        ))

    def test_one_device_is_its_serial(self):
        self.assertEqual(
            'emulator-5554',
            mod.pick_serial(
                'List of devices attached\n'
                'emulator-5554\tdevice\n'
            ),
        )

    def test_the_first_device_wins(self):
        self.assertEqual(
            'emulator-5554',
            mod.pick_serial(
                'List of devices attached\n'
                'emulator-5554\tdevice\n'
                'emulator-5556\tdevice\n'
            ),
        )

    def test_a_device_that_is_not_ready_is_not_a_device(self):
        for state in ('offline', 'unauthorized', 'unbootdevice', 'authorizing'):
            with self.subTest(state=state):
                self.assertIsNone(mod.pick_serial(
                    'List of devices attached\n'
                    f'emulator-5554\t{state}\n'
                ))

    def test_the_header_is_not_a_device(self):
        self.assertIsNone(mod.pick_serial('List of devices attached\n'))

    def test_a_ready_device_beats_an_unready_one(self):
        self.assertEqual(
            'emulator-5556',
            mod.pick_serial(
                'List of devices attached\n'
                'emulator-5554\toffline\n'
                'emulator-5556\tdevice\n'
            ),
        )


class SkipTest(unittest.TestCase):
    """`SKIP_ADB=1` means adb is not called at all."""

    def setUp(self):
        self._previous = os.environ.get('SKIP_ADB')
        self._called = False
        self._original = mod.adb_devices

        def _called_devices():
            self._called = True
            return 'List of devices attached\nemulator-5554\tdevice\n'

        mod.adb_devices = _called_devices

    def tearDown(self):
        mod.adb_devices = self._original
        if self._previous is None:
            os.environ.pop('SKIP_ADB', None)
        else:
            os.environ['SKIP_ADB'] = self._previous

    def test_skipping_does_not_call_adb(self):
        os.environ['SKIP_ADB'] = '1'
        self.assertEqual(0, mod.main([]))
        self.assertFalse(self._called)


class NoDeviceTest(unittest.TestCase):
    """Without a device the run is skipped, and it is not a failure."""

    def setUp(self):
        self._original = mod.adb_devices
        mod.adb_devices = lambda: 'List of devices attached\n'

    def tearDown(self):
        mod.adb_devices = self._original

    def test_a_missing_device_is_not_a_failed_run(self):
        os.environ.pop('SKIP_ADB', None)
        self.assertEqual(0, mod.main([]))


class GradleFailureTest(unittest.TestCase):
    """A failed Gradle run is reported; it does not take the loop with it."""

    def setUp(self):
        self._original_adb = mod.adb_devices
        self._original_gradle = mod.run_gradle
        self._original_serial_file = mod.SERIAL_FILE
        mod.adb_devices = lambda: 'List of devices attached\nemulator-5554\tdevice\n'
        mod.run_gradle = lambda task, serial: 1
        mod.SERIAL_FILE = pathlib.Path(
            os.environ.get('TMPDIR', '/tmp'),
        ) / 'android-instrumentation-test-serial.txt'

    def tearDown(self):
        mod.adb_devices = self._original_adb
        mod.run_gradle = self._original_gradle
        mod.SERIAL_FILE = self._original_serial_file

    def test_a_failing_gradle_run_does_not_fail_the_script(self):
        os.environ.pop('SKIP_ADB', None)
        self.assertEqual(0, mod.main([]))


if __name__ == '__main__':
    unittest.main()

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

And one case about what the lane leaves behind: the report and the serial of
an earlier run must not survive a run that does not happen, or the steps after
it price and pin a run that never was.
"""

import importlib.util
import os
import pathlib
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'android-instrumentation.py'
spec = importlib.util.spec_from_file_location('android_instrumentation', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class LanePathsTestCase(unittest.TestCase):
    """The lane's state paths point at a temporary directory, not the checkout.

    The script clears the report directory and the serial file before it runs,
    so a test that left them at the repository's paths would delete a real
    report — and would prove nothing about the paths it means to exercise.
    """

    def setUp(self):
        self._temporary = tempfile.TemporaryDirectory()
        root = pathlib.Path(self._temporary.name)
        self._original_report = mod.REPORT_DIR
        self._original_serial = mod.SERIAL_FILE
        mod.REPORT_DIR = root / 'report'
        mod.SERIAL_FILE = root / 'device-serial.txt'
        os.environ.pop('SKIP_ADB', None)

    def tearDown(self):
        mod.REPORT_DIR = self._original_report
        mod.SERIAL_FILE = self._original_serial
        self._temporary.cleanup()

    def seed_stale_state(self, serial='emulator-5554'):
        """The state an earlier run leaves behind."""
        mod.REPORT_DIR.mkdir(parents=True, exist_ok=True)
        (mod.REPORT_DIR / 'TEST-old.xml').write_text('<testsuite/>', encoding='utf-8')
        mod.SERIAL_FILE.parent.mkdir(parents=True, exist_ok=True)
        mod.SERIAL_FILE.write_text(f'{serial}\n', encoding='utf-8')


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


class SkipTest(LanePathsTestCase):
    """`SKIP_ADB=1` means adb is not called at all."""

    def setUp(self):
        super().setUp()
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
        super().tearDown()

    def test_skipping_does_not_call_adb(self):
        os.environ['SKIP_ADB'] = '1'
        self.assertEqual(0, mod.main([]))
        self.assertFalse(self._called)


class NoDeviceTest(LanePathsTestCase):
    """Without a device the run is skipped, and it is not a failure."""

    def setUp(self):
        super().setUp()
        self._original = mod.adb_devices
        mod.adb_devices = lambda: 'List of devices attached\n'

    def tearDown(self):
        mod.adb_devices = self._original
        super().tearDown()

    def test_a_missing_device_is_not_a_failed_run(self):
        self.assertEqual(0, mod.main([]))


class GradleFailureTest(LanePathsTestCase):
    """A failed Gradle run is reported; it does not take the loop with it."""

    def setUp(self):
        super().setUp()
        self._original_adb = mod.adb_devices
        self._original_gradle = mod.run_gradle
        mod.adb_devices = lambda: 'List of devices attached\nemulator-5554\tdevice\n'
        mod.run_gradle = lambda task, serial: 1

    def tearDown(self):
        mod.adb_devices = self._original_adb
        mod.run_gradle = self._original_gradle
        super().tearDown()

    def test_a_failing_gradle_run_does_not_fail_the_script(self):
        self.assertEqual(0, mod.main([]))


class LaneStateTest(LanePathsTestCase):
    """The state of the lane describes this lane, so nothing earlier survives it.

    The steps after this one read the report and the serial to answer "what
    did this run deliver?". The report of an earlier run would be priced by the
    floor step — whose ratchet is two-sided — and the serial of an earlier run
    would pin the Maestro flows to a device that may be gone. So a run that
    does not happen has to leave neither of them behind.
    """

    def test_a_skipped_run_leaves_no_report_and_no_serial(self):
        self.seed_stale_state()
        os.environ['SKIP_ADB'] = '1'
        self.assertEqual(0, mod.main([]))
        self.assertFalse(mod.REPORT_DIR.exists())
        self.assertFalse(mod.SERIAL_FILE.exists())

    def test_a_run_without_a_device_leaves_no_report_and_no_serial(self):
        self.seed_stale_state()
        original = mod.adb_devices
        mod.adb_devices = lambda: 'List of devices attached\n'
        try:
            self.assertEqual(0, mod.main([]))
        finally:
            mod.adb_devices = original
        self.assertFalse(mod.REPORT_DIR.exists())
        self.assertFalse(mod.SERIAL_FILE.exists())

    def test_a_run_records_the_device_it_used(self):
        original_adb = mod.adb_devices
        original_gradle = mod.run_gradle
        mod.adb_devices = lambda: 'List of devices attached\nemulator-5554\tdevice\n'
        mod.run_gradle = lambda task, serial: 0
        try:
            self.assertEqual(0, mod.main([]))
        finally:
            mod.adb_devices = original_adb
            mod.run_gradle = original_gradle
        self.assertEqual(
            'emulator-5554',
            mod.SERIAL_FILE.read_text(encoding='utf-8').strip(),
        )


if __name__ == '__main__':
    unittest.main()

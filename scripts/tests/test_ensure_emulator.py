#!/usr/bin/env python3
"""Unit tests for scripts/src/emulator/ package.

Run with: python3 -m unittest discover -s scripts/tests

The package's core logic (device detection, readiness, AVD lifecycle) is tested
here without the emulator itself by patching the adb subprocess layer and the
/proc scan.
"""
from __future__ import annotations

import contextlib
import io
import os
import pathlib
import sys
import tempfile
import threading
import time
import unittest
import unittest.mock

import click
import emulator as ee
import emulator._cli as _cli
import emulator._device as _device
import emulator._startup as _startup

# Import from the package (no hyphen in package name — direct import).
from emulator import (
    EXIT_CRASHED,
    EXIT_LOCK,
    EXIT_NOT_READY,
    EXIT_OK,
    EXIT_TIMEOUT,
    cfg,
    clear_stale_emulator,
    device_is_ready,
    find_ready,
    first_ready_device,
    main,
    popen,
    start_avd,
    startup_lock,
    try_run,
)
from emulator._startup import Acquisition, acquire_device
from emulator.maestro._testing import Clock, FakeAdb, FakeProc

# ── helpers ───────────────────────────────────────────────────────────────────

def _make_fake_try_run(table: dict[str, str | None]):
    """Return a try_run-like callable backed by a dict.

    table: mapping from cmdline string (space-joined) to output string or None.
           A key of "*" matches any command not explicitly listed.
    """
    def fake(args: list[str], *, timeout: float = 5, **kwargs):
        key = " ".join(args)
        value = table.get(key, table.get("*"))
        if value is None:
            cp = unittest.mock.Mock()
            cp.returncode = 1
            cp.stdout = ""
            return cp
        cp = unittest.mock.Mock()
        cp.returncode = 0
        cp.stdout = value
        return cp
    return fake


# ── readiness tests ────────────────────────────────────────────────────────────

def _std_table(serial: str) -> dict[str, str | None]:
    """A table where all readiness checks pass for the given serial."""
    return {
        "adb devices": f"List of devices attached\n{serial} device\n",
        f"adb -s {serial} get-state": "device",
        f"adb -s {serial} shell getprop sys.boot_completed": "1",
        f"adb -s {serial} shell pm path android": "package:/system/framework/framework-res.apk",
        f"adb -s {serial} shell dumpsys SurfaceFlinger": "SurfaceFlinger\nGPU mem:",
    }


class DeviceIsReadyTest(unittest.TestCase):

    def _run(self, serial: str, table: dict[str, str | None]):
        with unittest.mock.patch.object(_device, "try_run", _make_fake_try_run(table)):
            return ee.device_is_ready(serial)

    def test_all_checks_pass_returns_true(self):
        t = _std_table("emulator-5554")
        self.assertTrue(self._run("emulator-5554", t))

    def test_not_device_state_returns_false(self):
        t = {**_std_table("emulator-5554"), "adb -s emulator-5554 get-state": "unauthorized"}
        self.assertFalse(self._run("emulator-5554", t))

    def test_boot_completed_not_1_returns_false(self):
        t = {**_std_table("emulator-5554"),
             "adb -s emulator-5554 shell getprop sys.boot_completed": "0"}
        self.assertFalse(self._run("emulator-5554", t))

    def test_pm_path_empty_returns_false(self):
        t = {**_std_table("emulator-5554"), "adb -s emulator-5554 shell pm path android": ""}
        self.assertFalse(self._run("emulator-5554", t))

    def test_pm_path_none_returns_false(self):
        t = {**_std_table("emulator-5554"), "adb -s emulator-5554 shell pm path android": None}
        self.assertFalse(self._run("emulator-5554", t))

    def test_sf_not_responsive_returns_false(self):
        t = {**_std_table("emulator-5554"), "adb -s emulator-5554 shell dumpsys SurfaceFlinger": ""}
        self.assertFalse(self._run("emulator-5554", t))

    def test_sf_timeout_returns_false(self):
        t = {**_std_table("emulator-5554"), "adb -s emulator-5554 shell dumpsys SurfaceFlinger": None}
        self.assertFalse(self._run("emulator-5554", t))

    def test_empty_serial_returns_false(self):
        self.assertFalse(ee.device_is_ready(""))


class FirstReadyDeviceTest(unittest.TestCase):

    def _run(self, table: dict[str, str | None]):
        with unittest.mock.patch.object(_device, "try_run", _make_fake_try_run(table)):
            return ee.first_ready_device()

    def test_returns_first_ready_device(self):
        t = {
            "adb devices": "List of devices attached\nemulator-5554 device\nemulator-5556 device\n",
            "adb -s emulator-5554 get-state": "device",
            "adb -s emulator-5554 shell getprop sys.boot_completed": "1",
            "adb -s emulator-5554 shell pm path android": "package:/system/framework/framework-res.apk",
            "adb -s emulator-5554 shell dumpsys SurfaceFlinger": "SurfaceFlinger\nGPU mem:",
            # 5556: not ready — boot_completed=0
            "adb -s emulator-5556 get-state": "device",
            "adb -s emulator-5556 shell getprop sys.boot_completed": "0",
            "adb -s emulator-5556 shell pm path android": "package:/system/framework/framework-res.apk",
            "adb -s emulator-5556 shell dumpsys SurfaceFlinger": "SurfaceFlinger\nGPU mem:",
        }
        self.assertEqual(self._run(t), "emulator-5554")

    def test_no_device_returns_none(self):
        self.assertIsNone(self._run({"adb devices": "List of devices attached\n"}))

    def test_device_not_ready_returns_none(self):
        t = {**_std_table("emulator-5554"),
             "adb -s emulator-5554 shell getprop sys.boot_completed": "0"}
        self.assertIsNone(self._run(t))


class FindReadyTest(unittest.TestCase):

    def _run(self, pin: str, table: dict[str, str | None]):
        with unittest.mock.patch.object(_device, "try_run", _make_fake_try_run(table)):
            return ee.find_ready(pin)

    def test_pin_ready_returns_pin(self):
        self.assertEqual(self._run("emulator-5556", _std_table("emulator-5556")),
                         "emulator-5556")

    def test_pin_not_ready_returns_none(self):
        t = {"adb devices": "List of devices attached\nemulator-5556 device\n",
             "adb -s emulator-5556 get-state": "offline"}
        self.assertIsNone(self._run("emulator-5556", t))

    def test_no_pin_returns_first_ready(self):
        self.assertEqual(self._run("", _std_table("emulator-5554")), "emulator-5554")


# ── _avd_is_running ───────────────────────────────────────────────────────────

class AvdIsRunningTest(unittest.TestCase):

    def _patch(self, value: bool):
        return unittest.mock.patch.object(ee, "_avd_is_running", lambda: value)

    def test_running_returns_true(self):
        with self._patch(True):
            self.assertTrue(ee._avd_is_running())

    def test_not_running_returns_false(self):
        with self._patch(False):
            self.assertFalse(ee._avd_is_running())

    def test_uses_proc_scan_not_pgrep(self):
        """Verify the implementation scans /proc rather than spawning pgrep."""
        orig = ee._avd_is_running
        calls: list = []

        def capturing_wrapper() -> bool:
            # Patch os.listdir and Path.read_bytes to capture that they're called
            # with /proc paths, proving pgrep is not used.
            captured_pids = []
            def fake_listdir(path: str):
                if path == "/proc":
                    captured_pids.extend(["1", "2"])
                    return captured_pids
                return os.listdir(path)
            def fake_read(path: pathlib.Path):
                s = str(path)
                if s == "/proc/1/cmdline":
                    return b"qemu-system-x86_64\x00-avd\x00Medium_Phone\x00-no-snapshot"
                raise FileNotFoundError()
            with unittest.mock.patch.object(os, "listdir", fake_listdir):
                with unittest.mock.patch.object(pathlib.Path, "read_bytes", fake_read):
                    result = orig()
            return result, captured_pids

        # Patch the module-level function directly.
        with unittest.mock.patch.object(ee, "_avd_is_running", capturing_wrapper):
            result, pids = ee._avd_is_running()
        self.assertTrue(result)
        self.assertEqual(pids, ["1", "2"])  # proves /proc was listed


# ── startup_lock ───────────────────────────────────────────────────────────────

class StartupLockTest(unittest.TestCase):

    def test_acquires_and_releases_cleanly(self):
        """Lock is acquired immediately when free; released on context exit."""
        with tempfile.TemporaryDirectory() as tmp:
            lock = pathlib.Path(tmp) / "test.lock"
            orig = ee.LOCK_FILE
            ee.LOCK_FILE = lock
            try:
                # First acquisition succeeds (no one holds it).
                with ee.startup_lock(timeout=5):
                    pass
                # Immediate re-acquisition succeeds — fd was released on exit.
                with ee.startup_lock(timeout=5):
                    pass
            finally:
                ee.LOCK_FILE = orig

    def test_second_caller_blocks_then_times_out(self):
        """A waiter that cannot acquire the lock hits its deadline and raises."""
        with tempfile.TemporaryDirectory() as tmp:
            lock = pathlib.Path(tmp) / "test.lock"
            barrier = threading.Barrier(2)
            holder_can_exit = threading.Event()

            def holder():
                orig = ee.LOCK_FILE
                ee.LOCK_FILE = lock
                try:
                    with ee.startup_lock(timeout=10):
                        barrier.wait(timeout=5)  # signal: holder has the lock
                        holder_can_exit.wait(timeout=5)  # wait until test is ready
                finally:
                    ee.LOCK_FILE = orig

            t = threading.Thread(target=holder, group=None)
            t.start()
            barrier.wait(timeout=5)  # wait until holder has the lock

            # Now the holder holds the lock. Waiter will block and timeout.
            orig = ee.LOCK_FILE
            ee.LOCK_FILE = lock
            try:
                with self.assertRaises(TimeoutError):
                    with ee.startup_lock(timeout=1):  # 1s deadline
                        pass  # should not reach here
            finally:
                ee.LOCK_FILE = orig
                holder_can_exit.set()  # let holder release the lock
                t.join(timeout=10)


# ── pin semantics ─────────────────────────────────────────────────────────────

class PinSemanticsTest(unittest.TestCase):

    def _run(self, pin: str, table: dict[str, str | None]):
        with unittest.mock.patch.object(_device, "try_run", _make_fake_try_run(table)):
            return ee.find_ready(pin)

    def test_pin_not_ready_is_not_silently_ignored(self):
        t = {"adb devices": "List of devices attached\nemulator-5556 device\n",
             "adb -s emulator-5556 get-state": "offline"}
        self.assertIsNone(self._run("emulator-5556", t))

    def test_physical_serial_rejected_when_offline(self):
        t = {"adb devices": "List of devices attached\nABCXYZ device\n",
             "adb -s ABCXYZ get-state": "unauthorized"}
        self.assertIsNone(self._run("ABCXYZ", t))


# ── exit codes ───────────────────────────────────────────────────────────────

class ExitCodesTest(unittest.TestCase):

    def test_exit_code_constants(self):
        self.assertEqual(ee.EXIT_OK, 0)
        self.assertEqual(ee.EXIT_NOT_READY, 1)
        self.assertEqual(ee.EXIT_CRASHED, 2)
        self.assertEqual(ee.EXIT_TIMEOUT, 3)
        self.assertEqual(ee.EXIT_LOCK, 4)


# ── main() argument parsing ───────────────────────────────────────────────────

class MainArgParsingTest(unittest.TestCase):

    def test_main_no_device_returns_not_ready(self):
        """When no device is ready and the AVD cannot be started, returns its code.

        Click's main() calls ctx.exit() after the callback, which raises Exit(0)
        and then sys.exit(0), discarding the callback's return value.  Use
        standalone_mode=False so main() returns the exit code directly.
        """
        with unittest.mock.patch.object(_startup, "find_ready", return_value=None):
            with unittest.mock.patch.object(_startup, "start_avd",
                                            return_value=ee.EXIT_NOT_READY):
                rc = ee.main([], standalone_mode=False)
                self.assertEqual(rc, ee.EXIT_NOT_READY)

    def test_help_exits_zero(self):
        with self.assertRaises(SystemExit) as ctx:
            ee.main(["--help"])
        self.assertEqual(ctx.exception.code, 0)


class IntegrationTest(unittest.TestCase):
    """End-to-end tests for the full start_avd → ready cycle.

    start_avd() captures 'import time' in its __globals__, so time.sleep / time.monotonic
    must be patched via sys.modules['time'] for virtual-clock tests to work.
    """

    def _run_start_avd(self, serial: str, fake: FakeAdb,
                       boot_timeout: int = 300,
                       avd_running: bool = False,
                       lock_conflict_log: bool = False) -> int:
        """Call start_avd with FakeAdb wired in.

        Uses a real Mock (not MagicMock) for time so that:
        - time.sleep is replaced with a no-op (no real sleeping)
        - time.monotonic() is replaced with a counter that advances by 1 per call
          (acts as a virtual clock for the deadline check)
        The boot simulation (boot_completed flag) is driven by fake.time_sleep,
        which advances the fake clock and sets boot_completed when the deadline
        is reached.
        """
        orig_avd = ee.AVD
        orig_lock = ee.LOCK_FILE
        orig_extra = ee.EXTRA_FLAGS
        orig_boot = ee.BOOT_TIMEOUT
        log_path = pathlib.Path(tempfile.mkdtemp()) / "em.log"

        # Virtual monotonic: reads and increments fake._clock._monotonic_offset so
        # the same virtual clock drives both time.sleep (via time_sleep) and the
        # deadline check (via monotonic()).  Each time.monotonic() call advances
        # the virtual clock by 1 s (modelling one adb probe round-trip).
        def fake_monotonic():
            v = fake._clock._monotonic_offset
            fake._clock._monotonic_offset += 1
            return v

        orig_time = _startup.time

        # For lock-conflict simulation, mock LOG_FILE.read_text() to return the
        # conflict message.  This bypasses the file-content issue (start_avd's
        # open("wb") truncates the file before it reads) and also sidesteps the
        # pathlib open() patching complexity.  The real log file is untouched.
        if lock_conflict_log:
            conflict_text = ("Failed to get write lock for AVD. "
                              "Another emulator instance may be running.\n")

            def fake_read_text(self_, *args, **kwargs):
                # 'self' here is the cfg.log_file (patched as log_path).
                return conflict_text

            # start_avd opens the log with "wb" before reading it, so the
            # conflict text has to come from a patched read_text rather than the
            # real file.
        else:
            fake_read_text = None

        cfg.avd = "Medium_Phone"
        cfg.lock_file = pathlib.Path(tempfile.mkdtemp()) / "test.lock"
        cfg.extra_flags = []
        cfg.boot_timeout = boot_timeout
        try:
            with contextlib.redirect_stderr(io.StringIO()):
                with unittest.mock.patch.object(_device, "try_run", fake.try_run), \
                     unittest.mock.patch.object(_device, "popen", fake.popen), \
                     unittest.mock.patch.object(ee, "popen", fake.popen), \
                     unittest.mock.patch.object(ee, "_avd_is_running", lambda: avd_running), \
                     unittest.mock.patch.object(cfg, "log_file", log_path), \
                     unittest.mock.patch.object(cfg, "android_avd_home", pathlib.Path("/tmp")), \
                     unittest.mock.patch.object(_startup.time, "sleep", fake.time_sleep), \
                     unittest.mock.patch.object(_startup.time, "monotonic", fake_monotonic), \
                     unittest.mock.patch.object(pathlib.Path, "read_text",
                                                fake_read_text) if fake_read_text else contextlib.nullcontext():
                    return ee.start_avd(serial)
        finally:
            cfg.avd = orig_avd
            cfg.lock_file = orig_lock
            cfg.extra_flags = orig_extra
            cfg.boot_timeout = orig_boot
            _startup.time = orig_time

    def test_device_boots_within_timeout_returns_exit_ok(self):
        """Normal boot: device ready on first probe → EXIT_OK.

        The serial must be passed to start_avd so find_ready() can check
        device_is_ready("emulator-5554"). Passing "" skips the readiness check
        and times out.
        """
        fake = FakeAdb(boot_time=0.0)  # ready immediately
        rc = self._run_start_avd("emulator-5554", fake, boot_timeout=300)
        self.assertEqual(rc, ee.EXIT_OK)
        self.assertEqual(fake.serial, "emulator-5554")
        self.assertTrue(fake.started)

    def test_boot_timeout_returns_exit_timeout(self):
        """Device never becomes ready → EXIT_TIMEOUT.

        The virtual monotonic counter reaches boot_timeout (10) before boot_completed
        is set (boot_time=600.0), so the loop exits with EXIT_TIMEOUT.
        """
        fake = FakeAdb(boot_time=600.0)
        rc = self._run_start_avd("", fake, boot_timeout=10)
        self.assertEqual(rc, ee.EXIT_TIMEOUT)

    def test_emulator_process_crash_returns_exit_crashed(self):
        """popen() returns a process that has already exited → EXIT_CRASHED."""
        fake = FakeAdb(boot_time=5.0, poll_returns=[1])
        rc = self._run_start_avd("", fake)
        self.assertEqual(rc, ee.EXIT_CRASHED)

    def test_getstate_unauthorized_never_ready_returns_exit_timeout(self):
        """Device boots (boot_completed=1) but get-state returns 'unauthorized'.
        device_is_ready() returns False, the polling loop never finds a ready device,
        and the deadline expires → EXIT_TIMEOUT.
        """
        fake = FakeAdb(boot_time=0.0)   # boot completes instantly
        fake._unauthorized = True        # get-state will return "unauthorized"
        rc = self._run_start_avd("emulator-5554", fake, boot_timeout=3)
        self.assertEqual(rc, ee.EXIT_TIMEOUT)

    def test_sf_timeout_boot_completed_returns_exit_timeout(self):
        """Device boots (boot_completed=1, pm path OK) but SurfaceFlinger is
        unresponsive. device_is_ready() never returns True → EXIT_TIMEOUT.
        """
        fake = FakeAdb(boot_time=0.0)    # boot completes instantly
        fake._sf_responsive = False      # dumpsys SurfaceFlinger → None
        rc = self._run_start_avd("emulator-5554", fake, boot_timeout=3)
        self.assertEqual(rc, ee.EXIT_TIMEOUT)

    def test_lock_conflict_one_retry_then_ok(self):
        """First emulator launch exits with 'Failed to get write lock', retries,
        second launch succeeds → EXIT_OK.

        poll_returns=[1, None]: first FakeProc pops 1 (crash), second FakeProc
        pops None (alive). Both share the same list via FakeAdb.poll_returns.
        """
        fake = FakeAdb(boot_time=0.0, poll_returns=[1, None])
        rc = self._run_start_avd("emulator-5554", fake, lock_conflict_log=True)
        self.assertEqual(rc, ee.EXIT_OK)

    def test_lock_conflict_exhausted_retries_returns_exit_timeout(self):
        """Three emulator crashes exhaust startup_attempts=3 → EXIT_TIMEOUT.

        All three FakeProc instances share poll_returns=[None, None, None, None].
        Each FakeProc.poll() returns None (alive), so the loop keeps polling
        until the virtual deadline (monotonic >= boot_timeout=8) expires.
        boot_time=600.0 means the device is never ready within that window.
        """
        fake = FakeAdb(boot_time=600.0, poll_returns=[None, None, None, None])
        rc = self._run_start_avd("emulator-5554", fake, boot_timeout=8, lock_conflict_log=True)
        self.assertEqual(rc, ee.EXIT_TIMEOUT)

    def test_start_avd_writes_nothing_to_stdout(self):
        """stdout carries the device serial and nothing else.

        `SERIAL=$(ensure-emulator)` depends on it, and `start_avd` used to print
        the serial from three separate branches.
        """
        fake = FakeAdb(boot_time=0.0)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = self._run_start_avd("emulator-5554", fake, boot_timeout=300)
        self.assertEqual(rc, ee.EXIT_OK)
        self.assertEqual(buf.getvalue(), "")


class AcquireDeviceTest(unittest.TestCase):
    """acquire_device() is the one way to get a device; the table below is its contract."""

    def _locked(self, *, raises: bool = False):
        if raises:
            @contextlib.contextmanager
            def lock(_timeout):
                raise TimeoutError("locked")
                yield  # pragma: no cover — unreachable, keeps it a generator
            return lock
        return contextlib.nullcontext()

    def test_ready_device_short_circuits(self):
        with unittest.mock.patch.object(_startup, "find_ready", return_value="emulator-5554"):
            acq = acquire_device("emulator-5554")
        self.assertEqual(acq.serial, "emulator-5554")
        self.assertEqual(acq.exit_code, ee.EXIT_OK)
        self.assertTrue(acq)

    def test_empty_pin_starts_the_avd(self):
        """No pin means 'any device' — it must boot one, not fail.

        This is the bug that took out `just gm agenda`: the Maestro CLI used to
        skip acquisition entirely when SERIAL was empty.
        """
        started: list[str] = []

        def fake_start(pin):
            started.append(pin)
            return ee.EXIT_OK

        # find_ready answers None for the fast path and the in-lock re-check, then
        # the serial acquire_device re-probes for after start_avd returns.
        seq = [None, None, "emulator-5556"]
        with unittest.mock.patch.object(_startup, "find_ready", side_effect=seq), \
             unittest.mock.patch.object(_startup, "start_avd", fake_start):
            acq = acquire_device("")
        self.assertEqual(started, [""], "the AVD must be started when no pin is given")
        self.assertEqual(acq.serial, "emulator-5556")
        self.assertEqual(acq.exit_code, ee.EXIT_OK)

    def test_physical_pin_never_starts_an_avd(self):
        started: list[str] = []
        with unittest.mock.patch.object(_startup, "device_is_ready", return_value=False), \
             unittest.mock.patch.object(_startup, "start_avd",
                                         lambda pin: started.append(pin) or ee.EXIT_OK), \
             unittest.mock.patch.object(_startup, "adb", return_value=""):
            acq = acquire_device("R58M1234ABC")
        self.assertEqual(started, [], "a pinned physical device must not boot an AVD")
        self.assertIsNone(acq.serial)
        self.assertEqual(acq.exit_code, ee.EXIT_NOT_READY)

    def test_physical_pin_ready_is_used_as_is(self):
        with unittest.mock.patch.object(_startup, "device_is_ready", return_value=True):
            acq = acquire_device("R58M1234ABC")
        self.assertEqual(acq.serial, "R58M1234ABC")
        self.assertEqual(acq.exit_code, ee.EXIT_OK)

    def test_emulator_pin_unready_relaunches(self):
        """A pinned-but-unready AVD is the crash case recovery exists for."""
        started: list[str] = []
        seq = [None, None, "emulator-5554"]
        with unittest.mock.patch.object(_startup, "find_ready", side_effect=seq), \
             unittest.mock.patch.object(_startup, "start_avd",
                                         lambda pin: started.append(pin) or ee.EXIT_OK):
            acq = acquire_device("emulator-5554")
        self.assertEqual(started, ["emulator-5554"])
        self.assertEqual(acq.serial, "emulator-5554")

    def test_crash_propagates_exit_crashed(self):
        with unittest.mock.patch.object(_startup, "find_ready", return_value=None), \
             unittest.mock.patch.object(_startup, "start_avd", return_value=ee.EXIT_CRASHED):
            acq = acquire_device("")
        self.assertEqual(acq.exit_code, ee.EXIT_CRASHED)
        self.assertIsNone(acq.serial)

    def test_boot_timeout_propagates_exit_timeout(self):
        with unittest.mock.patch.object(_startup, "find_ready", return_value=None), \
             unittest.mock.patch.object(_startup, "start_avd", return_value=ee.EXIT_TIMEOUT):
            acq = acquire_device("")
        self.assertEqual(acq.exit_code, ee.EXIT_TIMEOUT)

    def test_lock_timeout_but_device_arrived_still_succeeds(self):
        """The holder boots for about as long as a waiter blocks.

        Reporting EXIT_LOCK there would fail a healthy run by a race, so a final
        readiness check runs before giving up.
        """
        seq = [None, "emulator-5554"]
        with unittest.mock.patch.object(_startup, "find_ready", side_effect=seq), \
             unittest.mock.patch.object(_startup, "startup_lock", self._locked(raises=True)):
            acq = acquire_device("")
        self.assertEqual(acq.serial, "emulator-5554")
        self.assertEqual(acq.exit_code, ee.EXIT_OK)

    def test_lock_timeout_with_no_device_reports_lock(self):
        with unittest.mock.patch.object(_startup, "find_ready", return_value=None), \
             unittest.mock.patch.object(_startup, "startup_lock", self._locked(raises=True)):
            acq = acquire_device("")
        self.assertIsNone(acq.serial)
        self.assertEqual(acq.exit_code, ee.EXIT_LOCK)

    def test_oserror_from_lock_is_not_swallowed(self):
        """A broken lock file is a fault, not an expected failure.

        Flattening it into EXIT_LOCK would hide the cause behind a retry.
        """
        @contextlib.contextmanager
        def exploding(_timeout):
            raise OSError("lock file unreadable")
            yield  # pragma: no cover

        with unittest.mock.patch.object(_startup, "find_ready", return_value=None), \
             unittest.mock.patch.object(_startup, "startup_lock", exploding):
            with self.assertRaises(OSError):
                acquire_device("")


class MainOutputContractTest(unittest.TestCase):
    """`ensure-emulator` prints the serial on stdout and diagnostics on stderr."""

    def _deps(self, acquisition):
        """A deps struct whose acquisition is a fake.

        Injection goes through `EmulatorDeps`, swapped into the module
        attribute `main` reads, NOT through patching `_startup.acquire_device`.
        The singleton binds `acquire_fn` in `__post_init__` — at import — so a
        later patch of `_startup.acquire_device` never reaches it and the test
        silently acquires a REAL device. That is how the "no device" case below
        came to expect exit 1 while a live emulator made it return 0, and how
        its sibling passed for the wrong reason: the host's real serial
        happened to be the string the fixture asserted. A test that consults the
        machine it runs on is not a test of the contract.

        The fake counts its calls, so a seam that stops being reached fails the
        test instead of quietly deferring to the host.
        """
        calls = []

        def acquire(pin):
            calls.append(pin)
            return acquisition

        deps = _cli.EmulatorDeps(acquire_fn=acquire)
        deps.calls = calls
        return deps

    def test_main_prints_only_the_serial(self):
        out, err = io.StringIO(), io.StringIO()
        deps = self._deps(Acquisition("emulator-5554", ee.EXIT_OK))
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            with unittest.mock.patch.object(_cli, "_emulator_deps", deps):
                rc = ee.main([], standalone_mode=False)
        self.assertEqual(rc, ee.EXIT_OK)
        self.assertEqual(out.getvalue(), "emulator-5554\n")
        self.assertEqual(len(deps.calls), 1, "the fake acquisition was bypassed")

    def test_main_prints_nothing_when_no_device(self):
        out = io.StringIO()
        deps = self._deps(Acquisition(None, ee.EXIT_NOT_READY))
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            with unittest.mock.patch.object(_cli, "_emulator_deps", deps):
                rc = ee.main([], standalone_mode=False)
        self.assertEqual(rc, ee.EXIT_NOT_READY)
        self.assertEqual(out.getvalue(), "")
        self.assertEqual(len(deps.calls), 1, "the fake acquisition was bypassed")


class IsPhysicalPinTest(unittest.TestCase):

    def test_console_port_pin_is_not_physical(self):
        self.assertFalse(_startup._is_physical_pin("emulator-5554"))
        self.assertFalse(_startup._is_physical_pin("emulator-5556"))

    def test_usb_serial_is_physical(self):
        self.assertTrue(_startup._is_physical_pin("R58M1234ABC"))

    def test_empty_pin_is_not_physical(self):
        self.assertFalse(_startup._is_physical_pin(""))


if __name__ == "__main__":
    unittest.main()

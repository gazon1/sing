"""emulator._startup — AVD lifecycle: lock, recovery, and boot orchestration."""
from __future__ import annotations

import fcntl
import os
import shutil
import time
from contextlib import contextmanager
from pathlib import Path

import emulator as _emu
from emulator._device import adb, device_is_ready, first_ready_device, find_ready, try_run
from emulator._types import EXIT_CRASHED, EXIT_LOCK, EXIT_OK, EXIT_TIMEOUT


def _popen(args, stdout_fd=None):
    # Lazy import to avoid circular import during emulator/__init__.py load.
    # Test patches emulator.popen, so we access it through the emulator module
    # to pick up any test patches.
    return _emu.popen(args, stdout_fd=stdout_fd)


# ── startup lock ──────────────────────────────────────────────────────────────

@contextmanager
def startup_lock(timeout: float):
    """Serialize AVD restarts across concurrent callers.

    Only one caller may restart the AVD at a time. Others block here, then
    re-check readiness once they acquire the lock — the winner may have already
    brought up a device they can use.

    The lock fd is not inherited by the emulator child because Python opens
    files without O_INHERIT (on Linux); the emulator therefore cannot hold the
    lock for its lifetime.
    """
    lock_path = _emu.LOCK_FILE
    deadline = time.monotonic() + timeout
    while True:
        try:
            with lock_path.open("w") as fh:
                fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                yield
                fcntl.flock(fh.fileno(), fcntl.LOCK_UN)
                return
        except BlockingIOError:
            if time.monotonic() > deadline:
                raise TimeoutError("Timed out waiting for startup lock.")
            time.sleep(1)


# ── recovery ──────────────────────────────────────────────────────────────────

def _avd_is_running() -> bool:
    """True if a qemu-system-x86_64 process for our AVD is already running.

    Uses /proc scan instead of pgrep for two reasons:
      1. No external process overhead.
      2. No ERE compatibility concerns.
    """
    avd_bytes = _emu.AVD.encode()
    try:
        for pid in os.listdir("/proc"):
            if not pid.isdigit():
                continue
            try:
                cmdline = Path(f"/proc/{pid}/cmdline").read_bytes()
                if b"qemu-system" in cmdline and b"-avd" in cmdline and avd_bytes in cmdline:
                    return True
            except OSError:
                pass
    except OSError:
        pass
    return False


def _kill_avd() -> None:
    """Send SIGKILL to any qemu process for our AVD; do not wait for exit."""
    avd_bytes = _emu.AVD.encode()
    try:
        for pid in os.listdir("/proc"):
            if not pid.isdigit():
                continue
            try:
                cmdline = Path(f"/proc/{pid}/cmdline").read_bytes()
                if b"qemu-system" in cmdline and b"-avd" in cmdline and avd_bytes in cmdline:
                    os.kill(int(pid), 9)
            except (OSError, ProcessLookupError):
                pass
    except OSError:
        pass


def clear_stale_emulator() -> None:
    """Kill any stale emulator process for AVD and remove its lock files."""
    _kill_avd()

    avd_dir = _emu.cfg.android_avd_home / f"{_emu.AVD}.avd"
    for pattern in ("*.lock", ".*.lock"):
        for lock in avd_dir.glob(pattern):
            try:
                shutil.rmtree(lock) if lock.is_dir() else lock.unlink(missing_ok=True)
            except OSError:
                pass


def _tail_log(n: int = 20) -> None:
    if _emu.cfg.log_file.exists():
        for line in _emu.cfg.log_file.read_text(errors="replace").splitlines()[-n:]:
            print(line)


# ── AVD start ─────────────────────────────────────────────────────────────────

def start_avd(pin: str) -> int:
    """Start the AVD and wait for it to become ready. Returns exit code."""
    # Before killing anything, check if an emulator for this AVD is already running.
    if _avd_is_running():
        running_serial = first_ready_device()
        if running_serial and device_is_ready(running_serial):
            if pin and pin.startswith("emulator-"):
                expected_port = pin.removeprefix("emulator-")
                if f"emulator-{expected_port}" == running_serial:
                    print(running_serial)
                    return EXIT_OK
                clear_stale_emulator()
            else:
                print(running_serial)
                return EXIT_OK

    clear_stale_emulator()

    # Keep log of the previous run (potential crash diagnostic).
    prev = _emu.cfg.log_file.with_name(_emu.cfg.log_file.name + ".prev")
    if _emu.cfg.log_file.exists():
        os.replace(_emu.cfg.log_file, prev)

    # Flags, and why each is here:
    #   -no-snapshot        snapshots are never reused; saving wastes time
    #   -camera-* none     virtual camera's WebRTC thread aborts after ~9 min
    #   -no-audio           removes unrelated host-audio failure mode
    emulator_args = [
        "emulator", "-avd", _emu.AVD,
        "-no-snapshot",
        "-no-boot-anim",
        "-camera-back", "none",
        "-camera-front", "none",
        "-no-audio",
        *_emu.EXTRA_FLAGS,
    ]

    # Pin semantics: SERIAL=emulator-5556 → console port 5556.
    if pin and pin.startswith("emulator-"):
        emulator_args += ["-port", pin.removeprefix("emulator-")]

    startup_attempts = 3
    for attempt in range(1, startup_attempts + 1):
        log_fh = _emu.cfg.log_file.open("wb")
        proc = _popen(emulator_args, stdout_fd=log_fh.fileno())
        os.close(log_fh.fileno())

        deadline = time.monotonic() + _emu.BOOT_TIMEOUT
        while time.monotonic() < deadline:
            rc = proc.poll()
            if rc is not None:
                log_text = (
                    _emu.cfg.log_file.read_text(errors="replace")
                    if _emu.cfg.log_file.exists() else ""
                )
                if "Failed to get" in log_text and attempt < startup_attempts:
                    time.sleep(5)
                    break

                return EXIT_CRASHED

            if serial := find_ready(pin):
                if pin and serial != pin:
                    time.sleep(2)
                    continue
                print(serial)
                return EXIT_OK

            time.sleep(2)
        else:
            break

    return EXIT_TIMEOUT

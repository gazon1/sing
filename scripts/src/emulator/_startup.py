"""emulator._startup — AVD lifecycle: lock, recovery, acquisition and boot orchestration."""
from __future__ import annotations

import fcntl
import os
import shutil
import time
from contextlib import contextmanager
from pathlib import Path
from typing import NamedTuple

from loguru import logger

import emulator as _emu
import emulator._device as _device
from emulator._device import (
    adb,
    adb_on,
    device_is_ready,
    find_ready,
    first_ready_device,
    probe_device_ready,
    try_run,
)
from emulator._types import EXIT_CRASHED, EXIT_LOCK, EXIT_NOT_READY, EXIT_OK, EXIT_TIMEOUT


def _popen(args, stdout_fd=None):
    # Lazy import to avoid circular import during emulator/__init__.py load.
    # Test patches emulator.popen, so we access it through the emulator module
    # to pick up any test patches.
    return _emu.popen(args, stdout_fd=stdout_fd)


class Acquisition(NamedTuple):
    """Result of acquiring a device: the serial, and why not if there is none.

    Truthy iff a serial was obtained, so call sites read ``if not acq:``.
    """

    serial: str | None
    exit_code: int

    def __bool__(self) -> bool:
        return bool(self.serial)


# ── device kind ────────────────────────────────────────────────────────────────

def is_emulator_serial(serial: str) -> bool:
    """True when the serial names an AVD console port, e.g. ``emulator-5554``.

    One decision, one place.  adb reports an emulator with a serial of the
    form ``emulator-<port>`` and a physical device with its hardware serial
    (or an ``ip:port`` pair), so the prefix is the whole distinction.

    Why it matters: an emulator is ours — booting it, waking it and
    unlocking it is part of driving it.  A physical device belongs to a
    person who may be holding it, and waking their screen or changing their
    settings is an intrusion into something we do not own.  Every caller
    that acts on a device asks this question first, and it is asked here
    once, so it cannot be answered differently in two places.
    """
    return bool(serial) and serial.startswith("emulator-")


def _is_physical_pin(pin: str) -> bool:
    """True when the pin names real hardware, so no AVD may ever be started for it.

    Starting an AVD for a pinned physical device would silently land on
    emulator-5554 and not match the pin, so this is a hard stop, not a hint.
    """
    return bool(pin) and not is_emulator_serial(pin)


# ── startup lock ──────────────────────────────────────────────────────────────

@contextmanager
def startup_lock(timeout: float):
    """Serialize AVD restarts across concurrent callers.

    Only one caller may restart the AVD at a time. Others block here, then
    re-check readiness once they acquire the lock — the winner may have already
    brought up a device they can use.

    The lock is not inherited by the emulator child: file descriptors are opened
    non-inheritable by default since PEP 446 (``O_CLOEXEC``), and Popen closes
    every descriptor above 2 by default (``close_fds=True``). The emulator
    therefore cannot hold the lock for its lifetime.
    """
    lock_path = _emu.cfg.LOCK_FILE
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
    avd_bytes = _emu.cfg.AVD.encode()
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
    avd_bytes = _emu.cfg.AVD.encode()
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

    avd_dir = _emu.cfg.android_avd_home / f"{_emu.cfg.AVD}.avd"
    for pattern in ("*.lock", ".*.lock"):
        for lock in avd_dir.glob(pattern):
            try:
                shutil.rmtree(lock) if lock.is_dir() else lock.unlink(missing_ok=True)
            except OSError:
                pass


def _tail_log(n: int = 20, level: str = "debug") -> None:
    """Copy the tail of the last emulator log to the debug log.

    Goes to stderr through loguru, never to stdout: the CLI's stdout carries the
    device serial and nothing else, so ``SERIAL=$(ensure-emulator)`` stays valid.

    `level` is a parameter because a start-up crash and a boot timeout are not the
    same event. It used to print at debug in both, and nobody runs with `--verbose`
    in CI — so "emulator exited with rc=1 during start-up" reached the log with no
    cause attached, and the cause is the only thing in the file worth having.
    """
    if not _emu.cfg.log_file.exists():
        if level == "error":
            logger.error(f"(no emulator log at {_emu.cfg.log_file})")
        return
    for line in _emu.cfg.log_file.read_text(errors="replace").splitlines()[-n:]:
        # loguru's `log()` takes UPPERCASE level names and raises ValueError on
        # "error" — which surfaced as the CLI exiting 1 instead of its own
        # EXIT_CRASHED, the one exit code this file is careful to preserve.
        logger.log(level.upper(), f"emulator log | {line}")


# ── AVD start ─────────────────────────────────────────────────────────────────

def start_avd(pin: str) -> int:
    """Start the AVD and wait for it to become ready. Returns an exit code.

    Does not print: stdout belongs to the CLI, which prints the serial once.
    """
    # Before killing anything, check if an emulator for this AVD is already
    # running.  A qemu that is still booting must be given its full budget
    # before we kill it — killing a booting instance guarantees a failed boot,
    # and the process that owns it may be a concurrent run further along.
    if _emu._avd_is_running():
        running_serial = first_ready_device()
        if running_serial and (not pin or pin == running_serial):
            return EXIT_OK
        deadline = time.monotonic() + _emu.cfg.BOOT_TIMEOUT
        while time.monotonic() < deadline:
            if serial := find_ready(pin):
                return EXIT_OK
            time.sleep(2)
        logger.debug("emulator process alive but never became ready; treating as wedged")

    clear_stale_emulator()

    # The AVD name is a claim; `emulator -list-avds` is the fact. Checking it
    # here is the difference between "the emulator did not come up" and "this
    # machine has no AVD called X, and here is the one it has".
    #
    # It is not a nicety. A shard that boots the right binary against the
    # wrong AVD name spends the whole boot budget discovering a typo, and the
    # log says only that the emulator was not ready. That is how a renamed
    # AVD read as a flaky emulator for a whole release cycle.
    known = _device.list_avds()
    if known is None:
        logger.error(
            "the 'emulator' binary is not on PATH; the AVD cannot be listed or started"
        )
        return EXIT_NOT_READY
    if _emu.cfg.AVD not in known:
        available = ", ".join(known) if known else "none"
        logger.error(
            f"AVD '{_emu.cfg.AVD}' does not exist on this machine. Available: {available}"
        )
        return EXIT_NOT_READY

    # Keep log of the previous run (potential crash diagnostic).
    prev = _emu.cfg.log_file.with_name(_emu.cfg.log_file.name + ".prev")
    if _emu.cfg.log_file.exists():
        os.replace(_emu.cfg.log_file, prev)

    # Flags, and why each is here:
    #   -no-snapshot        snapshots are never reused; saving wastes time
    #   -camera-* none     virtual camera's WebRTC thread aborts after ~9 min
    #   -no-audio           removes unrelated host-audio failure mode
    #   ADR 2026-09-29-emulator-launch-recipe.md. Google has deprecated the
    #   `emulator` binary in favour of `android emulator start`, but that command
    #   accepts only --cold/--headless and cannot pass these flags, so the binary
    #   stays the launcher on this host.
    emulator_args = [
        "emulator", "-avd", _emu.cfg.AVD,
        "-no-snapshot",
        "-no-boot-anim",
        "-camera-back", "none",
        "-camera-front", "none",
        "-no-audio",
        *_emu.cfg.EXTRA_FLAGS,
    ]

    # Pin semantics: SERIAL=emulator-5556 → console port 5556.
    if is_emulator_serial(pin):
        emulator_args += ["-port", pin.removeprefix("emulator-")]

    startup_attempts = 3
    for attempt in range(1, startup_attempts + 1):
        # close(), not os.close(fileno()): closing the raw fd leaves the Python
        # file object holding a dead descriptor, and __del__ then raises EBADF at
        # an arbitrary later point (fatal on 3.14+). Popen has already dup'd this
        # fd onto the child's stdout by the time it returns, so closing our copy
        # is both correct and sufficient.
        log_fh = _emu.cfg.log_file.open("wb")
        try:
            proc = _popen(emulator_args, stdout_fd=log_fh.fileno())
        finally:
            log_fh.close()

        deadline = time.monotonic() + _emu.cfg.BOOT_TIMEOUT
        while time.monotonic() < deadline:
            rc = proc.poll()
            if rc is not None:
                log_text = (
                    _emu.cfg.log_file.read_text(errors="replace")
                    if _emu.cfg.log_file.exists() else ""
                )
                if "Failed to get" in log_text and attempt < startup_attempts:
                    logger.warning(
                        f"emulator could not take the AVD write lock (attempt {attempt}); retrying"
                    )
                    time.sleep(5)
                    break

                logger.error(f"emulator exited with rc={rc} during start-up")
                _tail_log(level="error")
                return EXIT_CRASHED

            if find_ready(pin):
                return EXIT_OK

            time.sleep(2)
        else:
            break

    logger.error(
        f"emulator was still not ready after {startup_attempts} attempt(s) "
        f"of {_emu.cfg.BOOT_TIMEOUT}s"
    )
    _tail_log(level="error")
    return EXIT_TIMEOUT


# ── preparation ───────────────────────────────────────────────────────────────

def prepare_device(serial: str) -> None:
    """Wake the device, unlock it, and take the soft keyboard out of the way.

    One meaning, one place.  "Acquired and ready" used to be written three
    times — in the Maestro CLI, in the Maestro runner and in the e2e script —
    and the copy that was missing was the one that mattered most: the AVD that
    ``acquire_device`` had just started after a crash came up with the IME on,
    so every flow measured after that measured a screen half-covered by a
    keyboard.  Six measurements in one session were wrong that way (2026-10-28).

    The first two steps are the project's standard unlock.  The third is the
    setting an AVD with a hardware keyboard needs.

    A physical device is left completely untouched, and this is deliberate:
    the owner of that phone is very likely standing next to it, and waking
    somebody's screen, unlocking their phone and changing their settings
    behind their back is an intrusion into a device we do not own.  Someone
    who pushed their phone for us asked for a push, not for their handset to
    be woken up at the other end.  An emulator is ours — nobody is holding
    it — so there the preparation is exactly what it always was.  The
    distinction is made by :func:`is_emulator_serial`, one function, so it
    cannot be answered differently in two places.

    Nothing here raises: a device that does not answer these three commands
    is still a device, and the caller decides what to do about it.
    """
    if not is_emulator_serial(serial):
        return
    adb_on(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb_on(serial, "shell", "input", "keyevent", "82")
    adb_on(serial, "shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "0")


def _prepared(serial: str) -> Acquisition:
    """Hand back a device that is awake, unlocked, and ready to be measured."""
    prepare_device(serial)
    return Acquisition(serial, EXIT_OK)


# ── acquisition ───────────────────────────────────────────────────────────────

def acquire_device(pin: str) -> Acquisition:
    """Return a ready device, starting the AVD when there is none.

    The single entry point for "give me a device", shared by the ``ensure-emulator``
    CLI and the Maestro runner.  It owns the startup lock, so concurrent callers
    serialise instead of killing each other's emulator.

    Raises nothing expected: the only exception caught is ``TimeoutError`` from
    the lock.  An ``OSError`` opening the lock file is a genuine fault and
    propagates rather than being flattened into an exit code.
    """
    # A pinned physical device is never an AVD problem: validating readiness and
    # failing loudly beats booting an emulator that cannot match the pin.
    if _is_physical_pin(pin):
        if device_is_ready(pin):
            return _prepared(pin)
        logger.error(f"Device '{pin}' is present but not ready.")
        logger.error(adb("devices") or "")
        return Acquisition(None, EXIT_NOT_READY)

    if serial := find_ready(pin):
        return _prepared(serial)

    try:
        with startup_lock(_emu.cfg.lock_timeout):
            # Re-check inside the lock: the previous holder may have booted a
            # device we can simply use.
            if serial := find_ready(pin):
                return _prepared(serial)
            rc = start_avd(pin)
    except TimeoutError:
        # The holder was probably mid-boot and succeeds at about this moment.
        # One last look before reporting a lock failure, or a healthy run loses
        # the race and reports EXIT_LOCK for a device that is already up.
        if serial := find_ready(pin):
            return _prepared(serial)
        logger.error("Timed out waiting for another ensure-emulator run.")
        return Acquisition(None, EXIT_LOCK)

    if rc != EXIT_OK:
        result = probe_device_ready(pin) if pin else None
        if result is not None and result.failed_probe:
            logger.debug(f"readiness probe '{result.failed_probe}' did not pass")
        return Acquisition(None, rc)

    serial = find_ready(pin)
    return _prepared(serial) if serial else Acquisition(None, EXIT_OK)


def ensure_device(serial: str) -> str | None:
    """Acquire a device for `serial`, returning its serial, or None.

    Acquires — this starts the AVD when nothing ready is attached.  That is the
    point of the name; it used to only re-check, which made every caller that
    relied on it fail exactly when recovery was needed
    (ADR 2026-10-13-ensure-device-acquires).
    """
    return acquire_device(serial).serial
#!/usr/bin/env python3
"""Ensure a usable Android device is available; print its serial on stdout.

The emulator on this host crashes intermittently (gfxstream render thread segfault
in TextureResize when the guest creates a new ColorBuffer). No renderer configuration
avoids it, so the fix is recovery: if a ready device exists, print its serial;
otherwise start the AVD, wait for boot, print the serial.

Usage:
    ensure-emulator.py                          # start the AVD if needed
    ensure-emulator.py --avd Pixel_7           # a different AVD
    ensure-emulator.py --timeout 420           # custom boot timeout
    ensure-emulator.py --serial emulator-5556  # pin; dead emulator restarted on that port
    ensure-emulator.py --serial <physical>     # pin physical; never start AVD
    ensure-emulator.py -v                      # verbose stderr output

Exit codes: 0 ok, 1 not ready / tool missing, 2 emulator died during start-up,
            3 boot timeout, 4 lock timeout.
"""
from __future__ import annotations

import argparse
import fcntl
import os
import shlex
import shutil
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path

EXIT_OK, EXIT_NOT_READY, EXIT_CRASHED, EXIT_TIMEOUT, EXIT_LOCK = 0, 1, 2, 3, 4

# ── env ─────────────────────────────────────────────────────────────────────────
AVD = os.environ.get("AVD", "Medium_Phone")
PINNED = os.environ.get("SERIAL", "")
BOOT_TIMEOUT = int(os.environ.get("EMULATOR_TIMEOUT", "300"))
EXTRA_FLAGS = shlex.split(os.environ.get("EMULATOR_EXTRA_FLAGS", ""))
LOG_FILE = Path(os.environ.get("EMULATOR_LOG", "/tmp/singularity-emulator.log"))
LOCK_FILE = Path(os.environ.get("TMPDIR", "/tmp")) / "ensure-emulator.lock"
ANDROID_AVD_HOME = Path(os.environ.get("ANDROID_AVD_HOME",
                                       Path.home() / ".android" / "avd"))
VERBOSE = False


# ── output ────────────────────────────────────────────────────────────────────
def _ansi(code: str) -> str:
    return f"\033[{code}m" if sys.stderr.isatty() else ""


RED = _ansi("0;31")
YELLOW = _ansi("0;33")
GREEN = _ansi("0;32")
NC = _ansi("0")


def say(msg: str) -> None:
    print(msg, file=sys.stderr)


def warn(msg: str) -> None:
    say(f"{YELLOW}{msg}{NC}")


def error(msg: str) -> None:
    say(f"{RED}{msg}{NC}")


def note(msg: str) -> None:
    say(msg)


def verbose(msg: str) -> None:
    if VERBOSE:
        say(f"{GREEN}[verbose] {msg}{NC}")


# ── subprocess helpers ─────────────────────────────────────────────────────────
def run(args: list[str], *, timeout: float = 5,
        check: bool = False) -> subprocess.CompletedProcess:
    """Run a command, return the CompletedProcess."""
    return subprocess.run(
        args,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=check,
        text=True,
    )


def try_run(args: list[str], *, timeout: float = 5) -> subprocess.CompletedProcess | None:
    """Run a command, return None on non-zero exit or timeout."""
    try:
        return subprocess.run(
            args,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            text=True,
        )
    except (subprocess.TimeoutExpired, OSError):
        return None


def popen(args: list[str], stdout_fd: int | None = None) -> subprocess.Popen:
    """Start a process in the background; stdout goes to stdout_fd if given."""
    devnull = None
    if stdout_fd is None:
        devnull = subprocess.DEVNULL
    return subprocess.Popen(
        args,
        stdout=stdout_fd if stdout_fd is not None else devnull,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )


# ── adb ────────────────────────────────────────────────────────────────────────
def adb(*args: str, timeout: float = 5) -> str | None:
    """Run `adb <args>`; return stdout on success, None on non-zero exit or timeout."""
    cp = try_run(["adb", *args], timeout=timeout)
    return cp.stdout.strip() if cp and cp.returncode == 0 else None


def device_is_ready(serial: str) -> bool:
    """True when adb knows the device, it is booted, and key services answer.

    Checks three layers:
      1. adb get-state == device
      2. sys.boot_completed == 1  (Android boot finished)
      3. pm path android         (package manager is responsive)
      4. dumpsys SurfaceFlinger  (graphics service is alive; catches a
         gfxstream-segfault scenario where boot_completed=1 but the render
         thread is dead and SurfaceFlinger is hung)
    """
    if not serial:
        return False
    if adb("-s", serial, "get-state") != "device":
        return False
    if adb("-s", serial, "shell", "getprop", "sys.boot_completed") != "1":
        return False
    # pm path android succeeds with "package:<path>" when packages are installed.
    # A booted-but-still-indexing emulator returns empty or times out.
    pm_out = adb("-s", serial, "shell", "pm", "path", "android", timeout=10) or ""
    if not pm_out.startswith("package:"):
        return False
    # SurfaceFlinger must be responsive. A dead render thread leaves it hung.
    sf_out = adb("-s", serial, "shell", "dumpsys", "SurfaceFlinger",
                 timeout=10) or ""
    # If the output is empty or the command timed out, SurfaceFlinger is not healthy.
    if not sf_out or "SurfaceFlinger" not in sf_out:
        return False
    return True


def first_ready_device() -> str | None:
    for line in (adb("devices") or "").splitlines()[1:]:
        parts = line.split()
        if len(parts) == 2 and parts[1] == "device" and device_is_ready(parts[0]):
            return parts[0]
    return None


def find_ready(pin: str) -> str | None:
    """Return pin if it is ready; otherwise find any ready device (when pin is empty)."""
    if pin:
        return pin if device_is_ready(pin) else None
    return first_ready_device()


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
    lock_path = LOCK_FILE
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
      2. No ERE compatibility concerns (the pattern is interpreted as plain bytes,
         not as a regular expression, so there are no \\S or (?:...) issues).
    """
    avd_bytes = AVD.encode()
    try:
        for pid in os.listdir("/proc"):
            if not pid.isdigit():
                continue
            try:
                cmdline = Path(f"/proc/{pid}/cmdline").read_bytes()
                if b"qemu-system" in cmdline and b"-avd" in cmdline and avd_bytes in cmdline:
                    return True
            except OSError:
                # Process vanished between listdir and read — ignore.
                pass
    except OSError:
        pass
    return False


def _kill_avd() -> None:
    """Send SIGKILL to any qemu process for our AVD; do not wait for exit."""
    avd_bytes = AVD.encode()
    try:
        for pid in os.listdir("/proc"):
            if not pid.isdigit():
                continue
            try:
                cmdline = Path(f"/proc/{pid}/cmdline").read_bytes()
                if b"qemu-system" in cmdline and b"-avd" in cmdline and avd_bytes in cmdline:
                    os.kill(int(pid), 9)
            except (OSError, ProcessLookupError):
                # Process already gone — nothing to kill.
                pass
    except OSError:
        pass


def clear_stale_emulator() -> None:
    """Kill any stale emulator process for AVD and remove its lock files."""
    _kill_avd()

    # Lock files may be files or directories (.AndroidStudio.*).
    avd_dir = ANDROID_AVD_HOME / f"{AVD}.avd"
    for pattern in ("*.lock", ".*.lock"):
        for lock in avd_dir.glob(pattern):
            try:
                shutil.rmtree(lock) if lock.is_dir() else lock.unlink(missing_ok=True)
            except OSError:
                pass


def _tail_log(n: int = 20) -> None:
    if LOG_FILE.exists():
        for line in LOG_FILE.read_text(errors="replace").splitlines()[-n:]:
            say(line)


def start_avd(pin: str) -> int:
    # Before killing anything, check if an emulator for this AVD is already running
    # and is healthy. If so, and we're not pinned to a different port, use it.
    if _avd_is_running():
        running_serial = first_ready_device()
        if running_serial and device_is_ready(running_serial):
            if pin and pin.startswith("emulator-"):
                expected_port = pin.removeprefix("emulator-")
                if f"emulator-{expected_port}" == running_serial:
                    note(f"Using already-running emulator: {running_serial}")
                    print(running_serial)
                    return EXIT_OK
                warn(f"Running emulator is on {running_serial} but pin wants {pin}; "
                     "restarting on correct port.")
                clear_stale_emulator()
            else:
                note(f"Using already-running emulator: {running_serial}")
                print(running_serial)
                return EXIT_OK

    warn(f"No ready device; starting AVD '{AVD}'.")
    clear_stale_emulator()

    # Keep log of the previous run (potential crash diagnostic).
    prev = LOG_FILE.with_name(LOG_FILE.name + ".prev")
    if LOG_FILE.exists():
        os.replace(LOG_FILE, prev)

    # Flags, and why each is here:
    #   -no-snapshot        snapshots are never reused; saving them wastes time
    #   -camera-* none     virtual camera's WebRTC thread aborts after ~9 min uptime
    #   -no-audio          removes an unrelated host-audio failure mode
    # Ruled out by experiment on this host:
    #   -no-window         SIGSEGV before window is created
    #   -gpu software/swangle  dies during startup on API 36
    emulator_args = [
        "emulator", "-avd", AVD,
        "-no-snapshot",
        "-no-boot-anim",
        "-camera-back", "none",
        "-camera-front", "none",
        "-no-audio",
        *EXTRA_FLAGS,
    ]

    # Pin semantics: SERIAL=emulator-5556 means we want the device on console
    # port 5556. The emulator binary accepts -port <N> (not the console port).
    if pin and pin.startswith("emulator-"):
        emulator_args += ["-port", pin.removeprefix("emulator-")]

    # After killing a qemu process, its qcow2 image may be held open by the kernel
    # for up to ~20 s (the emulator's own graceful-shutdown timer). Retry on
    # "Failed to get write lock" up to 3 times, waiting 5 s between attempts.
    startup_attempts = 3
    for attempt in range(1, startup_attempts + 1):
        log_fh = LOG_FILE.open("wb")
        proc = popen(emulator_args, stdout_fd=log_fh.fileno())
        # log_fh is now writing to the file via the fd; close our handle
        # but the subprocess still holds its own copy of the fd.
        os.close(log_fh.fileno())

        deadline = time.monotonic() + BOOT_TIMEOUT
        while time.monotonic() < deadline:
            rc = proc.poll()
            if rc is not None:
                # Emulator exited. Check the log for a lock conflict.
                log_text = LOG_FILE.read_text(errors="replace") if LOG_FILE.exists() else ""
                if "Failed to get" in log_text and attempt < startup_attempts:
                    warn(f"Emulator failed to start (lock conflict, attempt {attempt}/"
                         f"{startup_attempts}); retrying in 5 s …")
                    time.sleep(5)
                    break  # exit inner while; retry outer for
                error(f"Emulator exited during start-up (rc={rc}).")
                warn(f"See {LOG_FILE}")
                _tail_log()
                return EXIT_CRASHED

            if serial := find_ready(pin):
                if pin and serial != pin:
                    warn(f"Booted device is '{serial}' but pin requires '{pin}'; waiting …")
                    time.sleep(2)
                    continue
                note(f"Device ready: {serial}")
                print(serial)
                return EXIT_OK

            time.sleep(2)
        else:
            # deadline reached — break out of retry loop
            break

    error(f"Emulator did not finish booting within {BOOT_TIMEOUT}s.")
    warn(f"See {LOG_FILE}")
    return EXIT_TIMEOUT


# ── main ──────────────────────────────────────────────────────────────────────
def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Ensure a usable Android device is available; print its serial on stdout.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--avd", "-a", dest="avd", default=AVD,
                   help=f"AVD name (default: {AVD}, also set via AVD env var)")
    p.add_argument("--serial", "-s", dest="serial", default=PINNED,
                   help="pin a specific device serial (also set via SERIAL env var)")
    p.add_argument("--timeout", "-t", dest="timeout", type=int, default=BOOT_TIMEOUT,
                   help=f"boot timeout in seconds (default: {BOOT_TIMEOUT}, "
                        f"also set via EMULATOR_TIMEOUT env var)")
    p.add_argument("--verbose", "-v", action="store_true",
                   help="print detailed progress to stderr")
    return p


def main(argv: list[str] | None = None) -> int:
    global AVD, PINNED, BOOT_TIMEOUT, VERBOSE

    args = _build_parser().parse_args(argv)
    AVD = args.avd
    PINNED = args.serial
    BOOT_TIMEOUT = args.timeout
    VERBOSE = args.verbose

    verbose(f"avd={AVD} pinned={PINNED} timeout={BOOT_TIMEOUT}")

    if not Path("/usr/bin/emulator").exists() and not shutil.which("emulator"):
        error("'emulator' binary not found in PATH.")
        return EXIT_NOT_READY

    # DISPLAY is required for the emulator window; fail fast if absent.
    if not os.environ.get("DISPLAY") and not os.environ.get("WAYLAND_DISPLAY"):
        warn("DISPLAY / WAYLAND_DISPLAY is not set; the emulator may fail to start.")

    # Physical device pin: never start an AVD — the serial must come from adb.
    if PINNED and not PINNED.startswith("emulator-"):
        if device_is_ready(PINNED):
            print(PINNED)
            return EXIT_OK
        error(f"Device '{PINNED}' is present but not ready.")
        say(adb("devices") or "")
        return EXIT_NOT_READY

    # Fast path: something is already up.
    if serial := find_ready(PINNED):
        verbose(f"found ready device: {serial}")
        print(serial)
        return EXIT_OK

    # Emulator pin set but not ready: the AVD crashed and needs restarting.
    if PINNED:
        warn(f"Pinned emulator '{PINNED}' is not ready; restarting the AVD.")

    try:
        with startup_lock(BOOT_TIMEOUT):
            # Re-check inside the lock: another caller may have just finished booting.
            if serial := find_ready(PINNED):
                verbose(f"device ready after acquiring lock: {serial}")
                print(serial)
                return EXIT_OK
            return start_avd(PINNED)
    except TimeoutError:
        error("Timed out waiting for another ensure-emulator run.")
        return EXIT_LOCK


if __name__ == "__main__":
    try:
        sys.exit(main())
    except FileNotFoundError as e:
        error(f"'{e.filename}' not found in PATH.")
        sys.exit(EXIT_NOT_READY)

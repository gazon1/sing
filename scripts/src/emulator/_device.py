"""emulator._device — adb wrappers and device readiness detection."""
from __future__ import annotations

import subprocess
from typing import Optional

from emulator._types import cfg


# ── subprocess helpers ─────────────────────────────────────────────────────────

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
    """Start a background process; stdout goes to stdout_fd if given."""
    devnull = None
    if stdout_fd is None:
        devnull = subprocess.DEVNULL
    return subprocess.Popen(
        args,
        stdout=stdout_fd if stdout_fd is not None else devnull,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )


# ── adb primitives ─────────────────────────────────────────────────────────────

def adb(*args: str, timeout: float = 5) -> str | None:
    """Run `adb <args>`; return stdout on success, None on non-zero exit or timeout."""
    cp = try_run(["adb", *args], timeout=timeout)
    return cp.stdout.strip() if cp and cp.returncode == 0 else None


def device_is_ready(serial: str) -> bool:
    """True when adb knows the device, it is booted, and key services answer.

    Checks four layers:
      1. adb get-state == device
      2. sys.boot_completed == 1  (Android boot finished)
      3. pm path android          (package manager is responsive)
      4. dumpsys SurfaceFlinger   (graphics service is alive;
           catches gfxstream-segfault where boot_completed=1 but render thread is dead)
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
    # SurfaceFlinger must be responsive.
    sf_out = adb("-s", serial, "shell", "dumpsys", "SurfaceFlinger", timeout=10) or ""
    if not sf_out or "SurfaceFlinger" not in sf_out:
        return False
    return True


def first_ready_device() -> str | None:
    """Return the first device adb lists that is ready, or None."""
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


def device_alive(serial: str) -> bool:
    """True when adb can reach the device (get-state == device), without full boot checks."""
    return adb("-s", serial, "get-state") == "device"


def ensure_device(serial: str) -> str | None:
    """Return serial if the device is fully ready, otherwise None."""
    return serial if device_is_ready(serial) else None

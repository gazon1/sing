"""maestro._device — adb wrappers, device readiness, and ADB lifecycle.

Custom logic lives here; trivial re-wraps of emulator._device are inlined at
the call site (see emulator.maestro._types._default_device_deps).
"""
from __future__ import annotations

import re
import subprocess
import time

from emulator._device import (
    adb as _parent_adb,
)
from emulator._device import (
    adb_on as _parent_adb_on,
)
from emulator._device import (
    device_alive as _parent_device_alive,
)
from emulator.maestro._subprocess import run as _subprocess_run
from emulator.maestro._types import APP_ID, GW, ROOT

# Re-export parent functions for backward compatibility.
adb = _parent_adb
adb_on = _parent_adb_on
device_alive = _parent_device_alive


def adb_check(*args: str) -> bool:
    """Run adb; return True on exit 0, suppress all output."""
    from emulator.maestro._subprocess import try_run
    cp = try_run(["adb", *args], timeout=5)
    return cp is not None and cp.returncode == 0


def device_model(serial: str) -> str:
    return (adb_on(serial, "shell", "getprop", "ro.product.model") or "").strip()


def reset_adb_server() -> None:
    adb("kill-server")
    time.sleep(2)
    adb("start-server")
    time.sleep(2)


def maestro_saw_device_failure(stderr: str) -> bool:
    """Return True if Maestro's output indicates a device-level failure (retryable)."""
    patterns = [
        "0 devices connected",
        "Not enough devices",
        r"device '[^']*' not found",
        "RawAdbSocket",
        "AdbSocketFactory",
        "AdbServer",
        "Device server died",
        "UNAVAILABLE",
        "Connection refused",
    ]
    for pat in patterns:
        if re.search(pat, stderr, re.IGNORECASE):
            return True
    return False


def gradle_install_debug(serial: str) -> subprocess.CompletedProcess:
    """Run `./gw :androidApp:installDebug` for the given device serial."""
    return _subprocess_run(
        [str(GW), ":androidApp:installDebug", f"-Pandroid.device={serial}", "--quiet"],
        cwd=ROOT,
    )


def wait_for_app_resumed(serial: str, timeout: int = 20) -> bool:
    """Poll dumpsys activity until the main activity is top-resumed."""
    waited = 0
    while waited < timeout:
        out = adb_on(serial, "shell", "dumpsys", "activity", "activities", timeout=5) or ""
        if f"topResumedActivity=.{APP_ID}/" in out or f"topResumedActivity={APP_ID}/" in out:
            return True
        time.sleep(1)
        waited += 1
    return False

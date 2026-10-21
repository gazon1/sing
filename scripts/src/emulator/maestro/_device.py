"""maestro._device — adb wrappers, device readiness, and ADB lifecycle.

Custom logic lives here; trivial re-wraps of emulator._device are inlined at
the call site (see emulator.maestro._types._default_device_deps).
"""
from __future__ import annotations

import re
import subprocess
import time
from pathlib import Path

from emulator._device import adb, adb_on
from emulator.maestro._subprocess import run as _subprocess_run
from emulator.maestro._types import APP_ID, GW, ROOT


def device_model(serial: str) -> str:
    return (adb_on(serial, "shell", "getprop", "ro.product.model") or "").strip()


def reset_adb_server() -> None:
    adb("kill-server")
    time.sleep(2)
    adb("start-server")
    time.sleep(2)


#: Signatures of a device-level failure in Maestro's *process output*: the
#: device died, not the flow.
#:
#: They appear in two places, and a runner that reads only one of them miscounts
#: the run. The process's stderr usually stays empty, while the flow's JUnit
#: report carries the gRPC stack of a device that died mid-flow — so a runner
#: that reads stderr only counts a dead device as a broken flow, and a broken
#: flow is not retried.
_DEVICE_FAILURE_PATTERNS = (
    "0 devices connected",
    "Not enough devices",
    r"device '[^']*' not found",
    "RawAdbSocket",
    "AdbSocketFactory",
    "AdbServer",
    "Device server died",
    "UNAVAILABLE",
    "Connection refused",
)

#: The same failure, seen from inside the JUnit report.
#:
#: This set is deliberately tighter than the process-output one. The report also
#: carries the app's own text — an assertion message can legitimately say
#: "unavailable" — and a loose pattern would read that as a dead device and
#: retry a flow that simply failed its assertion. These names are Maestro's
#: own classes and cannot appear in an assertion about the app.
_JUNIT_DEVICE_FAILURE_PATTERNS = (
    "DeviceServerDiedException",
    "Device server died",
    "StatusRuntimeException: UNAVAILABLE",
    "maestro.android.AndroidDeviceConnection",
)


def _matches(text: str, patterns: tuple[str, ...]) -> bool:
    """Whether `text` carries any of `patterns`."""
    return any(re.search(pat, text, re.IGNORECASE) for pat in patterns)


def maestro_saw_device_failure(stderr: str) -> bool:
    """Return True if Maestro's output indicates a device-level failure (retryable)."""
    return _matches(stderr, _DEVICE_FAILURE_PATTERNS)


def junit_reports_device_failure(report: Path) -> bool:
    """Whether the flow's JUnit report records a device-level failure.

    This is where the device's death is actually written down: the flow never
    ran, so the report's ``<failure>`` element carries Maestro's gRPC stack
    (``DeviceServerDiedException … UNAVAILABLE``) instead of an assertion about
    the app. A report that holds an assertion failure is a broken flow, and the
    two must not be counted the same way.
    """
    try:
        text = report.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    return _matches(text, _JUNIT_DEVICE_FAILURE_PATTERNS)


def gradle_install_debug(serial: str) -> subprocess.CompletedProcess:
    """Run `./gw :androidApp:installDebug` for the given device serial.

    The build carries ``-PTEST_AUTH_BYPASS=true`` so the installed APK starts
    signed in. Without it the app opens on the "Connect your server" screen and
    every assertion about the shell is made against a screen in front of the
    one it describes — the flows then fail on ``nav_tab_today`` after a 45-second
    timeout each, which looks like a broken suite and is really a wrong build.
    The E2E workflow assembles its APK with the same property, so this makes the
    local run measure what CI measures.
    """
    return _subprocess_run(
        [
            str(GW), ":androidApp:installDebug",
            f"-Pandroid.device={serial}",
            "-PTEST_AUTH_BYPASS=true",
            "--quiet",
        ],
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

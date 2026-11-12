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


def device_api_level(serial: str) -> int | None:
    """The device's API level, or None when it does not answer.

    The API level is part of the environment a measurement was taken in. A
    number measured on API 35 and a number measured on API 36 are two
    different quantities, and the floor belongs to one of them.
    """
    out = (adb_on(serial, "shell", "getprop", "ro.build.version.sdk") or "").strip()
    try:
        return int(out)
    except ValueError:
        return None


def app_installed(serial: str) -> bool:
    """Whether the app under test is on the device.

    A run can lose the app without losing the device: the package is removed
    and the device keeps answering adb, perfectly. Every flow after that fails
    in four seconds with "Package com.singularity.todo is not installed", which
    is not a statement about the app — it is the absence of the app, and a
    report that counts it as a failing flow is a report about the run.

    Measured on a physical phone (2026-10-28): the package was removed at
    15:59 and the runner carried on, so 31 of 94 flows were reported as
    failures when they had not been measured at all.
    """
    out = adb_on(serial, "shell", "pm", "list", "packages", APP_ID) or ""
    return any(line.strip() == f"package:{APP_ID}" for line in out.splitlines())


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


#: The two shapes the system prints the resumed activity in. The first is the
#: global answer, the second is what an older Android version prints.
_RESUMED_LINE_MARKERS = ("ResumedActivity:", "topResumedActivity=")


def resumed_activity_line(out: str | None) -> str:
    """The line the system reports as the resumed activity, or "".

    `dumpsys activity activities` prints the resumed activity in one of two
    shapes, and which one you get depends on the Android version:

        topResumedActivity=ActivityRecord{8f3 u0 com.singularity.todo/.MainActivity t42}
        ResumedActivity: ActivityRecord{5a1b95b u0 com.singularity.todo/.MainActivity t3273 d0}

    The second one is what an API 35 phone prints, and it has no
    `topResumedActivity` in it at all. A matcher that looks for
    `topResumedActivity=` therefore never matches on a modern device, and the
    wait it guards turns into a fixed sleep that always ends in "not resumed".

    The activity id is *inside* the record, not right after the `=`: that is the
    second way this line is read wrong.

    The dump also holds a *task list* with a `Resumed:` line per task, and the
    header above it ("Resumed activities in task display areas"). That one
    spells itself differently — `Resumed:` — so the markers below do not match
    it, and the answer is the line that names the resumed activity outright.
    """
    if not out:
        return ""
    lines = [line.strip() for line in out.splitlines()]
    for marker in _RESUMED_LINE_MARKERS:
        for stripped in lines:
            if marker in stripped:
                return stripped
    return ""


def wait_for_app_resumed(serial: str, timeout: int = 20) -> bool:
    """Poll dumpsys activity until the app is in front.

    "The app is in front" is a statement about the *package*: the debug seed
    activity — which the runner drives the content reset through — belongs to
    the same app id as the main activity, so this waiter answers true for it
    too. That is what makes this answer too weak to guard the content reset,
    and why :func:`wait_for_main_activity_resumed` exists.
    """
    waited = 0
    while waited < timeout:
        out = adb_on(serial, "shell", "dumpsys", "activity", "activities", timeout=5) or ""
        line = resumed_activity_line(out)
        if line and f"{APP_ID}/" in line:
            return True
        time.sleep(1)
        waited += 1
    return False


def wait_for_main_activity_resumed(serial: str, timeout: int = 30) -> bool:
    """Poll dumpsys activity until the *main* activity is top-resumed.

    ``wait_for_app_resumed`` answers "is the app in front", which the debug
    seed activity also satisfies — and the seed activity is exactly what runs
    the content reset. So "the app is in front" is no proof that the reset is
    done: the reset runs on the seed activity's own thread, and the runner's
    next step (a force-stop, for the session reset) can kill it half way
    through. The tables the reset empties last are then simply not empty, and
    the next flow sees the previous flow's rows.

    The main activity is the signal that the reset thread has *ended*: the seed
    activity brings the main activity up in the ``finally`` of its seeding
    thread, so the main activity is in front only after that thread returned.
    It says nothing about whether the reset itself succeeded — a seeding thread
    that threw still brings the app up — so this is "the reset is over", not
    "the reset worked".

    The wait reads the resumed activity the way the system prints it — see
    ``resumed_activity_line``. The older shape of that line
    (`topResumedActivity=ActivityRecord{…}`) is read too, so the wait works on
    the Android versions that print it.
    """
    waited = 0
    while waited < timeout:
        out = adb_on(serial, "shell", "dumpsys", "activity", "activities", timeout=5) or ""
        line = resumed_activity_line(out)
        if line and (f"{APP_ID}/.MainActivity" in line or f"{APP_ID}.MainActivity" in line):
            return True
        time.sleep(1)
        waited += 1
    return False

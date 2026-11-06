#!/usr/bin/env python3
"""android-instrumentation.py — run the instrumented suite on a connected device.

Why
---
The instrumented tier is the one part of the local loop that needs hardware.
It is therefore optional and its failure is not the loop's verdict — but "the
device was not there" and "the suite ran and failed" have to be told apart,
or a green run hides a red tier.

So this script does the two things that were tangled in the shell: it decides
*whether* there is a device to run on, and it runs the Gradle task on it. The
decision is a pure function of `adb devices` output (`pick_serial`), and that
is the part worth testing — a device that is `offline` or `unauthorized` is
not a device to run on, and a script that picks it anyway produces a red tier
for a reason that has nothing to do with the tier.

The floor of the instrumented tier is checked by its own step, where the
report is: see `scripts/check-instrumented-test-floor.py`.

Usage
-----
    python3 scripts/android-instrumentation.py [:androidApp:connectedDebugAndroidTest]

Environment:
    SKIP_ADB=1   skip the run entirely (no adb is called at all)

Exit code: always 0. The run is optional; its failure is reported, not
enforced — the device lane decides what a failed instrumentation means.
"""

from __future__ import annotations

import os
import pathlib
import shutil
import subprocess
import sys

#: The repository root: this script lives in `scripts/`.
ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The Gradle task that runs the instrumented suite on a device.
DEFAULT_TASK = ":androidApp:connectedDebugAndroidTest"

#: Where the instrumented run writes its report.
REPORT_DIR = ROOT / "androidApp" / "build" / "outputs" / "androidTest-results" / "connected"

#: Where the serial of the device that was used is recorded, so the next step
#: of the same lane (the Maestro flows) runs on the same device instead of
#: picking one of its own.
SERIAL_FILE = ROOT / "build" / "diagnostics" / "device-serial.txt"


def pick_serial(devices_output: str) -> str | None:
    """The serial of the first attached device, or `None` if there is none.

    `adb devices` prints a header line and one line per device, whose second
    column is its state. Only `device` means the device is ready for a run:
    `offline`, `unauthorized` and `unbootdevice` are devices that are there
    and cannot be used, and picking one of them turns a missing device into a
    failed tier.
    """
    for line in devices_output.splitlines():
        parts = line.split()
        if len(parts) < 2:
            continue
        serial, state = parts[0], parts[1]
        if state != "device":
            continue
        if serial == "List":
            # The header line: `List of devices attached`.
            continue
        return serial
    return None


def adb_devices() -> str:
    """What `adb devices` says, or an empty string when adb is not there."""
    try:
        completed = subprocess.run(
            ["adb", "devices"],
            capture_output=True,
            text=True,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    return completed.stdout


def run_gradle(task: str, serial: str) -> int:
    """The Gradle task on that device; its exit code is returned, not raised."""
    command = [
        str(ROOT / "gw"),
        task,
        f"-Pandroid.testInstrumentationRunnerArguments.device={serial}",
        "--quiet",
    ]
    try:
        completed = subprocess.run(command, cwd=str(ROOT), check=False)
    except (OSError, subprocess.SubprocessError) as exc:
        print(f"could not run {task}: {exc}")
        return 1
    return completed.returncode


def main(argv: list[str]) -> int:
    if os.environ.get("SKIP_ADB") == "1":
        print("SKIP_ADB=1 — the instrumented run is skipped.")
        return 0

    task = argv[0] if argv else DEFAULT_TASK

    devices = adb_devices()
    if not devices:
        print("adb is not available — the instrumented run is skipped.")
        return 0

    serial = pick_serial(devices)
    if serial is None:
        print("no attached device — the instrumented run is skipped.")
        return 0

    print(f"Running on device: {serial}")
    # The old report goes first: the floor step reads the report directory to
    # decide whether there is a run to price, and a report left by an earlier
    # run would answer for a run that never happened.
    shutil.rmtree(REPORT_DIR, ignore_errors=True)
    code = run_gradle(task, serial)

    SERIAL_FILE.parent.mkdir(parents=True, exist_ok=True)
    SERIAL_FILE.write_text(f"{serial}\n", encoding="utf-8")

    if code != 0:
        # The tier failed, but the failure is the device lane's business, not
        # this script's: the floor step that follows reads the report and says
        # what actually went wrong.
        print(f"{task} failed on {serial} (exit {code}) — see the report above.")
        return 0

    print("Android instrumentation completed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

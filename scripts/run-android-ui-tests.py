#!/usr/bin/env python3
"""Run Android UI instrumentation tests on a connected device.

Usage:
    run-android-ui-tests.py                    # ensure a device, starting AVD if needed
    run-android-ui-tests.py <serial>          # pin a specific device
    SERIAL=emulator-5556 run-android-ui-tests.py  # also pins via env var

Device selection is delegated to scripts/ensure-emulator.py, the repo's single
recovery primitive. Hand-rolled discovery here could only ever find a device
that was already up — every emulator crash would surface as "no adb device
found". The message says "connect a device" when the actual remedy is one
command the runner already knows how to run.
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # works from any cwd
SCRIPT_DIR = ROOT / "scripts"
ENSURE_EMULATOR = SCRIPT_DIR / "ensure-emulator.py"
GRADLEW = ROOT / "gradlew"
APP_ID = "com.singularity.todo"


# ── APK provenance ─────────────────────────────────────────────────────────────
def apk_sha_from_device(serial: str) -> str | None:
    """Read the +g<sha> suffix from versionName on the device."""
    out = adb("-s", serial, "shell", "dumpsys", "package", APP_ID, timeout=10) or ""
    for line in out.splitlines():
        if "versionName=" in line:
            version = line.split("versionName=", 1)[1].split()[0].strip()
            if "+" in version:
                sha = version.split("+", 1)[1].strip()
                return sha.lstrip("g")
    return None


def git_sha() -> str:
    """Short SHA of the current checkout."""
    cp = try_run(["git", "-C", str(ROOT), "rev-parse", "--short=12", "HEAD"])
    return cp.stdout.strip() if cp and cp.returncode == 0 else "unknown"

# Exit codes
EXIT_PASS = 0
EXIT_FAIL = 1
EXIT_BUILD_FAILED = 2
EXIT_DEVICE_UNAVAILABLE = 3

# ── colour output ──────────────────────────────────────────────────────────────
_is_tty = sys.stderr.isatty()
def _col(code: str, s: str) -> str:
    return f"\033[{code}m{s}\033[0m" if _is_tty else s

RED    = lambda s: _col("0;31", s)
GREEN  = lambda s: _col("0;32", s)
YELLOW = lambda s: _col("0;33", s)


# ── subprocess helpers ─────────────────────────────────────────────────────────
def run(args: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(args, text=True, **kwargs)

def try_run(args: list[str], **kwargs) -> subprocess.CompletedProcess | None:
    try:
        return run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)
    except OSError:
        return None


# ── device helpers ─────────────────────────────────────────────────────────────
def adb(*args: str, timeout: float = 5) -> str | None:
    cp = try_run(["adb", *args], timeout=timeout)
    return cp.stdout.strip() if cp and cp.returncode == 0 else None

def device_alive(serial: str) -> bool:
    """Both checks: adb get-state == device AND shell echo succeeds."""
    state_ok = adb("-s", serial, "get-state") == "device"
    ping_ok = adb("-s", serial, "shell", "echo", "ping") == "ping"
    return state_ok and ping_ok

def device_model(serial: str) -> str:
    return (adb("-s", serial, "shell", "getprop", "ro.product.model") or "").strip()

def ensure_device(serial: str | None) -> str | None:
    """Return serial of a ready device, starting the AVD if needed."""
    env = {**os.environ}
    if serial:
        env["SERIAL"] = serial
    try:
        result = subprocess.run(
            [sys.executable, str(ENSURE_EMULATOR)],
            cwd=ROOT,
            env=env,
            text=True,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        return result.stdout.strip().splitlines()[-1]
    except subprocess.CalledProcessError as e:
        return None


def gradle(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [str(GRADLEW), *args],
        cwd=ROOT,
        text=True,
        check=False,
    )


# ── failure artifacts ──────────────────────────────────────────────────────────
def capture_logcat(serial: str, artifact_dir: Path) -> None:
    """Dump logcat to artifact_dir/logcat.txt. Best-effort."""
    if not device_alive(serial):
        return
    artifact_dir.mkdir(parents=True, exist_ok=True)
    logcat_file = artifact_dir / "logcat.txt"
    logcat_out = adb("-s", serial, "shell", "logcat", "-d")
    if logcat_out:
        logcat_file.write_text(logcat_out)
        print(f"  {YELLOW}Logcat: {logcat_file}{_col('0', '')}", flush=True)


# ── main ──────────────────────────────────────────────────────────────────────
def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("serial", nargs="?", default=None,
                        help="pin a specific device serial (also set via SERIAL env var)")
    parser.add_argument("--artifact-dir", default=None,
                        help="Directory for failure artifacts (default: build/android-ui-results)")
    args = parser.parse_args(argv)

    serial: str | None = args.serial or os.environ.get("SERIAL")
    serial = serial or None  # normalize "" → None

    # ── 1. Acquire device ───────────────────────────────────────────────────
    print(f"{YELLOW}Acquiring device...{_col('0', '')}", flush=True)
    device = ensure_device(serial)
    if not device:
        print(f"{RED}ensure-emulator.py could not produce a ready device.{_col('0', '')}", flush=True)
        out = adb("devices") or ""
        print(f"{RED}adb devices:{_col('0', '')}\n{out}", flush=True)
        return EXIT_DEVICE_UNAVAILABLE

    # Verify device is responsive (adb can reach it).
    if not device_alive(device):
        print(f"{RED}Device {device} is not responsive (adb get-state failed).{_col('0', '')}", flush=True)
        return EXIT_DEVICE_UNAVAILABLE

    model = device_model(device)
    print(f"{GREEN}Device: {device} ({model}){_col('0', '')}", flush=True)

    # ── 2. Wake + unlock ────────────────────────────────────────────────────
    adb("-s", device, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("-s", device, "shell", "input", "keyevent", "KEYCODE_MENU")  # unlock

    # ── 3. Build ────────────────────────────────────────────────────────────
    print(f"\n{YELLOW}=== Building debug APK ==={_col('0', '')}", flush=True)
    build = gradle(":androidApp:assembleDebug")
    if build.returncode != 0:
        print(f"{RED}assembleDebug failed (rc={build.returncode}){_col('0', '')}", flush=True)
        if build.stdout:
            print(build.stdout, file=sys.stderr)
        if build.stderr:
            print(build.stderr, file=sys.stderr)
        return EXIT_BUILD_FAILED
    print(f"{GREEN}Build OK{_col('0', '')}", flush=True)

    # ── 3b. Verify APK provenance ─────────────────────────────────────────────
    expected_sha = git_sha()
    installed_sha = apk_sha_from_device(device)
    if not installed_sha:
        print(f"{RED}Cannot read build id from installed APK.{_col('0', '')}", flush=True)
        print(f"{RED}The installed binary is unidentifiable.{_col('0', '')}", flush=True)
        return EXIT_FAIL
    if installed_sha != expected_sha:
        print(f"{RED}Installed APK is not this checkout's build.{_col('0', '')}", flush=True)
        print(f"{RED}  checkout: g{expected_sha}{_col('0', '')}", flush=True)
        print(f"{RED}  installed: g{installed_sha}{_col('0', '')}", flush=True)
        return EXIT_FAIL
    print(f"APK provenance OK: g{installed_sha}", flush=True)

    # ── 4. Clear logcat ───────────────────────────────────────────────────
    adb("-s", device, "logcat", "-c")

    # ── 5. Run instrumentation tests ──────────────────────────────────────
    artifact_dir = Path(args.artifact_dir) if args.artifact_dir else ROOT / "build" / "android-ui-results"

    print(f"\n{YELLOW}=== Running UI tests on {device} ==={_col('0', '')}", flush=True)
    test = gradle(
        ":androidApp:connectedDebugAndroidTest",
        f"-Pandroid.testInstrumentationRunnerArguments.device={device}",
        "--no-build-cache",
    )

    if test.returncode == 0:
        print(f"\n{GREEN}All tests passed.{_col('0', '')}", flush=True)
        return EXIT_PASS

    # ── 6. Failure: capture artifacts ─────────────────────────────────────
    print(f"\n{RED}Tests failed (rc={test.returncode}){_col('0', '')}", flush=True)

    # Print the last 50 lines of test output — the important diagnostic is near the end.
    output = test.stdout or ""
    if output:
        lines = output.splitlines()
        tail = "\n".join(lines[-50:]) if len(lines) > 50 else output
        print(f"\n{YELLOW}--- test output (last 50 lines) ---{_col('0', '')}", flush=True)
        print(tail, flush=True)

    stderr = test.stderr or ""
    if stderr:
        print(f"\n{YELLOW}--- stderr ---{_col('0', '')}", flush=True)
        print(stderr, flush=True)

    print(f"\n{YELLOW}Capturing failure artifacts...{_col('0', '')}", flush=True)
    capture_logcat(device, artifact_dir)

    return EXIT_FAIL


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Run Android UI instrumentation tests on a connected device.

Usage:
    run-android-ui-tests.py                    # ensure a device, starting AVD if needed
    run-android-ui-tests.py <serial>          # pin a specific device
    SERIAL=emulator-5556 run-android-ui-tests.py  # also pins via env var

Device selection is delegated to ensure-emulator, the repo's single
recovery primitive. Hand-rolled discovery here could only ever find a device
that was already up — every emulator crash would surface as "no adb device
found". The message says "connect a device" when the actual remedy is one
command the runner already knows how to run.
"""
from __future__ import annotations

import argparse
import os
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # works from any cwd
SCRIPT_DIR = ROOT / "scripts"
SRC = SCRIPT_DIR / "src"
GRADLEW = ROOT / "gradlew"
APP_ID = "com.singularity.todo"


# ── APK provenance ─────────────────────────────────────────────────────────────
def sdk_root() -> Path:
    return Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")


def _aapt() -> str | None:
    """Path to a `aapt` from the newest installed build-tools, or None.

    Newest by sort order, which is how the SDK versions its build-tools
    directories (35, 36, 37). An unversioned one is not a candidate: it is not
    something the SDK installs.
    """
    root = sdk_root()
    if not root.is_dir():
        return None
    candidates = sorted(root.glob("build-tools/*/aapt"), reverse=True)
    return str(candidates[0]) if candidates else None


def apk_version_name(apk: Path) -> str | None:
    """versionName as recorded inside the APK, or None if it cannot be read.

    Read from the APK file, not from the device. It used to be read from
    `dumpsys package`, which asks the device what is installed — and on a runner
    with a fresh AVD nothing is installed, so the check read None on every clean
    machine and reported "the installed binary is unidentifiable" about a binary
    that did not exist yet. The property worth asserting is that the file about
    to be handed to `connectedAndroidTest` is this checkout's build, and that is
    a property of the file.
    """
    aapt = _aapt()
    if aapt is None or not apk.is_file():
        return None
    cp = try_run([aapt, "dump", "badging", str(apk)])
    if not cp or cp.returncode != 0:
        return None
    for line in cp.stdout.splitlines():
        if "versionName=" in line:
            value = line.split("versionName=", 1)[1].split("'")
            return value[1] if len(value) > 1 else None
    return None


def debug_apk() -> Path | None:
    """The APK `assembleDebug` produced, or None if the build wrote several."""
    outputs = ROOT / "androidApp" / "build" / "outputs" / "apk" / "debug"
    apks = sorted(outputs.glob("*.apk"))
    return apks[0] if len(apks) == 1 else (apks[0] if apks else None)


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
    """Run a command, or return None if it could not be run or did not answer.

    A timeout is a failed probe, not a crash. It used to propagate: a wedged adb
    raised `TimeoutExpired` out of every caller, and the run ended with a traceback
    naming a subprocess instead of a verdict naming the device — on exactly the
    runs, an unresponsive emulator, where the reason is the thing being diagnosed.
    """
    try:
        kwargs.setdefault("stdout", subprocess.PIPE)
        kwargs.setdefault("stderr", subprocess.PIPE)
        return run(args, **kwargs)
    except (OSError, subprocess.TimeoutExpired):
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
    """Return serial of a ready device, starting the AVD if needed.

    Runs `python -m emulator` rather than the `ensure-emulator` console script so
    this works from a bare checkout with nothing on PATH; `scripts/src` is
    prepended to PYTHONPATH below. `check=True` is meaningful: the command routes
    its verdict through ctx.exit, so a non-zero exit really is a failure.
    """
    env = {**os.environ}
    if serial:
        env["SERIAL"] = serial
    env["PYTHONPATH"] = os.pathsep.join(
        filter(None, [str(SRC), env.get("PYTHONPATH", "")])
    )
    try:
        result = subprocess.run(
            [sys.executable, "-m", "emulator"],
            cwd=ROOT,
            env=env,
            text=True,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        return result.stdout.strip().splitlines()[-1]
    except (subprocess.CalledProcessError, OSError):
        return None


def gradle(*args: str) -> subprocess.CompletedProcess:
    """Run Gradle with `GRADLE_ARGS` appended.

    GRADLE_ARGS is how the caller states what kind of build it wants — most
    importantly `-PTEST_AUTH_BYPASS=true`, without which the app opens on the
    auth screen and every UI assertion fails because there is no agenda to
    assert about. The build is assembled twice over (assembleDebug, then
    connectedDebugAndroidTest), so the property has to reach both; putting it in
    one place here is what stops the APK under test and the assertions about it
    being built from different flags.
    """
    extra = shlex.split(os.environ.get("GRADLE_ARGS", ""))
    return subprocess.run(
        [str(GRADLEW), *args, *extra],
        cwd=ROOT,
        text=True,
        check=False,
    )


# ── failure artifacts ──────────────────────────────────────────────────────────
def executed_test_count() -> int:
    """Tests the instrumentation run reports as executed.

    Summed over every XML the run produced, because a crash in one class does not
    remove the other classes' reports and "at least one ran" is the question being
    asked. Returns 0 when there is no XML at all, which is the same failure with a
    different cause and must not read as a pass.
    """
    results = ROOT / "androidApp" / "build" / "outputs" / "androidTest-results" / "connected"
    if not results.is_dir():
        return 0
    total = 0
    for xml in results.rglob("*.xml"):
        text = xml.read_text(errors="replace")
        # Every `<testsuite>`, not the first one. The file holds one per test
        # class — 6 + 3 + 3 on this repository's instrumented suite — and a
        # `search` that stops at the first header reports a third of the run
        # having executed while printing the rest as a pass.
        for match in re.finditer(r'<testsuite\b[^>]*\btests="(\d+)"', text):
            total += int(match.group(1))
    return total


def emulator_log_tail(lines: int = 40) -> str:
    """Tail of the log `scripts/src/emulator` wrote for the emulator process.

    Same path the emulator module writes to (EMULATOR_LOG, default
    /tmp/singularity-emulator.log). Read here rather than duplicated as a
    constant: a second copy of the path is a second thing to keep in step with
    `emulator._types.Config`.
    """
    sys.path.insert(0, str(SRC))
    try:
        from emulator._types import cfg
    except ImportError:
        return "(emulator module not importable; no log path known)"
    if not cfg.log_file.exists():
        return f"(no emulator log at {cfg.log_file})"
    return "\n".join(cfg.log_file.read_text(errors="replace").splitlines()[-lines:])


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
        print(f"{RED}ensure-emulator could not produce a ready device.{_col('0', '')}", flush=True)
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
    apk = debug_apk()
    if apk is None:
        print(f"{RED}assembleDebug produced no APK to verify.{_col('0', '')}", flush=True)
        return EXIT_BUILD_FAILED
    version = apk_version_name(apk)
    if version is None:
        print(f"{RED}Cannot read versionName from {apk}.{_col('0', '')}", flush=True)
        print(f"{RED}The binary about to be installed is unidentifiable.{_col('0', '')}", flush=True)
        return EXIT_FAIL
    installed_sha = version.split("+", 1)[1].lstrip("g") if "+" in version else ""
    if installed_sha != expected_sha:
        print(f"{RED}APK is not this checkout's build.{_col('0', '')}", flush=True)
        print(f"{RED}  checkout: g{expected_sha}{_col('0', '')}", flush=True)
        print(f"{RED}  apk:      {version}{_col('0', '')}", flush=True)
        return EXIT_FAIL
    print(f"APK provenance OK: {apk.name} ({version})", flush=True)

    # ── 4. Clear logcat ───────────────────────────────────────────────────
    # The build above can take minutes, and the emulator can die during it. Ask
    # again here rather than handing a dead serial to Gradle: a lost device
    # otherwise surfaces as a Gradle/AGP failure about a device, minutes later.
    if not device_alive(device):
        print(f"{RED}Device {device} is gone after the build.{_col('0', '')}", flush=True)
        print(f"{RED}Re-acquiring: {adb('devices') or '(adb produced no device list)'}{_col('0', '')}",
              flush=True)
        device = ensure_device(serial)
        if not device or not device_alive(device):
            print(f"{RED}No device available for the test run.{_col('0', '')}", flush=True)
            return EXIT_DEVICE_UNAVAILABLE
        print(f"{GREEN}Re-acquired: {device}{_col('0', '')}", flush=True)

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
        executed = executed_test_count()
        if executed == 0:
            # connectedDebugAndroidTest exits 0 when the app under test dies before
            # the first assertion: zero tests ran, nothing failed, and the report
            # is a green line. That is the state this exists to catch.
            print(f"\n{RED}connectedDebugAndroidTest exited 0 having run 0 tests.{_col('0', '')}",
                  flush=True)
            print(f"{RED}The app likely crashed on startup — see build/android-ui-results/logcat.txt{_col('0', '')}",
                  flush=True)
            capture_logcat(device, artifact_dir)
            return EXIT_FAIL
        print(f"\n{GREEN}All tests passed ({executed} executed).{_col('0', '')}", flush=True)
        return EXIT_PASS

    # ── 6. Failure: capture artifacts ─────────────────────────────────────
    print(f"\n{RED}Tests failed (rc={test.returncode}){_col('0', '')}", flush=True)

    # A dead device and a failing assertion produce the same Gradle exit code.
    # Say which one it was before printing a test tail that describes the wrong
    # cause — an emulator lost mid-run is the single most common failure here.
    if not device_alive(device):
        print(f"\n{RED}The device {device} did not survive the run — this is not an "
              f"assertion failure.{_col('0', '')}", flush=True)
        print(f"{RED}adb devices:{_col('0', '')}\n{adb('devices') or '(no device list)'}", flush=True)
        print(f"\n{YELLOW}--- emulator log tail ---{_col('0', '')}", flush=True)
        print(emulator_log_tail(), flush=True)
        capture_logcat(device, artifact_dir)
        return EXIT_DEVICE_UNAVAILABLE

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

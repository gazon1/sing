#!/usr/bin/env python3
"""Run Maestro E2E flows on a device acquired via ensure-emulator.

Usage:
    run-maestro-e2e.py --apk <path> [--suite smoke|full] --shard N --shards N

Workflow integration:
    1. Build APK (build-apk job) produces artifact
    2. This script downloads artifact, installs APK, acquires device, runs flows
    3. JUnit XML goes to build/maestro-results/

Environment (set by e2e.yml workflow):
    SERIAL           — optional device serial to pin
    AVD             — AVD name (default: Medium_Phone)
    EMULATOR_EXTRA_FLAGS  — extra emulator flags (e.g. -gpu swiftshader_indirect)
    EMULATOR_TIMEOUT     — boot timeout per attempt (default: 300)
    SUITE           — "smoke" or "full"
    SHARD           — 1-based shard index
    SHARDS          — total number of shards
    MAESTRO_VERSION — Maestro CLI version (for --version check)
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_SRC = ROOT / "scripts" / "src"
GRADLEW = ROOT / "gradlew"

# Exit codes matching emulator module
EXIT_PASS = 0
EXIT_FAIL = 1
EXIT_DEVICE_UNAVAILABLE = 3

_is_tty = sys.stderr.isatty()


def _col(code: str, s: str) -> str:
    return f"\033[{code}m{s}\033[0m" if _is_tty else s


RED = lambda s: _col("0;31", s)
GREEN = lambda s: _col("0;32", s)
YELLOW = lambda s: _col("0;33", s)


# ── subprocess helpers ──────────────────────────────────────────────────────────

def try_run(args: list[str], **kwargs) -> subprocess.CompletedProcess | None:
    """Run a command, or return None on failure."""
    try:
        kwargs.setdefault("stdout", subprocess.PIPE)
        kwargs.setdefault("stderr", subprocess.PIPE)
        return subprocess.run(args, text=True, **kwargs)
    except (OSError, subprocess.TimeoutExpired):
        return None


# ── Device acquisition (delegates to scripts/src/emulator) ────────────────────

def ensure_device(serial: str | None = None) -> str | None:
    """Return serial of a ready device via `python3 -m emulator`.

    Matches the approach of run-android-ui-tests.py: PYTHONPATH must include
    scripts/src so the emulator module is importable.
    """
    env = {**os.environ}
    if serial:
        env["SERIAL"] = serial
    env["PYTHONPATH"] = os.pathsep.join(
        filter(None, [str(SCRIPTS_SRC), env.get("PYTHONPATH", "")])
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
    except subprocess.CalledProcessError as exc:
        sys.stderr.write(exc.stderr or "")
        sys.stderr.flush()
        return None
    except OSError:
        return None


def device_alive(serial: str) -> bool:
    """Check device is responsive via adb."""
    state = try_run(["adb", "-s", serial, "get-state"])
    ping = try_run(["adb", "-s", serial, "shell", "echo", "ping"])
    return (state is not None and state.returncode == 0 and "device" in (state.stdout or "")
            and ping is not None and ping.returncode == 0 and "ping" in (ping.stdout or ""))


# ── APK installation ───────────────────────────────────────────────────────────

def resolve_apk(path: Path) -> Path | None:
    """The APK to install: the file itself, or the one inside a directory.

    The workflow downloads the `e2e-apk` artifact into a *directory* (`apk/`),
    because `actions/download-artifact` with a `path:` extracts the archive
    there and the archive's own layout is not ours to predict. So the argument
    is a directory in practice and a file only when a caller passes one.

    A directory that holds no APK is a failure, not a reason to install
    something else; a directory that holds several is ambiguous, and picking
    one of them is how a test suite ends up testing the wrong build.
    """
    path = path.expanduser()
    if path.is_file():
        return path.resolve()
    if path.is_dir():
        candidates = sorted(path.rglob("*.apk"))
        if len(candidates) == 1:
            return candidates[0].resolve()
        if len(candidates) > 1:
            names = ", ".join(str(c) for c in candidates)
            print(f"{RED}Several APKs in {path}: {names}{_col('0', '')}", flush=True)
            return None
    return None


def adb_install(serial: str, apk_path: Path) -> bool:
    """Install APK via adb install -r -t. Returns True on success."""
    cp = try_run(["adb", "-s", serial, "install", "-r", "-t", str(apk_path)], timeout=120)
    if cp is None or cp.returncode != 0:
        sys.stderr.write(cp.stderr if cp else "adb install failed")
        return False
    return True


def wait_for_app_ready(serial: str, timeout: int = 90) -> bool:
    """Poll dumpsys activity until MainActivity is top-resumed."""
    deadline = time.monotonic() + timeout
    app_id = "com.singularity.todo/.MainActivity"
    while time.monotonic() < deadline:
        out = try_run(
            ["adb", "-s", serial, "shell", "dumpsys", "activity", "activities"],
            timeout=5,
        )
        if out and f"topResumedActivity=.{app_id}" in (out.stdout or ""):
            return True
        # Check if process is alive
        if int(time.monotonic()) % 10 == 5:
            ps = try_run(["adb", "-s", serial, "shell", "ps", "-A"], timeout=5)
            if ps and "com.singularity.todo" not in (ps.stdout or ""):
                sys.stderr.write("WARNING: app process not found in ps\n")
        time.sleep(1)
    sys.stderr.write(f"WARNING: app readiness timed out after {timeout}s\n")
    return True  # Let Maestro's extendedWaitUntil be the secondary guard


# ── Maestro run ───────────────────────────────────────────────────────────────

def run_maestro(
    *,
    serial: str,
    shard: int | None,
    shards: int | None,
    suite: str,
    junit_dir: Path,
) -> int:
    """Run Maestro flows via python3 -m emulator.maestro."""
    # Build argv for maestro._cli
    argv = [
        sys.executable, "-m", "emulator.maestro",
        "--serial", serial,
        "--skip-install",          # APK already installed via adb
        "--allow-stale-apk",      # provenance N/A for artifact APK
        "--junit-dir", str(junit_dir),
        "--maestro-cleanup", "run",
    ]
    if shard is not None and shards is not None:
        argv.extend(["--shard", str(shard), "--shards", str(shards)])

    # Flow selection by suite
    if suite == "smoke":
        argv.extend(["--flow", str(ROOT / "Maestro/flows/smoke")])
    else:
        argv.extend(["--flow", str(ROOT / "Maestro/flows")])

    # MAESTRO_CONFIG for the config.yaml location
    env = {
        **os.environ,
        "PYTHONPATH": os.pathsep.join(filter(None, [
            str(SCRIPTS_SRC),
            os.environ.get("PYTHONPATH", ""),
        ])),
        "MAESTRO_CONFIG": str(ROOT / "Maestro/config.yaml"),
    }

    result = subprocess.run(argv, cwd=ROOT, env=env)
    return result.returncode


# ── Main ──────────────────────────────────────────────────────────────────────

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                    formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk", required=True, type=Path,
                        help="Path to the downloaded APK artifact")
    parser.add_argument("--suite", required=True, choices=["smoke", "full"],
                        help="Test suite to run")
    parser.add_argument("--shard", required=True, type=int,
                        help="Shard index (1-based)")
    parser.add_argument("--shards", required=True, type=int,
                        help="Total number of shards")
    parser.add_argument("--serial", default=None,
                        help="Device serial to pin (also via SERIAL env var)")
    parser.add_argument("--artifact-dir", default=None,
                        help="Directory for JUnit XML (default: build/maestro-results)")
    parser.add_argument("--skip-device-check", action="store_true",
                        help="Skip ensure_device() — device is already running (workflow started it)")
    args = parser.parse_args(argv)

    serial = args.serial or os.environ.get("SERIAL") or ""

    if args.skip_device_check:
        # Workflow started the AVD via `python3 -m emulator`; we just use it.
        if not serial:
            # adb devices returns serial\tdevice\n... — grab the first online device.
            out = try_run(["adb", "devices"], timeout=10)
            if out and out.returncode == 0:
                for line in (out.stdout or "").splitlines():
                    parts = line.strip().split()
                    if len(parts) == 2 and parts[1] == "device":
                        serial = parts[0]
                        break
        if not serial:
            print(f"{RED}No device serial available and --skip-device-check set.{_col('0', '')}",
                  flush=True)
            return EXIT_DEVICE_UNAVAILABLE
        device = serial
        print(f"{YELLOW}Using pre-started device: {device}{_col('0', '')}", flush=True)
    else:
        print(f"{YELLOW}Acquiring device...{_col('0', '')}", flush=True)
        device = ensure_device(serial)
        if not device:
            print(f"{RED}ensure-emulator could not produce a ready device.{_col('0', '')}",
                  flush=True)
            out = try_run(["adb", "devices"])
            print(f"adb devices:\n{out.stdout if out else '(none)'}", flush=True)
            return EXIT_DEVICE_UNAVAILABLE

    if not device_alive(device):
        print(f"{RED}Device {device} is not responsive.{_col('0', '')}", flush=True)
        return EXIT_DEVICE_UNAVAILABLE

    print(f"{GREEN}Device: {device}{_col('0', '')}", flush=True)

    # ── Install APK ──────────────────────────────────────────────────────────
    apk = resolve_apk(args.apk)
    if apk is None:
        target = args.apk.expanduser()
        if target.is_dir():
            print(f"{RED}No APK in {target.resolve()}{_col('0', '')}", flush=True)
        else:
            print(f"{RED}APK not found: {target.resolve()}{_col('0', '')}", flush=True)
        return EXIT_FAIL

    print(f"{YELLOW}Installing {apk.name}...{_col('0', '')}", flush=True)
    if not adb_install(device, apk):
        print(f"{RED}APK installation failed.{_col('0', '')}", flush=True)
        return EXIT_FAIL
    print(f"{GREEN}APK installed.{_col('0', '')}", flush=True)

    # ── Wake + unlock ───────────────────────────────────────────────────────
    try_run(["adb", "-s", device, "shell", "input", "keyevent", "KEYCODE_WAKEUP"])
    try_run(["adb", "-s", device, "shell", "input", "keyevent", "82"])  # unlock
    try_run(["adb", "-s", device, "shell", "settings", "put", "secure",
             "show_ime_with_hard_keyboard", "0"])

    # ── Wait for app ready ──────────────────────────────────────────────────
    print(f"{YELLOW}Waiting for app to be ready...{_col('0', '')}", flush=True)
    wait_for_app_ready(device)
    print(f"{GREEN}App ready.{_col('0', '')}", flush=True)

    # ── Run Maestro ──────────────────────────────────────────────────────────
    junit_dir = Path(args.artifact_dir) if args.artifact_dir else ROOT / "build" / "maestro-results"
    junit_dir.mkdir(parents=True, exist_ok=True)

    print(f"\n{YELLOW}=== Running Maestro E2E (suite={args.suite}, shard {args.shard}/{args.shards}) ==={_col('0', '')}",
          flush=True)

    rc = run_maestro(
        serial=device,
        shard=args.shard,
        shards=args.shards,
        suite=args.suite,
        junit_dir=junit_dir,
    )

    if rc == EXIT_PASS:
        print(f"{GREEN}=== Maestro E2E passed ==={_col('0', '')}", flush=True)
    else:
        print(f"{RED}=== Maestro E2E failed (exit {rc}) ==={_col('0', '')}", flush=True)

    return rc


if __name__ == "__main__":
    sys.exit(main())

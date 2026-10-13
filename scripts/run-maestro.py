#!/usr/bin/env python3
"""Run Maestro UI flows against a connected Android device or emulator.

Usage:
    run-maestro.py                          # every flow
    run-maestro.py --tags smoke             # only flows tagged "smoke"
    run-maestro.py --flow Maestro/flows/tasks  # one directory
    run-maestro.py --serial emulator-5554  # pin the device
    run-maestro.py --skip-install           # reuse the installed APK
    run-maestro.py --allow-stale-apk        # run against older binary deliberately
    run-maestro.py --maestro-max-retries 0  # do not retry a lost device
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT_DIR = ROOT / "scripts"
ENSURE_EMULATOR = SCRIPT_DIR / "ensure-emulator.py"
GRADLEW = ROOT / "gradlew"

APP_ID = "com.singularity.todo"

# Exit codes mirrors run-maestro.sh: 0=all passed, 1=any failure
EXIT_PASS = 0
EXIT_FAIL = 1

# ── colour output ──────────────────────────────────────────────────────────────
_is_tty = sys.stderr.isatty()
def _col(code: str, s: str) -> str:
    return f"\033[{code}m{s}\033[0m" if _is_tty else s

RED   = lambda s: _col("0;31", s)
GREEN = lambda s: _col("0;32", s)
YELLOW= lambda s: _col("0;33", s)


# ── subprocess helpers ─────────────────────────────────────────────────────────
def run(args: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(args, text=True, **kwargs)

def run_output(args: list[str], **kwargs) -> str:
    """Run and return stdout; stderr goes to our stderr."""
    return run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs).stdout

def try_run(args: list[str], **kwargs) -> subprocess.CompletedProcess | None:
    try:
        return run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)
    except OSError:
        return None


# ── tag matching (reimplementation of maestro-flow-tags.sh) ───────────────────
def flow_tags(path: Path) -> list[str]:
    """Return the list of tags declared in a flow's tags: block, trimmed.

    The tags block may appear in the YAML frontmatter (before the first ---) or
    in any YAML document. Scanning proceeds document-by-document, bounded by ---
    separators, and stops at the first non-blank, non-comment, non-list-item
    line outside a tags block.
    """
    text = path.read_text()

    # Scan the whole file; tags can appear in any document.
    # Blank lines and comments inside a tags block are skipped.
    # The first non-tag, non-blank, non-comment line outside a tags: block ends it.
    in_tags = False
    tags: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("---"):
            in_tags = False  # reset on document boundary
            continue
        if stripped.startswith("tags:"):
            in_tags = True
            continue
        if in_tags:
            if stripped.startswith("- "):
                value = stripped[1:].strip().strip("'\"").split("#")[0].strip()
                if value:
                    tags.append(value)
            elif stripped == "" or stripped.startswith("#"):
                continue
            else:
                in_tags = False
    return tags

def flow_has_tag(path: Path, tag: str) -> bool:
    return tag in flow_tags(path)


# ── device helpers ─────────────────────────────────────────────────────────────
def adb(*args: str, timeout: float = 5) -> str | None:
    cp = try_run(["adb", *args], timeout=timeout)
    return cp.stdout.strip() if cp and cp.returncode == 0 else None

def adb_check(*args: str) -> bool:
    """Run adb; return True on exit 0, suppress all output."""
    cp = try_run(["adb", *args], timeout=5)
    return cp is not None and cp.returncode == 0

def device_alive(serial: str) -> bool:
    """Both checks: adb get-state == device AND shell echo succeeds."""
    state_ok = adb("-s", serial, "get-state") == "device"
    ping_ok = adb("-s", serial, "shell", "echo", "ping") == "ping"
    return state_ok and ping_ok

def device_model(serial: str) -> str:
    return (adb("-s", serial, "shell", "getprop", "ro.product.model") or "").strip()

def wait_for_app_resumed(serial: str, timeout: int = 20) -> bool:
    """Poll dumpsys activity until the main activity is top-resumed."""
    waited = 0
    while waited < timeout:
        out = adb("-s", serial, "shell", "dumpsys", "activity", "activities",
                  timeout=5) or ""
        if f"topResumedActivity=.{APP_ID}/" in out or f"topResumedActivity={APP_ID}/" in out:
            return True
        time.sleep(1)
        waited += 1
    return False

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
    combined = " ".join(stderr.split())  # flatten multiline
    for pat in patterns:
        if re.search(pat, stderr, re.IGNORECASE):
            return True
    return False

def reset_adb_server() -> None:
    adb("kill-server")
    time.sleep(2)
    adb("start-server")
    time.sleep(2)


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
    cp = try_run(["git", "-C", str(ROOT), "rev-parse", "--short=12", "HEAD"])
    return cp.stdout.strip() if cp and cp.returncode == 0 else "unknown"


# ── device acquisition ─────────────────────────────────────────────────────────
def ensure_device(serial: str | None) -> str | None:
    """Return serial of a ready device; try to recover if not ready."""
    # Fast path: adb already knows a ready device.
    if serial:
        if device_alive(serial):
            return serial
    else:
        # Scan for any ready device.
        out = adb("devices") or ""
        for line in out.splitlines()[1:]:
            parts = line.split()
            if len(parts) == 2 and parts[1] == "device" and device_alive(parts[0]):
                return parts[0]

    # Try recovery via ensure-emulator.py.
    env = {**os.environ}
    if serial:
        env["SERIAL"] = serial
    try:
        result = subprocess.run(
            [sys.executable, str(ENSURE_EMULATOR)],
            cwd=ROOT, env=env,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        return result.stdout.strip().splitlines()[-1]
    except subprocess.CalledProcessError:
        return None


# ── flow collection ────────────────────────────────────────────────────────────
def collect_flows(target: Path) -> list[Path]:
    """Return sorted list of *.yaml files under target."""
    target = target.resolve()
    if target.is_dir():
        return sorted(target.rglob("*.yaml"), key=lambda p: p.name.lower())
    elif target.is_file():
        return [target]
    else:
        # Glob pattern
        matches = sorted(m for m in ROOT.glob(str(target)) if m.suffix == ".yaml")
        return matches

def filter_flows_by_tag(flows: list[Path], tags: list[str]) -> list[Path]:
    """Return flows that declare any of the given tags."""
    if not tags:
        return flows
    result = []
    for f in flows:
        for t in tags:
            if flow_has_tag(f, t):
                result.append(f)
                break
    return result


# ── fixture cleanup ────────────────────────────────────────────────────────────
def cleanup_run_fixtures(serial: str, run_id: str, mode: str = "run") -> None:
    """Send cleanup deeplink to the app. Best-effort."""
    if mode == "off" or not device_alive(serial):
        return
    encoded = urllib.parse.quote(f"'{run_id},Buy%20milk'")
    if mode == "all":
        cmd = f"am start -a android.intent.action.VIEW -d todo-debug://seed?reset=content"
    else:
        cmd = f"am start -a android.intent.action.VIEW -d todo-debug://seed?cleanup={encoded}"
    adb("-s", serial, "shell", cmd)
    time.sleep(1)


# ── run one flow ───────────────────────────────────────────────────────────────
def run_one_flow(
    flow_path: Path,
    serial: str,
    junit_dir: Path,
    skip_install: bool,
    allow_stale: bool,
    max_retries: int,
    expected_sha: str,
) -> tuple[bool, str]:
    """Run one Maestro flow. Returns (passed, run_id).

    Calls itself recursively on device failure after recovery.
    """
    flow_name = flow_path.stem  # basename without .yaml
    junit_dir.mkdir(parents=True, exist_ok=True)
    junit_file = junit_dir / f"{flow_name}.xml"
    log_file = junit_dir / f"{flow_name}.log"

    for attempt in range(max_retries + 1):
        run_id = f"r{int(time.time() * 1000) % 10**9}{attempt}"
        cmd = [
            "maestro", "test", str(flow_path),
            "--env", f"RUN_ID={run_id}",
            "--format", "JUNIT",
            "--output", str(junit_file),
        ]
        print(f"  run id: {run_id}", flush=True)

        cp = try_run(cmd, cwd=ROOT, timeout=300)
        status = cp.returncode if cp else -1
        stdout = cp.stdout if cp else ""

        if status == 0:
            # Check for APK provenance if device was relaunched (attempt > 0).
            if attempt > 0 and not allow_stale:
                installed_sha = apk_sha_from_device(serial)
                if installed_sha and installed_sha != expected_sha:
                    print(f"{YELLOW}WARNING: device was relaunched and carries "
                          f"g{installed_sha}, not g{expected_sha}; "
                          f"reinstalling...{_col('0', '')}", flush=True)
                    return False, run_id  # signal caller to reinstall
            return True, run_id

        # Non-zero exit: decide whether it is retryable.
        stderr = cp.stderr if cp else ""
        if not maestro_saw_device_failure(stderr) and device_alive(serial):
            # Real failure — not retryable.
            print(stdout, flush=True)
            return False, run_id

        if attempt >= max_retries:
            print(stdout, flush=True)
            return False, run_id

        # Retryable: attempt recovery.
        print(f"{YELLOW}Device unusable during {flow_name}; recovering "
              f"(attempt {attempt + 1})...{_col('0', '')}", flush=True)
        reset_adb_server()

        if not device_alive(serial):
            recovered = ensure_device(serial)
            if not recovered:
                return False, run_id
            serial = recovered
        else:
            # Device survived but Maestro couldn't reach it — adb server reset helped.
            # Re-discover the serial.
            out = adb("devices") or ""
            for line in out.splitlines()[1:]:
                parts = line.split()
                if len(parts) == 2 and parts[1] == "device":
                    serial = parts[0]
                    break

        # Re-apply IME workaround and reinstall.
        adb("-s", serial, "shell", "settings", "put", "secure",
            "show_ime_with_hard_keyboard", "0")

        if not skip_install:
            install_cp = run(
                [str(GRADLEW), ":androidApp:installDebug",
                 f"-Pandroid.device={serial}", "--quiet"],
                cwd=ROOT,
            )
            if install_cp.returncode != 0:
                print(f"{RED}installDebug failed (rc={install_cp.returncode}){_col('0', '')}",
                      flush=True)
                return False, run_id
            # Re-verify provenance after reinstall.
            if not allow_stale:
                installed_sha = apk_sha_from_device(serial)
                if installed_sha and installed_sha != expected_sha:
                    print(f"{RED}Relaunched device holds g{installed_sha}, "
                          f"not g{expected_sha}; results would describe wrong binary.{_col('0', '')}",
                          flush=True)
                    return False, run_id
        else:
            # SKIP_INSTALL=1 but device was relaunched — provenance check still needed.
            if not allow_stale:
                installed_sha = apk_sha_from_device(serial)
                if not installed_sha or installed_sha != expected_sha:
                    print(f"{YELLOW}WARNING: device was relaunched and SKIP_INSTALL=1, "
                          f"so binary is whatever the snapshot held.{_col('0', '')}",
                          flush=True)

    return False, run_id


# ── capture failure artifacts ──────────────────────────────────────────────────
def capture_artifacts(serial: str, flow_name: str, artifact_dir: Path) -> None:
    """Screencap + logcat at failure time. Best-effort."""
    if not device_alive(serial):
        return
    artifact_dir.mkdir(parents=True, exist_ok=True)

    # Raw framebuffer screenshot
    remote = f"/sdcard/{flow_name}_screen.png"
    local = artifact_dir / f"{flow_name}_screen.png"
    if adb("-s", serial, "shell", "screencap", "-p", remote):
        adb("-s", serial, "pull", remote, str(local))
        adb("-s", serial, "shell", "rm", "-f", remote)
        print(f"  Screenshot: {local}")

    # Logcat buffer
    logcat_file = artifact_dir / f"{flow_name}_logcat.txt"
    logcat_out = adb("-s", serial, "shell", "logcat", "-d")
    if logcat_out:
        logcat_file.write_text(logcat_out)
        print(f"  Logcat: {logcat_file}")


# ── main ──────────────────────────────────────────────────────────────────────
def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        description="Run Maestro UI flows against a connected Android device.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--flow", "-f", default="Maestro/flows",
                   help="Flow directory, file, or glob (default: Maestro/flows)")
    p.add_argument("--tags", "-t", default="",
                   help="Comma-separated tags to filter; flow must declare one of them")
    p.add_argument("--serial", "-s", default=None,
                   help="Pin a specific device serial (also set via SERIAL env var)")
    p.add_argument("--skip-install", action="store_true",
                   help="Skip APK install (reuse whatever is on the device)")
    p.add_argument("--allow-stale-apk", action="store_true",
                   help="Do not verify that the installed APK matches this checkout")
    p.add_argument("--maestro-max-retries", type=int, default=2,
                   help="Max device-recovery retries per flow (default: 2)")
    p.add_argument("--maestro-cleanup", choices=["run", "all", "off"], default="run",
                   help="Fixture cleanup scope (default: run)")
    p.add_argument("--junit-dir", default=None,
                   help="Directory for JUnit XML results "
                        "(default: <repo>/build/maestro-results)")
    p.add_argument("--dry-run", action="store_true",
                   help="Show which flows would run and exit; no device or APK needed")
    args = p.parse_args(argv)

    serial: str | None = args.serial or os.environ.get("SERIAL")

    # De-export SERIAL — recovery must depend on what we discover, not what the
    # caller exported.  (In Python subprocess env= explicitly controls the env,
    # so this is just for clarity.)
    serial = serial or ""

    # ── 0. Dry-run: discover and report flows, then exit ────────────────────
    if args.dry_run:
        flow_target = (ROOT / args.flow).resolve()
        all_flows = collect_flows(flow_target)
        tags = [t.strip() for t in args.tags.split(",") if t.strip()]
        flows = filter_flows_by_tag(all_flows, tags) if tags else all_flows

        print(f"{YELLOW}=== Dry run — no device or APK required ==={_col('0', '')}", flush=True)
        print(f"Flow target  : {flow_target.relative_to(ROOT)}", flush=True)
        print(f"Total discovered: {len(all_flows)}", flush=True)
        print(f"After tag filter ({tags or 'none'}): {len(flows)}", flush=True)
        print(f"\nFlows that would run:", flush=True)
        for f in flows:
            print(f"  {f.relative_to(ROOT)}", flush=True)

        # Scan for flows that reference seed-task (common dependency).
        seed_variants = {"seed-task", "seed_task", "seedtask"}
        seed_dep_flows = [
            f.relative_to(ROOT) for f in flows
            if any(v in f.read_text() for v in seed_variants)
        ]
        if seed_dep_flows:
            print(f"\nFlows referencing seed-task dependency ({len(seed_dep_flows)}):", flush=True)
            for f in seed_dep_flows:
                print(f"  {f}", flush=True)

        print(f"\n{GREEN}Dry run complete. Run without --dry-run to execute.{_col('0', '')}",
              flush=True)
        return EXIT_PASS

    # ── 1. Acquire device ───────────────────────────────────────────────────
    print(f"{YELLOW}No ready device; attempting recovery.{_col('0', '')}", flush=True)
    ready = ensure_device(serial) if serial else None
    if not ready:
        print(f"{RED}Could not obtain a device.{_col('0', '')}", flush=True)
        print(f"{RED}{adb('devices') or ''}{_col('0', '')}", flush=True)
        return EXIT_FAIL
    serial = ready

    print(f"=== Maestro on {serial} ({device_model(serial)}) ===", flush=True)

    # ── 2. Wake + unlock + IME workaround ───────────────────────────────────
    adb("-s", serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("-s", serial, "shell", "input", "keyevent", "82")
    adb("-s", serial, "shell", "settings", "put", "secure",
        "show_ime_with_hard_keyboard", "0")

    # ── 3. Install APK ───────────────────────────────────────────────────────
    if args.skip_install:
        print("Skipping install (--skip-install)")
    else:
        print("=== Installing debug APK ===", flush=True)
        cp = run([str(GRADLEW), ":androidApp:installDebug",
                  f"-Pandroid.device={serial}", "--quiet"], cwd=ROOT)
        if cp.returncode != 0:
            print(f"{RED}installDebug failed (rc={cp.returncode}){_col('0', '')}", flush=True)
            return EXIT_FAIL

    # ── 3b. APK provenance ──────────────────────────────────────────────────
    if not args.allow_stale_apk:
        expected_sha = git_sha()
        installed_sha = apk_sha_from_device(serial)
        if not installed_sha:
            print(f"{RED}Cannot read build id from installed APK.{_col('0', '')}", flush=True)
            print(f"{RED}The installed binary is unidentifiable; results prove nothing "
                  f"about this checkout.{_col('0', '')}", flush=True)
            return EXIT_FAIL
        if installed_sha != expected_sha:
            print(f"{RED}Installed APK is not this checkout's build.{_col('0', '')}", flush=True)
            print(f"{RED}  checkout: g{expected_sha}{_col('0', '')}", flush=True)
            print(f"{RED}  installed: g{installed_sha}{_col('0', '')}", flush=True)
            return EXIT_FAIL
        print(f"APK provenance OK: g{installed_sha}", flush=True)
    else:
        expected_sha = None

    # ── 4. Clear logcat ────────────────────────────────────────────────────
    adb("-s", serial, "logcat", "-c")

    # ── 5. Collect flows ────────────────────────────────────────────────────
    flow_target = (ROOT / args.flow).resolve()
    flows = collect_flows(flow_target)
    if not flows:
        print(f"{RED}No flow files matched '{flow_target}'{_col('0', '')}", flush=True)
        return EXIT_FAIL

    tags = [t.strip() for t in args.tags.split(",") if t.strip()]
    if tags:
        flows = filter_flows_by_tag(flows, tags)
        if not flows:
            print(f"{RED}No flow files carry tag(s): {args.tags}{_col('0', '')}", flush=True)
            return EXIT_FAIL

    print(f"Discovered {len(flows)} flow file(s):", flush=True)
    for f in flows:
        print(f"  {f.relative_to(ROOT)}", flush=True)
    if tags:
        print(f"Filtering to flows tagged: {tags} ({len(flows)} file(s))", flush=True)

    # ── 5b. Run each flow ───────────────────────────────────────────────────
    junit_dir = Path(args.junit_dir) if args.junit_dir else ROOT / "build" / "maestro-results"
    passed: list[str] = []
    failed: list[str] = []

    for flow_path in flows:
        flow_name = flow_path.stem
        print(f"\n=== {flow_name} ===", flush=True)

        # Stop the app between flows — residual UI pollutes the next run.
        if device_alive(serial):
            adb("-s", serial, "shell", "am", "force-stop", APP_ID)
            time.sleep(1)
            adb("-s", serial, "shell", "am", "start", "-n", f"{APP_ID}/.MainActivity")
            wait_for_app_resumed(serial)

        ok, run_id = run_one_flow(
            flow_path=flow_path,
            serial=serial,
            junit_dir=junit_dir,
            skip_install=args.skip_install,
            allow_stale=args.allow_stale_apk,
            max_retries=args.maestro_max_retries,
            expected_sha=expected_sha or "",
        )

        if ok:
            passed.append(flow_name)
            cleanup_run_fixtures(serial, run_id, args.maestro_cleanup)
        else:
            failed.append(flow_name)
            # Capture failure artifacts.
            capture_artifacts(serial, flow_name, junit_dir / flow_name)

    # ── 6. Crash triage ────────────────────────────────────────────────────
    if device_alive(serial):
        logcat = adb("-s", serial, "shell", "logcat", "-d") or ""
        crashes = re.findall(
            r"FATAL EXCEPTION|AndroidRuntime.*com\.singularity\.todo",
            logcat, re.IGNORECASE
        )
        if crashes:
            print(f"{RED}=== App crashes detected in logcat ==={_col('0', '')}", flush=True)
            for c in crashes[:20]:
                print(c, flush=True)
    else:
        print(f"{RED}=== No device at end of run; crash triage skipped ==={_col('0', '')}",
              flush=True)

    # ── Summary ────────────────────────────────────────────────────────────
    if passed:
        print(f"\n{GREEN}Passed ({len(passed)}): {', '.join(passed)}{_col('0', '')}",
              flush=True)
    if failed:
        print(f"{RED}Failed ({len(failed)}): {', '.join(failed)}{_col('0', '')}", flush=True)

    if not failed:
        print(f"{GREEN}=== Maestro flows passed ==={_col('0', '')}", flush=True)
    else:
        print(f"{RED}=== Maestro flows failed ==={_col('0', '')}", flush=True)

    return EXIT_FAIL if failed else EXIT_PASS


if __name__ == "__main__":
    sys.exit(main())

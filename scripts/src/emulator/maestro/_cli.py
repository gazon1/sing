"""maestro._cli — Click CLI entry point with loguru structured logging."""
from __future__ import annotations

import os
import re
import sys
import time
from pathlib import Path

import click
from loguru import logger as _logger

from emulator._loguru import setup_logger
from emulator.maestro._flow import collect_flows, filter_flows_by_tag
from emulator.maestro._types import (
    APP_ID,
    EXIT_FAIL,
    EXIT_PASS,
    GW,
    ROOT,
    DeviceDeps,
    _default_device_deps,
)

# ── loguru setup ───────────────────────────────────────────────────────────────
_is_ci = bool(getattr(sys, "_called_from_test", False) or os.environ.get("CI"))
setup_logger(is_ci=_is_ci)
logger = _logger


def _col(code: str, s: str) -> str:
    is_tty = sys.stderr.isatty()
    return f"\033[{code}m{s}\033[0m" if is_tty else s


RED    = lambda s: _col("0;31", s)
GREEN  = lambda s: _col("0;32", s)
YELLOW = lambda s: _col("0;33", s)


def _report_is_fresh(report: Path, started_at: float) -> bool:
    """True when `report` exists and was written after the run started.

    A JUnit file from an earlier run is not evidence about this run, and a file
    that does not exist at all means the flow never reported.
    """
    try:
        return report.is_file() and report.stat().st_mtime >= started_at
    except OSError:
        return False


@click.command(
    help="Run Maestro UI flows against a connected Android device or emulator.",
    epilog="SERIAL env var also accepted for device selection.",
)
@click.option("--flow", "-f", "flow", default="Maestro/flows",
              help="Flow directory, file, or glob (default: Maestro/flows)")
@click.option("--tags", "-t", "tags", default="",
              help="Comma-separated tags to filter; flow must declare one of them")
@click.option("--serial", "-s", "serial", default=None,
              help="Pin a specific device serial (also set via SERIAL env var)")
@click.option("--skip-install", is_flag=True,
              help="Skip APK install (reuse whatever is on the device)")
@click.option("--allow-stale-apk", is_flag=True,
              help="Do not verify that the installed APK matches this checkout")
@click.option("--maestro-max-retries", type=int, default=2,
              help="Max device-recovery retries per flow (default: 2)")
@click.option("--maestro-cleanup", type=click.Choice(["run", "all", "off"]),
              default="run", help="Fixture cleanup scope (default: run)")
@click.option("--junit-dir", "junit_dir", default=None,
              help="Directory for JUnit XML results (default: <repo>/build/maestro-results)")
@click.option("--dry-run", is_flag=True,
              help="Show which flows would run and exit; no device or APK needed")
@click.option("--shard", type=int, default=None,
              help="Run only flows for this shard (1-based, use with --shards)")
@click.option("--shards", type=int, default=None,
              help="Total number of shards (use with --shard)")
@click.pass_context
def main(
    ctx: click.Context,
    flow: str,
    tags: str,
    serial: str | None,
    skip_install: bool,
    allow_stale_apk: bool,
    maestro_max_retries: int,
    maestro_cleanup: str,
    junit_dir: str | None,
    dry_run: bool,
    shard: int | None,
    shards: int | None,
    deps: DeviceDeps | None = None,
) -> int:
    deps = deps or _default_device_deps()

    serial = serial or os.environ.get("SERIAL") or ""

    if dry_run:
        flow_target = (ROOT / flow).resolve()
        all_flows = collect_flows(flow_target)
        tag_list = [t.strip() for t in tags.split(",") if t.strip()]
        if shards is not None and shard is not None and 1 <= shard <= shards:
            flows = [f for i, f in enumerate(all_flows) if (i % shards) == (shard - 1)]
            logger.info(f"Dry run — shard {shard}/{shards}")
        else:
            flows = all_flows
        if tag_list:
            flows = filter_flows_by_tag(flows, tag_list) if flows else []
        tag_desc = f"tag filter ({tag_list or 'none'})" if tag_list else "no tag filter"

        logger.info("Dry run — no device or APK required")
        logger.info(f"Flow target  : {flow_target.relative_to(ROOT)}")
        logger.info(f"Total discovered: {len(all_flows)}")
        if shards is not None and shard is not None:
            logger.info(f"Shard {shard}/{shards}: {len(flows)} flows")
        logger.info(f"After {tag_desc}: {len(flows)}")
        logger.info("\nFlows that would run:")
        for f in flows:
            logger.info(f"  {f.relative_to(ROOT)}")

        seed_variants = {"seed-task", "seed_task", "seedtask"}
        seed_dep_flows = [
            f.relative_to(ROOT) for f in flows
            if any(v in f.read_text() for v in seed_variants)
        ]
        if seed_dep_flows:
            logger.info(f"\nFlows referencing seed-task dependency ({len(seed_dep_flows)}):")
            for f in seed_dep_flows:
                logger.info(f"  {f}")

        return ctx.exit(EXIT_PASS)

    # ── Acquire device ─────────────────────────────────────────────────────────
    # No `if serial else None` guard: an empty pin means "whatever device is
    # available", not "no device". Guarding on it made every run with an unset
    # SERIAL fail before adb was ever called, which took out `just gm agenda`
    # (gate-maestro passes SERIAL="").
    logger.info("Acquiring device…")
    acq = deps.acquire_device(serial)
    if not acq:
        logger.error(f"Could not obtain a device: {_EXIT_REASON.get(acq.exit_code, 'unknown')}.")
        logger.error(deps.adb("devices") or "")
        return ctx.exit(EXIT_FAIL)
    # From here on the acquired serial is authoritative — using the original
    # (possibly empty) `serial` for install and flows would target nothing.
    serial = acq.serial
    logger.info(f"Device ready: {serial}")

    logger.info(f"=== Maestro on {serial} ({deps.device_model(serial)}) ===")

    # ── Wake + unlock + IME workaround ─────────────────────────────────────────
    deps.adb_on(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    deps.adb_on(serial, "shell", "input", "keyevent", "82")
    deps.adb_on(serial, "shell", "settings", "put", "secure",
                "show_ime_with_hard_keyboard", "0")

    # ── Install APK ────────────────────────────────────────────────────────────
    if skip_install:
        logger.info("Skipping install (--skip-install)")
    else:
        logger.info("=== Installing debug APK ===")
        cp = deps.gradle_install_debug(serial)
        if cp.returncode != 0:
            logger.error(f"installDebug failed (rc={cp.returncode})")
            return ctx.exit(EXIT_FAIL)

    # ── APK provenance ─────────────────────────────────────────────────────────
    if not allow_stale_apk:
        expected_sha = deps.git_sha()
        installed_sha = deps.apk_sha_from_device(serial)
        if not installed_sha:
            logger.error("Cannot read build id from installed APK.")
            logger.error("The installed binary is unidentifiable; results prove nothing about this checkout.")
            return ctx.exit(EXIT_FAIL)
        if installed_sha != expected_sha:
            logger.error("Installed APK is not this checkout's build.")
            logger.error(f"  checkout: g{expected_sha}")
            logger.error(f"  installed: g{installed_sha}")
            return ctx.exit(EXIT_FAIL)
        logger.info(f"APK provenance OK: g{installed_sha}")
    else:
        expected_sha = None

    # ── Clear logcat ───────────────────────────────────────────────────────────
    deps.adb_on(serial, "logcat", "-c")

    # ── Collect flows ─────────────────────────────────────────────────────────
    flow_target = (ROOT / flow).resolve()
    all_flows = collect_flows(flow_target)
    if not all_flows:
        logger.error(f"No flow files matched '{flow_target}'")
        return ctx.exit(EXIT_FAIL)

    # Sharding: apply before tag filtering, matching e2e-shard.sh behaviour
    # (shard of full list, not of filtered list).
    if shards is not None and shard is not None:
        if not (1 <= shard <= shards):
            logger.error(f"--shard must be between 1 and --shards (got {shard}, {shards})")
            return ctx.exit(EXIT_FAIL)
        sharded: list[Path] = [
            f for i, f in enumerate(all_flows)
            if (i % shards) == (shard - 1)
        ]
        logger.info(f"Shard {shard}/{shards}: {len(sharded)} of {len(all_flows)} flows")
        flows = sharded
    else:
        flows = all_flows

    tag_list = [t.strip() for t in tags.split(",") if t.strip()]
    if tag_list:
        flows = filter_flows_by_tag(flows, tag_list)
        if not flows:
            logger.error(f"No flow files carry tag(s): {tags}")
            return ctx.exit(EXIT_FAIL)

    if not flows:
        logger.error("No flows remain after sharding and tag filtering")
        return ctx.exit(EXIT_FAIL)

    logger.info(f"Running {len(flows)} flow(s):")
    for f in flows:
        logger.info(f"  {f.relative_to(ROOT)}")

    # ── Run each flow ──────────────────────────────────────────────────────────
    junit_path = Path(junit_dir) if junit_dir else ROOT / "build" / "maestro-results"
    passed: list[str] = []
    failed: list[str] = []
    # The moment the run starts: a JUnit report older than this belongs to an
    # earlier run, and counting it as this run's report is how a flow that
    # never executed still looks like it passed.
    run_started_at = time.time()

    for flow_path in flows:
        flow_name = flow_path.stem
        logger.info(f"\n=== {flow_name} ===")

        if deps.device_alive(serial):
            deps.adb_on(serial, "shell", "am", "force-stop", APP_ID)
            time.sleep(1)
            deps.adb_on(serial, "shell", "am", "start", "-n", f"{APP_ID}/.MainActivity")
            deps.wait_for_app_resumed(serial)

        ok, run_id = run_one_flow(
            flow_path=flow_path,
            serial=serial,
            junit_dir=junit_path,
            skip_install=skip_install,
            allow_stale=allow_stale_apk,
            max_retries=maestro_max_retries,
            expected_sha=expected_sha or "",
            deps=deps,
        )

        if ok:
            passed.append(flow_name)
            deps.cleanup_run_fixtures(serial, run_id, maestro_cleanup, deps)
        else:
            failed.append(flow_name)
            deps.capture_artifacts(serial, flow_name, junit_path / flow_name, deps)

    # ── Every selected flow must have reported ────────────────────────────────
    #
    # A flow that Maestro never executed leaves no JUnit file. Without this
    # check the flow is simply absent from `passed` and `failed`, the run ends
    # with "flows passed", and the green says nothing about the flow that was
    # supposed to run. The report must also be *this* run's report: a file left
    # over from an earlier run is not evidence.
    unreported = [
        flow.stem
        for flow in flows
        if not _report_is_fresh(junit_path / f"{flow.stem}.xml", run_started_at)
    ]
    if unreported:
        logger.error(
            f"{len(unreported)} flow(s) produced no JUnit report and cannot be "
            f"counted as passed: {', '.join(unreported)}"
        )
        logger.error(
            "A green run must mean every selected flow ran. Investigate the "
            "device state and the flow output; do not lower the bar."
        )
        return ctx.exit(EXIT_FAIL)

    # ── Crash triage ──────────────────────────────────────────────────────────
    if deps.device_alive(serial):
        logcat = deps.adb_on(serial, "shell", "logcat", "-d") or ""
        crashes = re.findall(
            r"FATAL EXCEPTION|AndroidRuntime.*com\.singularity\.todo",
            logcat, re.IGNORECASE
        )
        if crashes:
            logger.error("=== App crashes detected in logcat ===")
            for c in crashes[:20]:
                logger.error(c)
    else:
        logger.warning("=== No device at end of run; crash triage skipped ===")

    # ── Summary ───────────────────────────────────────────────────────────────
    if passed:
        logger.info(f"Passed ({len(passed)}): {', '.join(passed)}")
    if failed:
        logger.error(f"Failed ({len(failed)}): {', '.join(failed)}")

    if not failed:
        logger.info("=== Maestro flows passed ===")
    else:
        logger.error("=== Maestro flows failed ===")

    return ctx.exit(EXIT_FAIL if failed else EXIT_PASS)


# Import at bottom to avoid circular import with _runner → _device → _provenance → …
from emulator.maestro._runner import _EXIT_REASON, run_one_flow  # noqa: E402

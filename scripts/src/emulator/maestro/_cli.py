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
from emulator.maestro._bridge import Bridge
from emulator.maestro._fixtures import reset_session
from emulator.maestro._flow import collect_flows, filter_flows_by_tag
from emulator.maestro._lock import DeviceLock, DeviceLocked
from emulator.maestro._policy import PolicyError, load_policy
from emulator.maestro._types import (
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


def _install_debug(serial: str, deps: DeviceDeps) -> str | None:
    """Install the debug APK, and survive a device that dies under the install.

    The install is the one step where a dying device ends the whole run: the
    per-flow recovery has not started yet, and a device that vanished mid
    install takes the whole suite with it. On this host the emulator is
    restarted by the system often enough that this is a real occurrence, not a
    hypothetical one.

    So a failed install is read the way a failed flow is read: the device is
    asked whether it is there. A device that answers means the *build* failed
    and retrying will not help; a device that does not answer means the AVD
    is rebooted and the install is tried again.

    Returns:
        The serial the APK was installed on, or None when the install could not
        be completed.
    """
    for attempt in range(2):
        cp = deps.gradle_install_debug(serial)
        if cp.returncode == 0:
            return serial
        health = deps.probe_device_health(serial)
        if health.state == "alive":
            logger.error(
                f"installDebug failed (rc={cp.returncode}) and the device "
                f"answers ({health.detail}) — the build failed, not the device"
            )
            return None
        logger.warning(
            f"installDebug failed (rc={cp.returncode}): {health.detail} — "
            f"rebooting the AVD (attempt {attempt + 1})..."
        )
        acquired = deps.acquire_device(serial)
        if not acquired:
            logger.error(
                f"could not recover {serial}: "
                f"{_EXIT_REASON.get(acquired.exit_code, 'unknown')}"
            )
            return None
        serial = acquired.serial
    logger.error("installDebug failed twice; the device is not usable")
    return None


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
    epilog=(
        "SERIAL, FLOW and TAGS env vars are also accepted for device, flow and "
        "tag selection — the `just` recipes pass them that way."
    ),
)
@click.option("--flow", "-f", "flow", default=None,
              help="Flow directory, file, or glob (default: Maestro/flows)")
@click.option("--tags", "-t", "tags", default=None,
              help="Comma-separated tags to filter; flow must declare one of them")
@click.option("--serial", "-s", "serial", default=None,
              help="Pin a specific device serial (also set via SERIAL env var)")
@click.option("--skip-install", is_flag=True,
              help="Skip APK install (reuse whatever is on the device)")
@click.option("--allow-stale-apk", is_flag=True,
              help="Do not verify that the installed APK matches this checkout")
@click.option("--maestro-max-retries", type=int, default=2,
              help="Max device-recovery retries per flow (default: 2)")
@click.option("--maestro-cleanup", type=click.Choice(["all", "off"]),
              default="all", help="Fixture cleanup scope (default: all)")
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

    # The environment is a first-class way to select what runs. The `just`
    # recipes cannot pass a flag through to this script — they set the
    # variables instead — and until these three were read here, `FLOW` and
    # `TAGS` were silently dropped: `just gm agenda` ran the whole suite
    # (94 flows) instead of the agenda-tagged subset, and
    # `just ui-maestro <flow>` ran everything too. A selection that is
    # ignored is worse than no selection: the run looks like it did what was
    # asked and the report says otherwise.
    serial = serial or os.environ.get("SERIAL") or ""
    flow = flow or os.environ.get("FLOW") or "Maestro/flows"
    tags = tags if tags is not None else os.environ.get("TAGS", "")
    tags = tags or ""

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

    # ── Bridge policy ───────────────────────────────────────────────────────────
    #
    # The policy says which device commands the host bridge may run. It is read
    # before the device is even acquired: a run whose policy cannot be read
    # would fail every flow that needs the bridge, one by one, and the report
    # would read as a broken suite rather than as a broken configuration.
    try:
        policy = load_policy()
    except PolicyError as exc:
        logger.error(f"The bridge policy could not be loaded: {exc}")
        return ctx.exit(EXIT_FAIL)

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

    # ── One run per device ────────────────────────────────────────────────────
    #
    # A run drives the device: it installs an APK, resets the session and starts
    # a device server on the emulator. A second run on the same device does not
    # queue behind the first — the two device servers kill each other and every
    # flow fails within a second with `DeviceServerDiedException`, which reads
    # like a broken suite and is really two runners sharing one emulator.
    #
    # The lock is released when this process exits; a lock whose owner was
    # killed is taken over by the next run, so a crash cannot lock the device
    # out of use.
    lock = DeviceLock(serial)
    try:
        lock.acquire()
    except DeviceLocked as exc:
        logger.error(str(exc))
        return ctx.exit(EXIT_FAIL)
    # Released when the command returns — not when the process exits: the run
    # is what holds the device, and a process that runs one suite after another
    # must not lock itself out of the second one.
    ctx.call_on_close(lock.release)

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
        serial = _install_debug(serial, deps)
        if serial is None:
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

    # ── Host bridge ────────────────────────────────────────────────────────────
    #
    # A Maestro script cannot reach the host: the engine has no shell, no Java
    # host access, and no `env` object. The flows that need to touch the device's
    # shell — set a system property, damage the app's encrypted store — need a
    # channel, and HTTP is the only one Maestro offers. The bridge is that
    # channel: it runs a *device* command through adb, and only the commands its
    # policy allows. See `maestro._bridge` and `maestro._policy`.
    bridge = Bridge(serial, policy=policy)
    bridge_url = bridge.start()
    logger.info(f"Host bridge: {bridge_url}")

    for index, flow_path in enumerate(flows):
        flow_name = flow_path.stem
        logger.info(f"\n=== {flow_name} ===")

        # The device is wiped before *every* flow, the first one included: the
        # device carries whatever the run before this one left on it, and a flow
        # that asserts an empty list must not be able to see it.
        #
        # This is also what a failing flow's leftovers meet: the artifacts
        # (screenshot, screen tree, logcat) are captured below, and the next
        # flow's wipe takes the rows away afterwards. Leaving them for the rest
        # of the shard makes every later flow that asserts an empty list fail on
        # the previous flow's data, and a suite whose failures cascade is a suite
        # that reports one defect as forty.
        if maestro_cleanup == "all":
            # The wipe is the whole scope: it needs no list of titles, which is
            # why the narrow scope is gone. The second argument is the run id
            # the narrow scope swept by; the contract still carries it, and the
            # wipe does not read it.
            deps.cleanup_run_fixtures(serial, "", maestro_cleanup, deps)

        # The session is reset before the flow, not after it: a flow that ends
        # signed out must not decide the session of the flow after it, and the
        # reset is a different kind of fixture from the content wipe above.
        reset_session(serial, deps)

        try:
            ok, _run_id = run_one_flow(
                flow_path=flow_path,
                serial=serial,
                junit_dir=junit_path,
                skip_install=skip_install,
                allow_stale=allow_stale_apk,
                max_retries=maestro_max_retries,
                expected_sha=expected_sha or "",
                deps=deps,
                bridge_url=bridge_url,
            )
        except DeviceLostError as exc:
            # The device is gone. The remaining flows did not run, and they are
            # not failures: they are flows that were never reached, and the
            # report has to say which. Continuing would turn one lost device
            # into ninety failures and hide the cause.
            not_run = [f.stem for f in flows[index + 1:]]
            logger.error(f"the run stopped: {exc}")
            logger.error(
                f"{len(not_run)} flow(s) did not run because the device was lost: "
                f"{', '.join(not_run) if not_run else 'none'}"
            )
            bridge.stop()
            logger.error("=== Maestro flows did not finish — the device was lost ===")
            return ctx.exit(EXIT_FAIL)

        if ok:
            passed.append(flow_name)
        else:
            failed.append(flow_name)
            deps.capture_artifacts(serial, flow_name, junit_path / flow_name, deps)

    # The bridge has served every flow; nothing may reach the host after this.
    bridge.stop()

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
from emulator.maestro._runner import (  # noqa: E402
    _EXIT_REASON,
    DeviceLostError,
    run_one_flow,
)

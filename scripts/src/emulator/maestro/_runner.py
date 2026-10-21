"""maestro._runner — single-flow execution with retry and APK provenance."""
from __future__ import annotations

import subprocess
import time
from pathlib import Path

from loguru import logger as _logger

from emulator._loguru import setup_logger
from emulator._types import EXIT_CRASHED, EXIT_LOCK, EXIT_NOT_READY, EXIT_OK, EXIT_TIMEOUT
from emulator.maestro._bridge import BRIDGE_ENV_NAME
from emulator.maestro._types import (
    APP_ID,
    EXIT_FAIL,
    EXIT_PASS,
    ROOT,
    DeviceDeps,
    _default_device_deps,
)

#: How long to wait before retrying a flow that failed while the device was
#: merely slow. Long enough for the host's queue to drain a little, short
#: enough not to stretch a 94-flow run.
RETRY_BACKOFF_SEC = 3.0

#: Why an acquisition failed, so a recovery that gives up says which of the four
#: it hit. "Could not obtain a device" alone cannot tell a wedged emulator from a
#: lock timeout, and those have different remedies.
_EXIT_REASON = {
    EXIT_NOT_READY: "no ready device and none could be started",
    EXIT_CRASHED: "emulator exited during start-up",
    EXIT_TIMEOUT: "emulator never became ready",
    EXIT_LOCK: "another acquire held the startup lock",
}

# idempotent: uses the shared logger configured by the first module to import it.
setup_logger(is_ci=False)
logger = _logger


def _log_failure(stdout: str, stderr: str) -> None:
    """Log why a flow failed, from both streams.

    Maestro reports a flow that could not even be *parsed* on stderr and
    nothing on stdout — "Unknown Property: contentDescription at …" is the
    whole story of that flow, and without stderr the runner printed an empty
    line and left the reader with a flow that failed and no reason. Logging
    stdout alone hid the class of failure that has nothing to do with the
    app.
    """
    for label, text in (("stdout", stdout), ("stderr", stderr)):
        if text and text.strip():
            logger.error(f"--- maestro {label} ---\n{text}")


def run_one_flow(
    flow_path: Path,
    serial: str,
    junit_dir: Path,
    skip_install: bool,
    allow_stale: bool,
    max_retries: int,
    expected_sha: str,
    deps: DeviceDeps | None = None,
    bridge_url: str = "",
) -> tuple[bool, str]:
    """Run one Maestro flow. Returns (passed, run_id).

    Args:
        deps: Device-layer dependency bundle. Tests pass a fake here to avoid
              patching module-level globals. Defaults to the real platform impl.
        bridge_url: Where the flow can reach the host. Passed to the flow as the
            ``E2E_BRIDGE_URL`` global — Maestro exposes each ``--env`` entry as a
            bare global, so a script reads ``E2E_BRIDGE_URL`` and not
            ``env.E2E_BRIDGE_URL``. Empty means the flow has no host channel, and
            a flow that needs one says so rather than passing on its next
            assertion.
    """
    if deps is None:
        deps = _default_device_deps()

    flow_name = flow_path.stem
    junit_dir.mkdir(parents=True, exist_ok=True)
    junit_file = junit_dir / f"{flow_name}.xml"

    for attempt in range(max_retries + 1):
        run_id = f"r{int(time.time() * 1000) % 10**9}{attempt}"
        # The report of an earlier attempt is not evidence about this one. A
        # leftover file would make this attempt look like it failed the way the
        # previous one did — and the previous one may have failed with a dead
        # device, which is the difference between retrying and giving up.
        junit_file.unlink(missing_ok=True)
        cmd = [
            "maestro", "test", str(flow_path),
            "--env", f"RUN_ID={run_id}",
            "--format", "JUNIT",
            "--output", str(junit_file),
        ]
        if bridge_url:
            cmd += ["--env", f"{BRIDGE_ENV_NAME}={bridge_url}"]
        logger.info(f"run id: {run_id}")

        cp = deps.try_run(cmd, cwd=ROOT, timeout=300)
        status = cp.returncode if cp else -1
        stdout = cp.stdout if cp else ""

        if status == 0:
            if attempt > 0 and not allow_stale:
                installed_sha = deps.apk_sha_from_device(serial)
                if installed_sha and installed_sha != expected_sha:
                    logger.warning(
                        f"device was relaunched and carries g{installed_sha}, "
                        f"not g{expected_sha}; reinstalling..."
                    )
                    return False, run_id
            return True, run_id

        stderr = cp.stderr if cp else ""
        # A device that dies mid-flow is written into the flow's JUnit report,
        # not into the process's stderr: the report's <failure> element carries
        # Maestro's gRPC stack. Reading stderr alone counts a dead device as a
        # broken flow, and a broken flow is never retried.
        device_failed = (
            deps.maestro_saw_device_failure(stderr)
            or deps.junit_reports_device_failure(junit_file)
        )
        if not device_failed and deps.device_alive(serial):
            _log_failure(stdout, stderr)
            return False, run_id

        if attempt >= max_retries:
            _log_failure(stdout, stderr)
            return False, run_id

        # A device-level signature is not the same as a dead device. Under a
        # loaded host Maestro's own adb socket times out and prints one, while
        # the device is working — so the first question is what kind of failure
        # this is, not whether to recover.
        health = deps.probe_device_health(serial)
        if health.state == "alive":
            # The device answers. The host was busy, not the device. Retry the
            # flow and leave the adb server alone: resetting it would hide the
            # device from every other client for the four seconds it takes to
            # come back, which is how one busy host turns into several
            # "failures" at once.
            logger.warning(
                f"{flow_name}: a device-level failure, but the device answers "
                f"({health.detail}). The host was busy, not the device — "
                f"retrying without resetting the adb server "
                f"(attempt {attempt + 1})..."
            )
            time.sleep(RETRY_BACKOFF_SEC)
            continue

        # The device did not answer. Whether an emulator process is still
        # running decides what recovery means: a live emulator needs its adb
        # server restarted, a dead one needs the AVD booted again. Asking the
        # host directly is the only way to tell — adb cannot answer while the
        # server it depends on is the thing in question.
        if deps.emulator_process_alive():
            logger.warning(
                f"Device unusable during {flow_name} ({health.detail}); the "
                f"emulator is still running, so resetting the adb server "
                f"(attempt {attempt + 1})..."
            )
            deps.reset_adb_server()
        else:
            logger.warning(
                f"Device unusable during {flow_name} ({health.detail}) and no "
                f"emulator process is running — booting the AVD again "
                f"(attempt {attempt + 1})..."
            )

        if not deps.device_alive(serial):
            # acquire_device boots the AVD when nothing ready is attached, which
            # is the whole point of calling it here: after an adb-server reset the
            # emulator may itself be gone, and a mere readiness re-check would
            # return None and lose the flow.
            acquired = deps.acquire_device(serial)
            if not acquired:
                logger.error(
                    f"could not recover {serial}: "
                    f"{_EXIT_REASON.get(acquired.exit_code, 'unknown')}"
                )
                return False, run_id
            serial = acquired.serial
        else:
            out = deps.adb("devices") or ""
            for line in out.splitlines()[1:]:
                parts = line.split()
                if len(parts) == 2 and parts[1] == "device":
                    serial = parts[0]
                    break

        deps.adb_on(
            serial, "shell", "settings", "put", "secure",
            "show_ime_with_hard_keyboard", "0",
        )

        if not skip_install:
            install_cp = deps.gradle_install_debug(serial)
            if install_cp.returncode != 0:
                logger.error(f"installDebug failed (rc={install_cp.returncode})")
                return False, run_id
            if not allow_stale:
                installed_sha = deps.apk_sha_from_device(serial)
                if installed_sha and installed_sha != expected_sha:
                    logger.error(
                        f"Relaunched device holds g{installed_sha}, not g{expected_sha}; "
                        "results would describe wrong binary."
                    )
                    return False, run_id
        else:
            if not allow_stale:
                installed_sha = deps.apk_sha_from_device(serial)
                if not installed_sha or installed_sha != expected_sha:
                    logger.warning(
                        "device was relaunched and SKIP_INSTALL=1, "
                        "so binary is whatever the snapshot held."
                    )

    return False, run_id

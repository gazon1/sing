"""maestro._runner — single-flow execution with retry and APK provenance."""
from __future__ import annotations

import subprocess
import time
from pathlib import Path

from loguru import logger as _logger

from emulator._loguru import setup_logger
from emulator.maestro._types import APP_ID, EXIT_FAIL, EXIT_PASS, DeviceDeps, ROOT, _default_device_deps

# idempotent: uses the shared logger configured by the first module to import it.
setup_logger(is_ci=False)
logger = _logger


def run_one_flow(
    flow_path: Path,
    serial: str,
    junit_dir: Path,
    skip_install: bool,
    allow_stale: bool,
    max_retries: int,
    expected_sha: str,
    deps: DeviceDeps | None = None,
) -> tuple[bool, str]:
    """Run one Maestro flow. Returns (passed, run_id).

    Args:
        deps: Device-layer dependency bundle. Tests pass a fake here to avoid
              patching module-level globals. Defaults to the real platform impl.
    """
    if deps is None:
        deps = _default_device_deps()

    flow_name = flow_path.stem
    junit_dir.mkdir(parents=True, exist_ok=True)
    junit_file = junit_dir / f"{flow_name}.xml"

    for attempt in range(max_retries + 1):
        run_id = f"r{int(time.time() * 1000) % 10**9}{attempt}"
        cmd = [
            "maestro", "test", str(flow_path),
            "--env", f"RUN_ID={run_id}",
            "--format", "JUNIT",
            "--output", str(junit_file),
        ]
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
        if not deps.maestro_saw_device_failure(stderr) and deps.device_alive(serial):
            logger.error(stdout)
            return False, run_id

        if attempt >= max_retries:
            logger.error(stdout)
            return False, run_id

        logger.warning(
            f"Device unusable during {flow_name}; recovering (attempt {attempt + 1})..."
        )
        deps.reset_adb_server()

        if not deps.device_alive(serial):
            recovered = deps.ensure_device(serial)
            if not recovered:
                return False, run_id
            serial = recovered
        else:
            out = deps.adb("devices") or ""
            for line in out.splitlines()[1:]:
                parts = line.split()
                if len(parts) == 2 and parts[1] == "device":
                    serial = parts[0]
                    break

        deps.adb(
            "-s", serial, "shell", "settings", "put", "secure",
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

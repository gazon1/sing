"""maestro._artifacts — failure artifact capture (screenshot, logcat)."""
from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from loguru import logger as _logger

from emulator._loguru import setup_logger

if TYPE_CHECKING:  # pragma: no cover — import cycle guard, annotations only
    from emulator.maestro._types import DeviceDeps

# idempotent; uses the shared logger configured by the first module to import it.
setup_logger(is_ci=False)
logger = _logger


def capture_artifacts(
    serial: str, flow_name: str, artifact_dir: Path, deps: "DeviceDeps",
) -> None:
    """Screencap + logcat at failure time. Best-effort.

    Every call goes through ``deps.adb_on`` so ``-s <serial>`` is always present.
    It was previously ``deps.adb(serial, …)``, which built ``adb <serial> shell
    screencap …`` — an unknown command to adb — so a failing flow produced no
    screenshot and no logcat while appearing to succeed.
    """
    if not deps.device_alive(serial):
        return
    artifact_dir.mkdir(parents=True, exist_ok=True)

    # `screencap -p <file>` prints nothing on success, so the return value is
    # the only success signal. `pull` is an adb command, not a shell one
    # (`adb shell pull` exits 127), and its progress goes to stderr — so the
    # file on disk is the evidence that it worked.
    remote = f"/sdcard/{flow_name}_screen.png"
    local = artifact_dir / f"{flow_name}_screen.png"
    if deps.adb_on(serial, "shell", "screencap", "-p", remote, timeout=60) is not None:
        # A longer deadline than adb_on's 5s default: a screenshot or a pull can
        # outlast it on a loaded runner, and a timeout would drop the evidence.
        deps.adb_on(serial, "pull", remote, str(local), timeout=120)
        # The device copy is removed only once the host has it. A pull that
        # failed must leave the only copy on the device.
        if local.is_file():
            deps.adb_on(serial, "shell", "rm", "-f", remote)
            logger.info(f"Screenshot: {local}")

    # Logcat buffer. Also past adb_on's default: it is the crash/ANR evidence.
    logcat_file = artifact_dir / f"{flow_name}_logcat.txt"
    logcat_out = deps.adb_on(serial, "shell", "logcat", "-d", timeout=120)
    if logcat_out:
        logcat_file.write_text(logcat_out)
        logger.info(f"Logcat: {logcat_file}")
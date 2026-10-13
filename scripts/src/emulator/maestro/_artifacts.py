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

    # Raw framebuffer screenshot
    remote = f"/sdcard/{flow_name}_screen.png"
    local = artifact_dir / f"{flow_name}_screen.png"
    if deps.adb_on(serial, "shell", "screencap", "-p", remote):
        deps.adb_on(serial, "shell", "pull", remote, str(local))
        deps.adb_on(serial, "shell", "rm", "-f", remote)
        logger.info(f"Screenshot: {local}")

    # Logcat buffer
    logcat_file = artifact_dir / f"{flow_name}_logcat.txt"
    logcat_out = deps.adb_on(serial, "shell", "logcat", "-d")
    if logcat_out:
        logcat_file.write_text(logcat_out)
        logger.info(f"Logcat: {logcat_file}")
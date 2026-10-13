"""maestro._artifacts — failure artifact capture (screenshot, logcat)."""
from __future__ import annotations

from pathlib import Path

from loguru import logger as _logger

from emulator._loguru import setup_logger

# idempotent; uses the shared logger configured by the first module to import it.
setup_logger(is_ci=False)
logger = _logger


def capture_artifacts(
    serial: str, flow_name: str, artifact_dir: Path, deps: "DeviceDeps",
) -> None:
    """Screencap + logcat at failure time. Best-effort."""
    if not deps.device_alive(serial):
        return
    artifact_dir.mkdir(parents=True, exist_ok=True)

    # Raw framebuffer screenshot
    remote = f"/sdcard/{flow_name}_screen.png"
    local = artifact_dir / f"{flow_name}_screen.png"
    if deps.adb(serial, "shell", "screencap", "-p", remote):
        deps.adb(serial, "shell", "pull", remote, str(local))
        deps.adb(serial, "shell", "rm", "-f", remote)
        logger.info(f"Screenshot: {local}")

    # Logcat buffer
    logcat_file = artifact_dir / f"{flow_name}_logcat.txt"
    logcat_out = deps.adb(serial, "shell", "logcat", "-d")
    if logcat_out:
        logcat_file.write_text(logcat_out)
        logger.info(f"Logcat: {logcat_file}")

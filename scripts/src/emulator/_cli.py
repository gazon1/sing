"""emulator._cli — Click CLI entry point with loguru structured logging."""
from __future__ import annotations

import shutil
import sys
from pathlib import Path

import click
from loguru import logger as _logger

from emulator._device import device_is_ready, find_ready
from emulator._startup import clear_stale_emulator, startup_lock, start_avd
from emulator._types import EXIT_LOCK, EXIT_NOT_READY, EXIT_OK, EXIT_TIMEOUT, EXIT_CRASHED, cfg

# ── loguru setup ───────────────────────────────────────────────────────────────
_logger.remove(0)

_is_ci = bool(getattr(sys, "_called_from_test", False) or __import__("os").environ.get("CI"))

if _is_ci:
    _logger.add(
        sys.stderr,
        format="<level>{level: <8}</level> | {message}",
        level="INFO",
        colorize=False,
    )
else:
    _logger.add(
        sys.stderr,
        format=(
            "<level><bold><fg cyan>{time:HH:mm:ss}</fg cyan></bold></level> "
            "<level>{level: <8}</level> "
            "<fg magenta>{name}</fg magenta>:{function}:{line} — <level>{message}</level>"
        ),
        level="DEBUG",
        colorize=True,
    )

logger = _logger


# ── Click command ──────────────────────────────────────────────────────────────

@click.command(
    help=(
        "Ensure a usable Android device is available; print its serial on stdout.\n\n"
        "Exit codes: 0 ok, 1 not ready / tool missing, 2 emulator died during start-up, "
        "3 boot timeout, 4 lock timeout."
    ),
    epilog=(
        "Environment: AVD, SERIAL, EMULATOR_TIMEOUT, EMULATOR_LOG, TMPDIR, ANDROID_AVD_HOME"
    ),
)
@click.option(
    "--avd", "-a", "avd", default=None,
    help=f"AVD name (default: {cfg.avd}, also set via AVD env var)",
)
@click.option(
    "--serial", "-s", "serial", default=None,
    help="pin a specific device serial (also set via SERIAL env var)",
)
@click.option(
    "--timeout", "-t", "timeout", type=int, default=None,
    help=f"boot timeout in seconds (default: {cfg.boot_timeout}, also set via EMULATOR_TIMEOUT env var)",
)
@click.option(
    "--verbose", "-v", is_flag=True,
    help="print detailed progress to stderr",
)
def main(
    avd: str | None,
    serial: str | None,
    timeout: int | None,
    verbose: bool,
) -> int:
    """Ensure a usable Android device is available; print its serial on stdout."""
    # Apply overrides to the shared config singleton.
    if avd is not None:
        cfg.avd = avd
    if serial is not None:
        cfg.pinned = serial
    if timeout is not None:
        cfg.boot_timeout = timeout
    cfg.verbose = verbose

    logger.debug(f"avd={cfg.avd} pinned={cfg.pinned} timeout={cfg.boot_timeout}")

    # Check emulator binary exists.
    if not Path("/usr/bin/emulator").exists() and not shutil.which("emulator"):
        logger.error("'emulator' binary not found in PATH.")
        return EXIT_NOT_READY

    # DISPLAY is required for the emulator window; warn if absent.
    import os as _os
    if not _os.environ.get("DISPLAY") and not _os.environ.get("WAYLAND_DISPLAY"):
        logger.warning("DISPLAY / WAYLAND_DISPLAY is not set; the emulator may fail to start.")

    # Physical device pin: never start an AVD.
    if cfg.pinned and not cfg.pinned.startswith("emulator-"):
        if device_is_ready(cfg.pinned):
            print(cfg.pinned)
            return EXIT_OK
        logger.error(f"Device '{cfg.pinned}' is present but not ready.")
        from emulator._device import adb
        logger.error(adb("devices") or "")
        return EXIT_NOT_READY

    # Fast path: something is already up.
    if serial := find_ready(cfg.pinned):
        logger.debug(f"found ready device: {serial}")
        print(serial)
        return EXIT_OK

    # Emulator pin set but not ready: the AVD crashed and needs restarting.
    if cfg.pinned:
        logger.warning(f"Pinned emulator '{cfg.pinned}' is not ready; restarting the AVD.")

    try:
        with startup_lock(cfg.boot_timeout):
            # Re-check inside the lock.
            if serial := find_ready(cfg.pinned):
                logger.debug(f"device ready after acquiring lock: {serial}")
                print(serial)
                return EXIT_OK
            rc = start_avd(cfg.pinned)
            return rc
    except TimeoutError:
        logger.error("Timed out waiting for another ensure-emulator run.")
        return EXIT_LOCK

    # Explicit fallback — main() must always return an int, not None.
    # Click's __call__ invokes ctx.exit(rv) where rv is the return value;
    # ctx.exit() raises Exit(rv) which becomes sys.exit(rv).  Returning
    # None would cause Click to call ctx.exit(None) → Exit() → sys.exit(0).
    return EXIT_NOT_READY

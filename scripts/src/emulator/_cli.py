"""emulator._cli — Click CLI entry point with loguru structured logging."""
from __future__ import annotations

import os
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path

import click
from loguru import logger as _logger

from emulator._loguru import setup_logger
from emulator._types import EXIT_NOT_READY

# ── loguru setup ───────────────────────────────────────────────────────────────
# Through the shared, idempotent setup_logger rather than a bare
# `_logger.remove(0)`. A bare remove consumes handler id 0, and any module that
# imports loguru later in the same process — sgate/log.py does exactly that — then
# finds no handler 0 and raises ValueError at import. That took out eleven
# unrelated sgate tests whenever an emulator test ran first in the same process.
_is_ci = bool(getattr(sys, "_called_from_test", False) or os.environ.get("CI"))
setup_logger(is_ci=_is_ci)
logger = _logger


# ── Dependency injection struct (makes main() testable) ────────────────────────


@dataclass
class EmulatorDeps:
    """Injectable dependencies for the emulator CLI — enables unit testing without a device.

    ``acquire_fn`` is the acquisition function (default: ``_startup.acquire_device``).
    All other fields mirror the ``Config`` fields the CLI applies overrides to.
    """
    # The actual implementation is imported lazily to avoid a module-level cycle.
    # Tests substitute a fake ``acquire_fn`` that returns a pre-built Acquisition.
    acquire_fn: callable = None  # type: ignore[assignment]

    avd: str = ""
    pinned: str = ""
    boot_timeout: int = 300
    lock_timeout: float = 60.0
    verbose: bool = False

    def __post_init__(self):
        if self.acquire_fn is None:
            # Lazy import to avoid cycle with emulator._startup
            from emulator import _startup
            self.acquire_fn = _startup.acquire_device


# Default singleton used by the CLI entry point.
# Defaults mirror Config so that omitting --avd/--timeout keeps the env-driven behaviour.
_emulator_deps: EmulatorDeps = EmulatorDeps(
    avd=os.environ.get("AVD", "Medium_Phone"),
    pinned=os.environ.get("SERIAL", ""),
    boot_timeout=int(os.environ.get("EMULATOR_TIMEOUT", "300")),
    lock_timeout=float(os.environ.get("EMULATOR_LOCK_TIMEOUT", "60")),
)


# ── Click command ──────────────────────────────────────────────────────────────

@click.command(
    help=(
        "Ensure a usable Android device is available; print its serial on stdout.\n\n"
        "Exit codes: 0 ok, 1 not ready / tool missing, 2 emulator died during start-up, "
        "3 boot timeout, 4 lock timeout."
    ),
    epilog=(
        "Environment: AVD, SERIAL, EMULATOR_TIMEOUT, EMULATOR_LOCK_TIMEOUT, "
        "EMULATOR_LOG, TMPDIR, ANDROID_AVD_HOME"
    ),
)
@click.option(
    "--avd", "-a", "avd", default=None,
    help=f"AVD name (default: from deps, also set via AVD env var)",
)
@click.option(
    "--serial", "-s", "serial", default=None,
    help="pin a specific device serial (also set via SERIAL env var)",
)
@click.option(
    "--timeout", "-t", "timeout", type=int, default=None,
    help=f"boot timeout in seconds (default: from deps, also set via EMULATOR_TIMEOUT env var)",
)
@click.option(
    "--verbose", "-v", is_flag=True,
    help="print detailed progress to stderr",
)
@click.pass_context
def main(
    ctx: click.Context,
    avd: str | None,
    serial: str | None,
    timeout: int | None,
    verbose: bool,
    *,  # force keyword-only so tests can inject deps via keyword
    _deps: EmulatorDeps | None = None,
) -> int:
    """Ensure a usable Android device is available; print its serial on stdout."""
    deps = _deps if _deps is not None else _emulator_deps

    # Apply CLI overrides to the deps struct.
    if avd is not None:
        deps.avd = avd
    if serial is not None:
        deps.pinned = serial
    if timeout is not None:
        deps.boot_timeout = timeout
    deps.verbose = verbose

    logger.debug(
        f"avd={deps.avd} pinned={deps.pinned} timeout={deps.boot_timeout} "
        f"lock_timeout={deps.lock_timeout}"
    )

    # Check emulator binary exists.
    if not Path("/usr/bin/emulator").exists() and not shutil.which("emulator"):
        logger.error("'emulator' binary not found in PATH.")
        return ctx.exit(EXIT_NOT_READY)

    # DISPLAY is required for the emulator window; warn if absent.
    if not os.environ.get("DISPLAY") and not os.environ.get("WAYLAND_DISPLAY"):
        logger.warning("DISPLAY / WAYLAND_DISPLAY is not set; the emulator may fail to start.")

    # stdout carries the serial and nothing else: `SERIAL=$(ensure-emulator)`
    # must keep working, so every diagnostic above went to stderr.
    result = deps.acquire_fn(deps.pinned)
    if result.serial:
        print(result.serial)

    # ctx.exit(), not a bare `return rc`. Click 8's standalone mode DISCARDS a
    # callback's return value and exits 0 — its source says "it's not safe to
    # ctx.exit(rv) here!" — so returning the code made this command exit 0 on
    # every failure, and `set -e` callers could never tell. ctx.exit(rc) becomes
    # sys.exit(rc) under a console script, and still returns rc for
    # standalone_mode=False callers such as the tests.
    return ctx.exit(result.exit_code)
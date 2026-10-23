"""sgate.log — loguru logger configured for sgate.

loguru is structured logging (like loguru) + stderr output.
In CI (CI=true) the format is compact and CI-annotation-friendly.
In local mode it is human-readable with colours.

Usage:
    from sgate.log import logger
    logger.info("message")
    logger.warning("message")
    logger.error("message")
"""
from __future__ import annotations

import sys

from loguru import logger as _logger

# Remove the default handler loguru adds at import time.
#
# Guarded, because handler id 0 is process-global and another module may already
# have claimed it. `emulator._loguru.setup_logger` does the same removal, so
# whichever of the two imported second used to raise
# "ValueError: There is no existing handler with id 0" — an import-time failure
# that surfaced as eleven unrelated errors in test_sgate_output whenever an
# emulator test happened to run first under `unittest discover`.
try:
    _logger.remove(0)
except ValueError:
    pass


def _is_ci() -> bool:
    import os
    return bool(os.environ.get("CI"))


def _level(default: str) -> str:
    """The log level for this run.

    The local level used to be DEBUG, and every command that touches the CLI
    printed thirty lines of "loaded sgate.commands.x" before it printed
    anything else — the noise is the size of the answer it precedes. INFO is
    the default; `SGATE_LOG_LEVEL=DEBUG` asks for the rest.
    """
    import os

    return os.environ.get("SGATE_LOG_LEVEL", default).upper()


# ── stderr handler (all logs go here, not stdout) ────────────────────────────

if _is_ci():
    # CI: compact format without colours — GitHub Actions ::error annotations
    # come from gate_fail()/gate_warn() in output.py, not from logger.
    _logger.add(
        sys.stderr,
        format="<level>{level: <8}</level> | {message}",
        level="INFO",
        colorize=False,
    )
else:
    # Local: colourised, human-readable
    _logger.add(
        sys.stderr,
        format=(
            "<level><bold><fg cyan>{time:HH:mm:ss}</fg cyan></bold></level> "
            "<level>{level: <8}</level> "
            "<fg magenta>{name}</fg magenta>:{function}:{line} — {message}"
        ),
        level=_level("INFO"),
        colorize=True,
    )

# ── public logger ──────────────────────────────────────────────────────────────

logger = _logger

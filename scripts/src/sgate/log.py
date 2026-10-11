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

# Remove the default handler loguru adds at import time
_logger.remove(0)


def _is_ci() -> bool:
    import os
    return bool(os.environ.get("CI"))


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
        level="DEBUG",
        colorize=True,
    )

# ── public logger ──────────────────────────────────────────────────────────────

logger = _logger

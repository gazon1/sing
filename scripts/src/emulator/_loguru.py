"""emulator._loguru — shared loguru setup with idempotent handler management."""
from __future__ import annotations

import os
import sys

from loguru import logger as _logger

# Keep track of whether we have already configured the handler.
_CONFIGURED = False


def setup_logger(*, is_ci: bool = False) -> None:
    """Configure loguru stderr output exactly once per process.

    Idempotent: subsequent calls are no-ops. This lets every module that
    imports loguru safely call ``_logger.remove(0)`` without risking
    "handler not found" ValueError or removing a handler set up by another
    module.
    """
    global _CONFIGURED
    if _CONFIGURED:
        return
    _CONFIGURED = True

    # Remove the default handler that loguru adds at import time.
    try:
        _logger.remove(0)
    except ValueError:
        pass

    if is_ci:
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


def get_logger():
    """Return the shared logger instance."""
    return _logger

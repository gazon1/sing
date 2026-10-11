"""sgate — unified CLI package for singularity-todo gate scripts.

Public exports
--------------
click            : the Click library used for all CLI commands
gate_fail        : emit one gate violation (structured + GitHub Actions annotation)
gate_warn        : emit a non-blocking warning
logger           : loguru logger — structured logging for CI (stderr output)
KiwiUnavailable  : raised when Kiwi TCMS cannot be loaded
KiwiResult       : NamedTuple returned on successful Kiwi load
GateCommand      : base Click command class for all gate commands
CLEAN, VALIDATION_FAILURE, IO_ERROR, CONFIG_ERROR, KIWI_UNAVAILABLE, INTERNAL_ERROR
                  : standard exit codes

Internal modules (not published as public API):
    kiwi, log, output, repo, gates, cli, commands.base
"""
from __future__ import annotations

__version__ = "0.1.0"

from sgate import kiwi
from sgate import log
from sgate import output
from sgate import repo
from sgate import gates
from sgate.commands.base import (
    GateCommand,
    CLEAN,
    VALIDATION_FAILURE,
    IO_ERROR,
    CONFIG_ERROR,
    KIWI_UNAVAILABLE,
    INTERNAL_ERROR,
)

# Public re-exports
click = __import__("click")

logger = log.logger  #: loguru.logger — structured logging
gate_fail = output.gate_fail
gate_warn = output.gate_warn
is_ci = output.is_ci

KiwiUnavailable = kiwi.KiwiUnavailable
KiwiResult = kiwi.KiwiResult

__all__ = [
    "__version__",
    "click",
    "logger",
    "gate_fail",
    "gate_warn",
    "is_ci",
    "KiwiUnavailable",
    "KiwiResult",
    "GateCommand",
    "CLEAN",
    "VALIDATION_FAILURE",
    "IO_ERROR",
    "CONFIG_ERROR",
    "KIWI_UNAVAILABLE",
    "INTERNAL_ERROR",
    "kiwi",
    "log",
    "output",
    "repo",
    "gates",
]

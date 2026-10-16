"""sgate — unified CLI package for singularity-todo gate scripts.

Public exports
--------------
click            : the Click library used for all CLI commands
gate_fail        : emit one gate violation (structured + GitHub Actions annotation)
gate_warn        : emit a non-blocking warning
logger           : loguru logger — structured logging for CI (stderr output)
GateCommand      : base Click command class for all gate commands
CLEAN, VALIDATION_FAILURE, IO_ERROR, CONFIG_ERROR, INTERNAL_ERROR
                  : standard exit codes

`KIWI_UNAVAILABLE` (4) is still defined for compatibility with the generated
gate shims, but no gate emits it: the Kiwi TCMS stand is gone, and the three
traceability gates that used to declare themselves Kiwi-dependent never needed
it. A soft skip that reads as a pass is worse than a hard failure, so `run-all`
no longer treats a non-zero exit as green.

Internal modules (not published as public API):
    log, output, repo, gates, cli, commands.base
"""
from __future__ import annotations

__version__ = "0.1.0"

from sgate import gates, log, output, repo
from sgate.commands.base import (
    CLEAN,
    CONFIG_ERROR,
    INTERNAL_ERROR,
    IO_ERROR,
    KIWI_UNAVAILABLE,
    VALIDATION_FAILURE,
    GateCommand,
)

# Public re-exports
click = __import__("click")

logger = log.logger  #: loguru.logger — structured logging
gate_fail = output.gate_fail
gate_warn = output.gate_warn
is_ci = output.is_ci

__all__ = [
    "__version__",
    "click",
    "logger",
    "gate_fail",
    "gate_warn",
    "is_ci",
    "GateCommand",
    "CLEAN",
    "VALIDATION_FAILURE",
    "IO_ERROR",
    "CONFIG_ERROR",
    "KIWI_UNAVAILABLE",
    "INTERNAL_ERROR",
    "log",
    "output",
    "repo",
    "gates",
]

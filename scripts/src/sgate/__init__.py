"""sgate — unified CLI package for singularity-todo gate scripts.

The public API is `__all__`, and nothing else: the names in it are the names
a caller may use. The modules behind the package are not listed here — a list
of them written by hand is a list that loses a module, and the one this
docstring used to carry had been missing `gatekit`, `controls`,
`gate_manifest`, `models` and `gate_reachability` for weeks.

Two places hold the inventory instead, and both read it from the files:

  * `docs/tools/catalog.md` — what every module of the package is for,
    rendered by `python3 scripts/check-tools-catalog.py --write`;
  * `scripts/tests/test_sgate_module_inventory.py` — that every module
    imports, and that no module is shadowed by a package of the same name.

`KIWI_UNAVAILABLE` (4) is still defined for compatibility with the generated
gate shims, but no gate emits it: the Kiwi TCMS stand is gone, and the three
traceability gates that used to declare themselves Kiwi-dependent never needed
it. A soft skip that reads as a pass is worse than a hard failure, so `run-all`
no longer treats a non-zero exit as green.
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

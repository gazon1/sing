"""Specification-first traceability for the Kiwi TCMS stand.

The pipeline is deliberately inverted from the "sync results to a test manager"
shape most people expect:

    tests -> raw JUnit/Maestro XML -> NORMALISE (pure) -> coverage matrix (committed)
                                                     -> result matrix  (CI artefact)
                                                     -> Kiwi publish   (projection)

Why the inversion: the plugin Kiwi ships derives one test case per *test method*
from ``"${classname}.${name}"``, while Kiwi's own model puts several
``TestExecution``s (Android=passed, Desktop=failed) under a *single* ``TestCase``.
A matrix derived from "last execution per case" therefore cannot be correct — it
collapses tiers and loses per-tier status. The truth is the normalised result
set; every other artefact is a projection of it.

Ownership, stated once so it is not re-decided per module:

* Git is the source of truth: scenario specs under ``infra/kiwi/scenarios/``
  plus the linkage that lives in code (``@DisplayName``, ``scenario:`` tags).
* CI is authoritative for pass/fail. Nothing in Kiwi can turn a build red.
* Kiwi is a one-way projection of Git. A field edited in the Kiwi UI is
  overwritten on the next seed; that is why ``seed --check`` exists.

Submodules are imported by name (``from traceability import spec``) rather than
reliably from ``infra/kiwi``'s parent, because ``infra/kiwi`` is a flat script
directory and not an installed package.
"""

from __future__ import annotations

from pathlib import Path

__all__ = [
    "KIWI_DIR",
    "REPO_ROOT",
    "SCENARIOS_DIR",
    "COVERAGE_MATRIX_PATH",
    "TraceabilityError",
    "ValidationError",
    "kiwi_module",
]


class TraceabilityError(RuntimeError):
    """Base class for every failure this package raises deliberately."""


class ValidationError(TraceabilityError):
    """A spec, a link or a normalisation rule was violated.

    Raised with every problem in the message rather than only the first one:
    a validator that reports one error per run turns a three-line fix into three
    runs.
    """


# infra/kiwi/traceability/__init__.py -> parents[0]=traceability, [1]=kiwi, [2]=infra, [3]=repo
KIWI_DIR: Path = Path(__file__).resolve().parents[1]
REPO_ROOT: Path = KIWI_DIR.parents[1]

#: Canonical scenario specs. One YAML file per user scenario.
SCENARIOS_DIR: Path = KIWI_DIR / "scenarios"

#: The committed, deterministic coverage view. Regenerated and diffed in CI.
COVERAGE_MATRIX_PATH: Path = REPO_ROOT / "docs" / "testing" / "coverage-matrix.md"


def kiwi_module():
    """Import the flat ``kiwi_client`` sitting beside this package.

    ``infra/kiwi`` is a directory of standalone scripts, not an installed
    package, so ``import kiwi_client`` only resolves when that directory is on
    ``sys.path``. The pure core deliberately does *not* do this at import time:
    ``validate``, ``coverage`` and ``results`` must stay runnable with no stand,
    no TLS config and no XML-RPC stack in the process. Only the two adapters
    call this, and only at the moment they need to talk to Kiwi.
    """
    import sys

    if str(KIWI_DIR) not in sys.path:
        sys.path.insert(0, str(KIWI_DIR))
    import kiwi_client  # noqa: PLC0415

    return kiwi_client

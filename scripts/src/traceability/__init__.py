"""Specification-first traceability: user scenarios in Git, matrices derived from them.

The pipeline is deliberately inverted from the "sync results to a test manager"
shape most people expect:

    tests -> raw JUnit/Maestro XML -> NORMALISE (pure) -> coverage matrix (committed)
                                                     -> scenario details (committed)
                                                     -> result matrix  (CI artefact)

Ownership, stated once so it is not re-decided per module:

* Git is the source of truth: scenario specs under ``traceability/scenarios/``
  plus the linkage that lives in code (``@DisplayName``, ``scenario:`` tags).
* CI is authoritative for pass/fail. Nothing in this package can turn a build
  red, and nothing in it needs a network, a database or a container.
* Every artefact is a projection of the specs. A field edited in a generated
  document is overwritten by the next ``coverage`` run, which is why both
  documents carry a "generated — do not edit" banner and are diffed byte-for-byte
  in CI.

Submodules are imported by name (``from traceability import spec``). This package
is installed from ``scripts/src/pyproject.toml`` alongside ``sgate`` and
``scanners``, so there is no ``PYTHONPATH`` or ``sys.path`` bootstrap left to
keep correct — that class of hack was what this directory previously needed, and
deleting it is the reason the package moved.
"""

from __future__ import annotations

from pathlib import Path

__all__ = [
    "REPO_ROOT",
    "PACKAGE_DIR",
    "SCENARIOS_DIR",
    "COVERAGE_MATRIX_PATH",
    "DETAILS_PATH",
    "TraceabilityError",
    "ValidationError",
]


class TraceabilityError(RuntimeError):
    """Base class for every failure this package raises deliberately."""


class ValidationError(TraceabilityError):
    """A spec, a link or a normalisation rule was violated.

    Raised with every problem in the message rather than only the first one:
    a validator that reports one error per run turns a three-line fix into three
    runs.
    """


# scripts/src/traceability/__init__.py -> [0]=traceability [1]=src [2]=scripts [3]=repo
PACKAGE_DIR: Path = Path(__file__).resolve().parent
REPO_ROOT: Path = PACKAGE_DIR.parents[2]

#: Canonical scenario specs. One YAML file per user scenario.
SCENARIOS_DIR: Path = PACKAGE_DIR / "scenarios"

#: The committed, deterministic coverage *index*: summary, per-area tables and the
#: prioritised "what to test next" queue. Regenerated and diffed in CI.
COVERAGE_MATRIX_PATH: Path = REPO_ROOT / "docs" / "testing" / "coverage-matrix.md"

#: The committed prose: given / steps / expected for every scenario, with stable
#: anchors so the index can link into it. Regenerated and diffed in CI.
DETAILS_PATH: Path = REPO_ROOT / "docs" / "testing" / "scenario-details.md"
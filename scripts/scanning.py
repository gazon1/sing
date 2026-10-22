#!/usr/bin/env python3
"""scanning — import the repository scanners from a gate script.

The scanner package lives at `scripts/src/scanners/`, which is the same
install root as `sgate` and `traceability`. When those are installed (CI runs
`pip install -e scripts/src/`) a bare `from scanners import test_classes`
suffices; when they are not — a developer running `python3 scripts/check-test-runs.py`
straight out of a checkout — this module puts the directory on `sys.path`
itself, so a gate never has to carry its own bootstrap.

It replaces a chain of `importlib.util.spec_from_file_location` dances that each
re-derived the path separately. Four of them existed and they had already drifted
once, which is the reason `scanners/test_classes.py` is the only implementation
of "is this a runnable test?" in the Python tree.

Import [scanners.test_classes.ScannerUnavailable] behaviour: this module raises
`ImportError` when the scanner cannot be found at all, and callers turn that
into a loud failure. A gate that cannot load its scanner must fail, never skip —
the vacuous green that `ScannerUnavailable` in check-test-runs.py exists to
prevent is the reason this indirection returns the module and not None.
"""

from __future__ import annotations

import sys
from pathlib import Path

SRC = Path(__file__).resolve().parent / "src"

if SRC.is_dir() and str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

try:
    from scanners import test_classes
except ImportError as exc:  # pragma: no cover — only when the tree is broken
    raise ImportError(
        f"the repository scanner is not available: expected {SRC / 'scanners'}. "
        f"Install the repo tools with: python -m pip install -e scripts/src/"
    ) from exc

#: The module itself. `test_classes.scan_repository()` is the entry point the
#: run-floor gate uses; returns tuple[list[RepoTest], list[tuple[str,str]]].
RepoTest = test_classes.RepoTest
#: run-floor gate uses; `has_runnable_test()` answers the per-file question the
#: fixture-table test asks.
RepoTest = test_classes.RepoTest
TEST_ROOTS = test_classes.TEST_ROOTS
has_runnable_test = test_classes.has_runnable_test
scan_repository = test_classes.scan_repository
read_tag = test_classes._read_tag  # noqa: SLF001 — one repo, one scanner
read_tags = test_classes._read_tags  # noqa: SLF001 — one repo, one scanner
test_classes_in = test_classes._test_classes_in  # noqa: SLF001 — one repo, one scanner

__all__ = [
    "RepoTest",
    "TEST_ROOTS",
    "has_runnable_test",
    "read_tag",
    "read_tags",
    "scan_repository",
    "test_classes",
    "test_classes_in",
]
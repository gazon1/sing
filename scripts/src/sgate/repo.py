"""sgate.repo — repo-root resolution and path constants.

All paths are absolute. The repo root is detected from this file's location:
  scripts/sgate/repo.py → scripts/ → <repo root>
"""
from __future__ import annotations

import pathlib

# Repo root — derived from this file's location: scripts/sgate/repo.py → repo root
ROOT: pathlib.Path = pathlib.Path(__file__).resolve().parent.parent.parent.parent
assert (ROOT / ".git").exists(), f"Expected .git at {ROOT}"
assert (ROOT / "scripts").is_dir(), f"Expected scripts/ at {ROOT}"

# The importable package root — the directory that holds the `sgate` package.
#
# It is a constant rather than a string repeated in the callers because a path
# written out twice is a path that drifts: the callers that need "this
# checkout's code" all need *this* directory, and the check that they do is
# the check that they point at the same place.
SRC: pathlib.Path = ROOT / "scripts" / "src"

# Gate registry
STATIC_GATES_SH: pathlib.Path = ROOT / "scripts" / "ci" / "static-gates.sh"
assert STATIC_GATES_SH.exists(), f"Expected static-gates.sh at {STATIC_GATES_SH}"

# Auto-generated docs
GATE_REGISTRY_MD: pathlib.Path = ROOT / "docs" / "scripts" / "gate-registry.md"

# Performance baselines
GATE_BENCHMARKS_JSON: pathlib.Path = ROOT / "config" / "docs" / "gate-benchmarks.json"

# The published cost of the verification loop
GATE_COSTS_MD: pathlib.Path = ROOT / "docs" / "testing" / "gate-costs.md"
COVERAGE_COST_PROPERTIES: pathlib.Path = ROOT / "config" / "docs" / "coverage-cost.properties"

# The measurement of the cycle that just ran. Untracked: it is a reading of this
# machine at this moment, and the committed baseline is what survives it.
GATE_RUN_JSON: pathlib.Path = ROOT / "build" / "diagnostics" / "gate-run.json"
CYCLE_STEPS_TXT: pathlib.Path = ROOT / "build" / "diagnostics" / "cycle-steps.txt"

# Infra Kiwi
KIWI_SYNC: pathlib.Path = ROOT / "infra" / "kiwi" / "sync.py"

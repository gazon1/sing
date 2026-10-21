"""emulator.maestro — Maestro UI flow runner for singularity-todo.

Public API:
    main()           → int            (Click CLI entry point)
    run_one_flow(...) → tuple[bool, str]
    FakeMaestro                          (test harness in _testing)
    flow_tags / flow_has_tag             (tag inspection)
    collect_flows / filter_flows_by_tag
    EXIT_PASS / EXIT_FAIL
"""
from emulator.maestro._cli import main
from emulator.maestro._cli import main as cli
from emulator.maestro._flow import (
    collect_flows,
    filter_flows_by_tag,
    flow_has_tag,
    flow_tags,
)
from emulator.maestro._runner import run_one_flow
from emulator.maestro._testing import FakeMaestro, FakeMaestroResult
from emulator.maestro._types import EXIT_FAIL, EXIT_PASS

__all__ = [
    "cli",
    "main",
    "run_one_flow",
    "collect_flows",
    "filter_flows_by_tag",
    "flow_has_tag",
    "flow_tags",
    "FakeMaestro",
    "FakeMaestroResult",
    "EXIT_FAIL",
    "EXIT_PASS",
]

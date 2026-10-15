"""sgate.gates — gate registry loader.

Loads gate definitions from sgate.commands._registry.GATES (the single source
of truth). The Gate namedtuple provides a read-only view of the registry data.

Gate = NamedTuple with fields:
    name: str           — display name from the registry
    mode: GateMode      — blocking or advisory
    command: str        — full command string (reconstructed from GateSpec)
    comment: str|None   — always None (registry has no inline-comment field)
    sabotage_test: str|None — probe name in check-gate-wiring.py (defaults to name)
    kiwi_required: bool  — from GateSpec.kiwi_required
"""
from __future__ import annotations

import sys
from typing import NamedTuple

from sgate.commands._registry import GATES as _REGISTRY_GATES
from sgate.commands._registry import CmdType, GateMode


class Gate(NamedTuple):
    """A gate registered in the registry."""

    name: str
    mode: GateMode
    command: str
    comment: str | None
    sabotage_test: str | None
    kiwi_required: bool = False


def _build_command(spec) -> str:
    """Reconstruct the full command string from a GateSpec."""
    if spec.cmd_type in (CmdType.PYTHON3, CmdType.PYTHON3_M):
        parts = [sys.executable]
        if spec.cmd_type == CmdType.PYTHON3_M:
            parts.append("-m")
        parts.append(str(spec.script))
        parts.extend(spec.script_args)
        return " ".join(parts)
    if spec.cmd_type == CmdType.SHELL:
        parts = ["bash", str(spec.script)]
        parts.extend(spec.script_args)
        return " ".join(parts)
    if spec.cmd_type == CmdType.BASH_C:
        # Reconstruct: bash -c ". {static_gates_sh} && {func}"
        static_sh = "scripts/ci/static-gates.sh"
        return f"bash -c '. {static_sh} && {spec.bash_func}'"
    if spec.cmd_type == CmdType.PATH:
        return " ".join(spec.path_cmd)
    # Fallback for unknown cmd_type: reconstruct from available fields
    if spec.script and str(spec.script):
        return f"{spec.cmd_type} {spec.script}"
    return f"{spec.cmd_type} (no script)"


def load_gates() -> list[Gate]:
    """Load all gates from the registry.

    Returns
    -------
    list[Gate]
        Ordered list of gates as they appear in _registry.GATES.
    """
    gates: list[Gate] = []
    for spec in _REGISTRY_GATES:
        gates.append(Gate(
            name=spec.name,
            mode=spec.mode,
            command=_build_command(spec),
            comment=None,
            sabotage_test=spec.name,
            kiwi_required=spec.kiwi_required,
        ))
    return gates

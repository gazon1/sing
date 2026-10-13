"""sgate.gates — gate registry loader.

Loads gate definitions from sgate.commands._registry.GATES (the single source
of truth). The Gate namedtuple provides a read-only view of the registry data.

Gate = NamedTuple with fields:
    name: str           — display name from the registry
    mode: str            — "blocking" or "advisory"
    command: str         — full command string (reconstructed from GateSpec)
    comment: str|None   — always None (registry has no inline-comment field)
    sabotage_test: str|None — probe name in check-gate-wiring.py (defaults to name)
    kiwi_required: bool  — from GateSpec.kiwi_required
"""
from __future__ import annotations

import sys
from typing import NamedTuple

from sgate.commands._registry import GATES as _REGISTRY_GATES


class Gate(NamedTuple):
    """A gate registered in the registry."""

    name: str
    mode: str  # "blocking" | "advisory"
    command: str
    comment: str | None
    sabotage_test: str | None
    kiwi_required: bool = False


def _build_command(spec) -> str:
    """Reconstruct the full command string from a GateSpec."""
    if spec.cmd_type in ("python3", "python3_m"):
        parts = [sys.executable]
        if spec.cmd_type == "python3_m":
            parts.append("-m")
        parts.append(spec.script)
        parts.extend(spec.script_args)
        return " ".join(parts)
    if spec.cmd_type == "shell":
        parts = ["bash", str(spec.script)]
        parts.extend(spec.script_args)
        return " ".join(parts)
    if spec.cmd_type == "bash_c":
        # Reconstruct: bash -c ". {static_gates_sh} && {func}"
        static_sh = "scripts/ci/static-gates.sh"
        return f"bash -c '. {static_sh} && {spec.bash_func}'"
    if spec.cmd_type == "path":
        return " ".join(spec.path_cmd)
    # Fallback for unknown cmd_type: reconstruct from available fields
    if spec.script:
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

"""sgate.gates — gate registry loader.

Parses scripts/ci/static-gates.sh to extract gate definitions.

Gate = NamedTuple with fields:
    name: str           — "adr-config-sync" (kebab-case)
    mode: str            — "blocking" or "advisory"
    command: str         — full command string
    comment: str|None   — inline comment if any
    sabotage_test: str|None — probe name in check-gate-wiring.py
    kiwi_required: bool  — always False (set at command level)
"""
from __future__ import annotations

import re
import shlex
from typing import NamedTuple


class Gate(NamedTuple):
    """A gate registered in static-gates.sh."""

    name: str
    mode: str  # "blocking" | "advisory"
    command: str
    comment: str | None
    sabotage_test: str | None
    kiwi_required: bool = False


# Matches `gate blocking|advisory "name" ...` registry lines.
# Must NOT match the `gate() {` function definition line.
_GATE_LINE_RE = re.compile(r'^gate\s+(blocking|advisory)\s+"([^"]+)"\s+(.+)$')


def load_gates() -> list[Gate]:
    """Load all gates from static-gates.sh.

    Parses the file directly without executing any shell code.
    Skips the ``gate() { ... }`` function definition by matching only
    lines that have a third field (the command), which function definitions
    don't have.

    Returns
    -------
    list[Gate]
        Ordered list of gates as they appear in the registry.
    """
    import sgate.repo

    registry_path = sgate.repo.STATIC_GATES_SH
    content = registry_path.read_text(encoding="utf-8")

    gates: list[Gate] = []
    seen_names: set[str] = set()

    for line in content.splitlines():
        # Skip: blank, comment-only, or the gate() function definition.
        # The function definition has the form "gate() {" — no third field.
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "gate()" in stripped:
            continue

        m = _GATE_LINE_RE.match(line)
        if not m:
            continue

        mode = m.group(1)
        name = m.group(2)
        raw_cmd = m.group(3).rstrip()

        # Inline comment after the command (e.g. "  # no-self-probe")
        comment: str | None = None
        cmd = raw_cmd
        if " # " in raw_cmd and not raw_cmd.strip().startswith("#"):
            cmd_part, comment = raw_cmd.split(" # ", 1)
            cmd = cmd_part.rstrip()

        # Deduplicate by name (first wins — matches bash for-loop behaviour)
        if name in seen_names:
            continue
        seen_names.add(name)

        gates.append(Gate(
            name=name,
            mode=mode,
            command=cmd,
            comment=comment,
            sabotage_test=name,
            kiwi_required=False,
        ))

    return gates

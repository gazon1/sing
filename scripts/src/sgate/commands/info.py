"""sgate.commands.info — sgate info: show detailed gate info."""
from __future__ import annotations

import re
from typing import ClassVar

import click
from rich.console import Console
from rich.table import Table

import sgate
from sgate.commands.base import register


def _module_name(display_name: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "_", display_name.lower())
    return re.sub(r"_+", "_", s).strip("_")


# ── KIWI_REQUIRED lookup from registry ─────────────────────────────────────────


def _kiwi_required_from_registry(gate_name: str) -> bool:
    """Look up KIWI_REQUIRED for a gate by its display name.

    Returns False for gates not found in the registry (e.g. custom gates
    defined outside GATES).
    """
    # Avoid a circular import: _registry does not import from info.
    from sgate.commands import _registry

    for spec in _registry.GATES:
        if spec.name == gate_name:
            return spec.kiwi_required
    return False


def _registry_names() -> set[str]:
    """All gate display names in the registry (generated gates only)."""
    from sgate.commands import _registry

    return {spec.name for spec in _registry.GATES}


@click.command("info")
@click.argument("gate_name", default="")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.pass_context
def info(ctx: click.Context, gate_name: str, emit_json: bool) -> None:
    """Show detailed information about one or more gates.

    GATE_NAME is a substring match (case-insensitive). If omitted, shows all gates.

    Exit codes:
      0 = one or more gates found
      1 = no gates matched
    """
    import json

    import sgate.gates

    gates = sgate.gates.load_gates()
    if not gate_name:
        matched = gates
    else:
        needle = gate_name.lower()
        matched = [g for g in gates if needle in g.name.lower()]

    if not matched:
        if emit_json:
            print(json.dumps({"error": f"no gate matching {gate_name!r}"}))
        else:
            print(f"No gate matching {gate_name!r}")
        raise SystemExit(1)

    registry_names = _registry_names()

    if emit_json:
        rows = []
        for g in matched:
            module_name = _module_name(g.name)
            kiwi_required = _kiwi_required_from_registry(g.name)
            # A gate is "custom" when it is not in the registry (hand-written
            # Click command rather than a generated shim).
            is_custom = g.name not in registry_names
            rows.append({
                "name": g.name,
                "mode": g.mode,
                "command": g.command,
                "kiwi_required": kiwi_required,
                "custom": is_custom,
                "module": f"{module_name}.py",
            })
        if len(rows) == 1:
            print(json.dumps(rows[0], indent=2))
        else:
            print(json.dumps(rows, indent=2))
        return

    console = Console()
    if len(matched) == 1:
        g = matched[0]
        module_name = _module_name(g.name)
        kiwi_required = _kiwi_required_from_registry(g.name)
        is_custom = g.name not in registry_names

        console.print(f"[bold]{g.name}[/bold]")
        console.print(f"  mode          : {g.mode}")
        console.print(f"  module        : {module_name}.py")
        console.print(f"  kiwi_required : {kiwi_required}")
        console.print(f"  custom        : {is_custom}")
        console.print(f"  command       : {g.command}")
        if g.comment:
            console.print(f"  comment       : {g.comment}")
    else:
        table = Table(title=f"{len(matched)} gates matching {gate_name!r}")
        table.add_column("name", style="cyan")
        table.add_column("mode", style="magenta")
        table.add_column("kiwi", justify="center")
        table.add_column("custom", justify="center")
        table.add_column("module")

        for g in matched:
            module_name = _module_name(g.name)
            kiwi_required = _kiwi_required_from_registry(g.name)
            is_custom = g.name not in registry_names

            table.add_row(
                g.name,
                g.mode,
                "✓" if kiwi_required else "",
                "✓" if is_custom else "",
                f"{module_name}.py",
            )
        console.print(table)


register(info)

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
    import sgate.gates

    gates = sgate.gates.load_gates()
    if not gate_name:
        matched = gates
    else:
        needle = gate_name.lower()
        matched = [g for g in gates if needle in g.name.lower()]

    if not matched:
        if emit_json:
            import json
            print(json.dumps({"error": f"no gate matching {gate_name!r}"}))
        else:
            print(f"No gate matching {gate_name!r}")
        raise SystemExit(1)

    if emit_json:
        import json
        rows = []
        for g in matched:
            module_name = _module_name(g.name)
            shim_path = sgate.repo.ROOT / "scripts" / "src" / "sgate" / "commands" / f"{module_name}.py"
            # Try to read KIWI_REQUIRED from the shim if it exists
            kiwi_required = False
            is_custom = False
            if shim_path.exists():
                text = shim_path.read_text(encoding="utf-8")
                m = re.search(r"^KIWI_REQUIRED:\s*ClassVar\[bool\]\s*=\s*(True|False)", text, re.M)
                if m:
                    kiwi_required = m.group(1) == "True"
                # Custom gates: either "native Click for" or "custom gate:" in docstring,
                # IS_CUSTOM class variable, or "custom": True in metadata
                if "native Click for" in text or "custom gate:" in text or 'IS_CUSTOM: ClassVar[bool] = True' in text:
                    is_custom = True
                # Legacy: check for custom metadata in docstring
                elif '{"custom": True}' in text or '"custom": True' in text:
                    is_custom = True

            rows.append({
                "name": g.name,
                "mode": g.mode,
                "command": g.command,
                "kiwi_required": kiwi_required,
                "custom": is_custom,
                "module": f"{module_name}.py",
            })
        import json
        if len(rows) == 1:
            print(json.dumps(rows[0], indent=2))
        else:
            print(json.dumps(rows, indent=2))
        return

    console = Console()
    if len(matched) == 1:
        g = matched[0]
        module_name = _module_name(g.name)
        shim_path = sgate.repo.ROOT / "scripts" / "src" / "sgate" / "commands" / f"{module_name}.py"

        kiwi_required = False
        is_custom = False
        if shim_path.exists():
            text = shim_path.read_text(encoding="utf-8")
            m = re.search(r"^KIWI_REQUIRED:\s*ClassVar\[bool\]\s*=\s*(True|False)", text, re.M)
            if m:
                kiwi_required = m.group(1) == "True"
            if '{"custom": True}' in text or '"custom": True' in text:
                is_custom = True

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
            shim_path = sgate.repo.ROOT / "scripts" / "src" / "sgate" / "commands" / f"{module_name}.py"
            kiwi_required = False
            is_custom = False
            if shim_path.exists():
                text = shim_path.read_text(encoding="utf-8")
                m = re.search(r"^KIWI_REQUIRED:\s*ClassVar\[bool\]\s*=\s*(True|False)", text, re.M)
                if m:
                    kiwi_required = m.group(1) == "True"
                if "native Click for" in text or "custom gate:" in text or 'IS_CUSTOM: ClassVar[bool] = True' in text:
                    is_custom = True
                elif '{"custom": True}' in text or '"custom": True' in text:
                    is_custom = True

            table.add_row(
                g.name,
                g.mode,
                "✓" if kiwi_required else "",
                "✓" if is_custom else "",
                f"{module_name}.py",
            )
        console.print(table)


register(info)

"""sgate.cli — top-level Click CLI entry point."""
from __future__ import annotations

import importlib
import pkgutil
from pathlib import Path

import click
from rich.console import Console
from rich.panel import Panel
from rich.table import Table
from rich.text import Text

import sgate
import sgate.gates
from sgate.commands.base import CLEAN
from sgate.log import logger


@click.group()
@click.version_option(version=sgate.__version__, prog_name="sgate")
def main():
    """sgate — unified CLI for singularity-todo gate scripts.

    Run a specific gate with: sgate <name>
    List all gates with:    sgate gates
    """
    pass


# ── Meta commands (always available) ─────────────────────────────────────────

@main.command("gates")
@click.option("--json", "json_output", is_flag=True, help="Emit JSON to stdout")
def cmd_gates(json_output: bool):
    """List all registered gates from static-gates.sh.

    Exit codes:
      0 = clean (always succeeds)
    """
    gates = sgate.gates.load_gates()
    if not gates:
        click.echo("No gates found in static-gates.sh")
        return

    if json_output:
        import json
        click.echo(json.dumps([
            {"name": g.name, "mode": g.mode, "command": g.command}
            for g in gates
        ]))
        raise SystemExit(CLEAN)

    console = Console()
    total = len(gates)
    blocking = sum(1 for g in gates if g.mode == "blocking")
    advisory = sum(1 for g in gates if g.mode == "advisory")

    # Summary panel
    summary = Text.assemble(
        f"  {total} gates total  ·  ",
        f"[bold cyan]{blocking}[/bold cyan] blocking  ·  ",
        f"[bold yellow]{advisory}[/bold yellow] advisory",
    )
    console.print(summary)
    console.print()

    table = Table(show_header=True, header_style="bold cyan", expand=False)
    table.add_column("Gate", style="cyan", no_wrap=False)
    table.add_column("Mode", style="magenta", width=10)
    table.add_column("Command", style="dim", no_wrap=False)

    for g in gates:
        mode_style = "cyan" if g.mode == "blocking" else "yellow"
        mode_label = f"[{mode_style}]{g.mode}[/{mode_style}]"
        cmd_short = g.command[:65] + ("…" if len(g.command) > 65 else "")
        table.add_row(g.name, mode_label, cmd_short)

    console.print(table)


# Lazy-load all command modules from sgate/commands/ and register them.
def _register_gate_commands():
    """Discover and register every <name>.py command module as a Click subcommand."""
    commands_pkg = Path(__file__).parent / "commands"
    if not commands_pkg.is_dir():
        return

    for _finder, module_name, _ispkg in pkgutil.iter_modules([str(commands_pkg)]):
        if module_name in ("base",):
            continue
        if module_name.startswith("_"):
            continue

        try:
            importlib.import_module(f"sgate.commands.{module_name}")
            logger.debug(f"loaded sgate.commands.{module_name}")
        except Exception:
            import traceback
            logger.error(
                f"failed to load sgate.commands.{module_name}: {traceback.format_exc()}"
            )


_register_gate_commands()

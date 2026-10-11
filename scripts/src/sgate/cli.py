"""sgate.cli — top-level Click CLI entry point."""
from __future__ import annotations

import importlib
import inspect
import pkgutil
from pathlib import Path

import click

import sgate
import sgate.gates
from sgate.commands.base import CLEAN


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
def cmd_gates():
    """List all registered gates from static-gates.sh.

    Exit codes:
      0 = clean (always succeeds)
    """
    gates = sgate.gates.load_gates()
    if not gates:
        click.echo("No gates found in static-gates.sh")
        return

    # Use rich if available, plain text otherwise
    try:
        from rich.console import Console
        from rich.table import Table

        console = Console()
        table = Table(title="Registered Gates")
        table.add_column("Gate", style="cyan")
        table.add_column("Mode", style="magenta")
        table.add_column("Command", style="dim")

        for g in gates:
            table.add_row(g.name, g.mode, g.command[:60] + ("…" if len(g.command) > 60 else ""))

        console.print(table)
    except Exception:
        click.echo(f"{'Gate':<40} {'Mode':<12} Command")
        click.echo("-" * 100)
        for g in gates:
            cmd_short = g.command[:57] + ("…" if len(g.command) > 60 else "")
            click.echo(f"{g.name:<40} {g.mode:<12} {cmd_short}")


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

        # Import the module (side-effect: it registers its @main.command)
        try:
            importlib.import_module(f"sgate.commands.{module_name}")
        except Exception:
            # Gate commands must be loadable; if one crashes on import that's a
            # real problem. Print and continue so other gates still register.
            import traceback

            click.echo(
                f"Warning: could not load sgate.commands.{module_name}: {traceback.format_exc()}",
                err=True,
            )


_register_gate_commands()

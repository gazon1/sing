"""sgate.commands.suite_lint — run all lint-rule governance gates concurrently."""
from __future__ import annotations

import concurrent.futures
import json as _json
import sys

import click
from rich.console import Console
from rich.rule import Rule
from rich.table import Table

import sgate
from sgate.commands.base import CLEAN, INTERNAL_ERROR, register
from sgate.log import logger
from sgate.output import gate_fail

# Gates that belong to the "lint" suite (from spec/sgate-cli.md)
LINT_GATES = [
    "detekt-rules-config-is-current",
    "detekt-rule-inventory-is-current",
    "detekt-rule-registry",
    "detekt-baseline-ratchet",
    "lint-rules-are-declared-decisions",
    "file-level-suppressions-explain-themselves",
]


def _run_gate(name: str) -> tuple[str, int]:
    """Run one gate as subprocess, return (name, exit_code)."""
    import subprocess

    repo_root = sgate.repo.ROOT
    cmd = [sys.executable, "-m", "sgate", name]
    try:
        result = subprocess.run(cmd, capture_output=True, cwd=repo_root)
        return name, result.returncode
    except Exception:
        return name, 5


@click.group("lint")
@click.option("--check", is_flag=True, help="Dry-run: print results without exiting non-zero")
@click.option("--json", is_flag=True, help="Emit JSON output to stdout")
def lint(check: bool, json: bool) -> None:
    """Run all lint-rule governance gates concurrently.

    Gates: detekt-rules-config-is-current, detekt-rule-inventory-is-current,
    detekt-rule-registry, detekt-baseline-ratchet, lint-rules-are-declared-decisions,
    file-level-suppressions-explain-themselves.

    Exit codes:
      0 = all clean
      1 = at least one gate failed
      5 = internal error
    """
    results: list[tuple[str, int]] = []

    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
        futures = {executor.submit(_run_gate, name): name for name in LINT_GATES}
        for future in concurrent.futures.as_completed(futures):
            try:
                name, rc = future.result()
                results.append((name, rc))
            except Exception as exc:
                name = futures[future]
                logger.error(f"gate {name} raised exception: {exc}")
                gate_fail(name, None, None, None, None, f"exception: {exc}")
                results.append((name, 5))

    failed = [(n, rc) for n, rc in results if rc != 0]

    if json:
        click.echo(_json.dumps({
            "suite": "lint",
            "total": len(LINT_GATES),
            "passed": len(results) - len(failed),
            "failed": len(failed),
            "results": [{"gate": n, "exit_code": rc} for n, rc in results],
        }))
        raise SystemExit(CLEAN if not failed else 1)

    console = Console()
    console.print(Rule("[bold]lint gates[/bold]"))

    table = Table(show_header=True, header_style="bold cyan", expand=False)
    table.add_column("Gate", style="cyan")
    table.add_column("Result", justify="center", width=8)
    table.add_column("Exit", justify="right", width=5)

    for name, rc in results:
        if rc == 0:
            status_label = "[green]PASS[/green]"
        else:
            status_label = f"[red]FAIL[/red]"
            gate_fail(name, None, None, None, None, f"exit {rc}")
        table.add_row(name, status_label, str(rc) if rc != 0 else "—")

    console.print(table)

    passed = len(results) - len(failed)
    total = len(results)
    if failed:
        console.print(f"\n  [red]✗ {passed}/{total} passed[/red]")
    else:
        console.print(f"\n  [green]✓ {passed}/{total} passed[/green]")

    raise SystemExit(CLEAN if not failed else 1)


# Register with the CLI group
register(lint)

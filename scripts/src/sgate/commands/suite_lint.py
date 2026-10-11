"""sgate.commands.suite_lint — run all lint-rule governance gates concurrently."""
from __future__ import annotations

import concurrent.futures
import sys

import click

import sgate
from sgate.commands.base import CLEAN, INTERNAL_ERROR, register

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
                click.echo(f"Error running {name}: {exc}", err=True)
                results.append((name, 5))

    failed = [(n, rc) for n, rc in results if rc != 0]

    if json:
        import json as _json
        click.echo(_json.dumps({
            "suite": "lint",
            "total": len(LINT_GATES),
            "passed": len(results) - len(failed),
            "failed": len(failed),
            "results": [{"gate": n, "exit_code": rc} for n, rc in results],
        }))
    else:
        for name, rc in results:
            status = "PASS" if rc == 0 else f"FAIL({rc})"
            click.echo(f"  {status}  {name}")
        click.echo(f"\n{len(results) - len(failed)}/{len(results)} passed")

    raise SystemExit(CLEAN if not failed else 1)


# Register with the CLI group
register(lint)

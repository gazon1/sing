#!/usr/bin/env python3
"""check-workflow-env-context.py — every `${{ env.X }}` in a workflow must resolve.

The `env` context of a workflow holds the variables the *workflow* defines:
workflow-level, job-level and step-level `env:` blocks, plus the names an
earlier step wrote to `$GITHUB_ENV`. It does **not** hold the runner's own
environment. `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `RUNNER_TEMP`, `HOME` and
the rest are process variables, not context entries, and `${{ env.ANDROID_HOME }}`
expands to the empty string — silently, with no warning and no error.

That is not a theory. The Maestro shards failed a whole release cycle with
"'emulator' binary not found in PATH" because the cache paths were written
`${{ env.ANDROID_HOME }}/…`: the list expanded to `/system-images`, `/emulator`
and `/platform-tools` at the filesystem root, the cache carried nothing, and
the empty entry it left behind was a *hit* on every later run — and a cache hit
means "do not save", so the empty entry was permanent. Four shards died per
run, and the log pointed at the emulator rather than at the path that named
nothing.

So this gate reads every workflow, collects the names the file actually
defines, and reports every `${{ env.NAME }}` that names something else.

What it deliberately does not do: model GitHub's exact scoping. A step in job
A that references a variable defined only in job B passes this gate, because
the file defines it somewhere. That is the right trade — the failure this
gate exists for is a name that resolves to nothing *anywhere*, and scoping
subtleties have never been the cause.

Usage: python3 scripts/check-workflow-env-context.py [--json] [--check]
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

import click
import yaml

from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"

#: `${{ env.NAME }}`, with the `|| 'fallback'` form the repo uses for defaults.
_ENV_REF = re.compile(r"\$\{\{\s*env\.([A-Za-z_][A-Za-z0-9_]*)\s*(?:\|\|[^}]*)?\}\}")

#: `echo "NAME=value" >> "$GITHUB_ENV"` — a name an earlier step publishes.
_ENV_WRITE = re.compile(
    r"""echo\s+["']?([A-Za-z_][A-Za-z0-9_]*)=[^"'\n]*["']?\s*>>\s*["']?\$GITHUB_ENV"""
)


def _strings(node, out: list[str]) -> None:
    """Every string value in a parsed YAML tree, recursively.

    Comments are not values, so a workflow that *documents* the mistake in a
    comment does not trip the gate. The mistake that matters is in the code.
    """
    if isinstance(node, str):
        out.append(node)
    elif isinstance(node, dict):
        for key, value in node.items():
            if isinstance(key, str):
                out.append(key)
            _strings(value, out)
    elif isinstance(node, list):
        for item in node:
            _strings(item, out)


def _defined_names(workflow: dict) -> set[str]:
    """The env names this workflow defines, at any level."""
    names: set[str] = set()

    def harvest(block) -> None:
        if isinstance(block, dict):
            for key in block:
                if isinstance(key, str):
                    names.add(key)

    harvest(workflow.get("env"))
    for spec in (workflow.get("jobs") or {}).values():
        if not isinstance(spec, dict):
            continue
        harvest(spec.get("env"))
        for step in (spec.get("steps") or []):
            if isinstance(step, dict):
                harvest(step.get("env"))
    return names


def _published_names(workflow: dict) -> set[str]:
    """Names a step writes to `$GITHUB_ENV`, which reach the steps after it."""
    texts: list[str] = []
    for spec in (workflow.get("jobs") or {}).values():
        if not isinstance(spec, dict):
            continue
        for step in (spec.get("steps") or []):
            if isinstance(step, dict) and isinstance(step.get("run"), str):
                texts.append(step["run"])
    found: set[str] = set()
    for text in texts:
        found.update(_ENV_WRITE.findall(text))
    return found


def _workflow_files() -> list[pathlib.Path]:
    if not WORKFLOWS.is_dir():
        return []
    return sorted(
        p for p in WORKFLOWS.iterdir() if p.suffix in (".yml", ".yaml") and p.is_file()
    )


def scan() -> list[dict]:
    """Every unresolvable `${{ env.NAME }}`, as a list of findings."""
    findings: list[dict] = []
    for path in _workflow_files():
        try:
            workflow = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as exc:
            findings.append(
                {
                    "file": path.name,
                    "name": "",
                    "where": "parse error",
                    "detail": str(exc),
                }
            )
            continue
        if not isinstance(workflow, dict):
            continue

        known = _defined_names(workflow) | _published_names(workflow)

        values: list[str] = []
        _strings(workflow.get("jobs"), values)
        _strings(workflow.get("env"), values)
        for key in workflow:
            if key not in ("jobs", "env") and isinstance(key, str):
                values.append(key)

        for value in values:
            for name in _ENV_REF.findall(value):
                if name in known:
                    continue
                findings.append(
                    {
                        "file": path.name,
                        "name": name,
                        "where": "unresolvable env context reference",
                        "detail": (
                            f"${{{{ env.{name} }}}} expands to the empty string: the "
                            f"`env` context does not hold the runner's environment"
                        ),
                    }
                )
    return findings


@click.command()
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.option("--check", is_flag=True, help="Dry-run: print the result without exiting non-zero")
@click.pass_context
def cli(ctx: click.Context, emit_json: bool, check: bool) -> None:
    # A gate that scans nothing reports "every reference resolves" — which is
    # exactly what it said when `.github/workflows/` was missing or renamed.
    # An absent directory is not a clean bill of health; it is the gate not
    # running. The sibling gate refuses the same way
    # (scripts/check-workflow-exit-status.py:233).
    workflows = _workflow_files()
    if not workflows:
        click.echo(f"::error::no workflows found under {WORKFLOWS}", err=True)
        raise ctx.exit(IO_ERROR)

    findings = scan()
    if emit_json:
        click.echo(json.dumps({"findings": findings}, indent=2))
    else:
        if not findings:
            click.echo("workflow env context: every ${{ env.X }} resolves to a name the file defines")
        else:
            click.echo(f"workflow env context: {len(findings)} unresolvable reference(s)")
            for finding in findings:
                click.echo(
                    f"  {finding['file']}: ${{{{ env.{finding['name']} }}}} — {finding['detail']}"
                )
    if check:
        raise ctx.exit(CLEAN)
    raise ctx.exit(VALIDATION_FAILURE if findings else CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))

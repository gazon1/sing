"""sgate.commands.render_registry — generate docs/scripts/gate-registry.md from Click command docstrings."""
from __future__ import annotations

import ast
import json as _json
import re
from pathlib import Path

import click

import sgate
from sgate.commands.base import CLEAN, CONFIG_ERROR, INTERNAL_ERROR, register
from sgate.log import logger

EXIT_CODES_RE = re.compile(r"Exit codes:\s*(?:\n\s*\d+\s*=\s*[^\n]+)+", re.MULTILINE)
_GATE_NAME_RE = re.compile(r"^@click\.command\(['\"]([^'\"]+)['\"]")


def _extract_docstring(path: Path) -> tuple[str | None, dict[int, str], str | None]:
    """Extract purpose, exit codes, and ADR link from a Click command module.

    Returns (purpose, exit_codes_dict, adr_link)
    """
    try:
        content = path.read_text(encoding="utf-8")
        tree = ast.parse(content)
    except (SyntaxError, ValueError):
        return None, {}, None

    # Get module docstring
    docstring = ast.get_docstring(tree)

    # Find the Click command decorator and gate name
    gate_name = None
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            func = node.func
            if isinstance(func, ast.Attribute) and func.attr == "command":
                if node.args and isinstance(node.args[0], ast.Constant):
                    gate_name = node.args[0].value
            elif isinstance(func, ast.Name) and func.id == "command":
                if node.args and isinstance(node.args[0], ast.Constant):
                    gate_name = node.args[0].value

    # Extract exit codes block
    exit_codes: dict[int, str] = {}
    adr_link: str | None = None

    if docstring:
        # Find ADR link
        adr_match = re.search(r"ADR[-_ ](\d+)", docstring)
        if adr_match:
            adr_link = f"adr-{adr_match.group(1)}"

        # Find exit codes block
        ec_match = EXIT_CODES_RE.search(docstring)
        if ec_match:
            ec_block = ec_match.group()
            for line in ec_block.splitlines():
                m = re.match(r"\s*(\d+)\s*=\s*(\w+)\s*(.*)", line)
                if m:
                    code = int(m.group(1))
                    meaning = m.group(3).strip()
                    exit_codes[code] = f"{m.group(2)}: {meaning}"

        # First paragraph = purpose
        paragraphs = [p.strip() for p in docstring.split("\n\n") if p.strip()]
        purpose = paragraphs[0] if paragraphs else None
    else:
        purpose = None

    return purpose, exit_codes, adr_link


def _generate_table(commands_dir: Path) -> str:
    """Generate the gate-registry.md table content.

    The rows come from the *registry* (`_registry.GATES`), not from the files in
    `sgate/commands/`.

    Until 2026-10-20 this iterated the command modules directory, which is a
    different set: it listed private helpers (`_gate-runner`, `_parsers`,
    `_registry`, `_runner`) as gates, it missed every gate whose command is
    generated from a `GateSpec` rather than hand-written, and it reported
    `mode=unknown` for most rows because the module stem and the gate's
    display name are different strings. A table of that shape is worse than no
    table: a reader looking for "which gates exist, and is each blocking?"
    got a list of modules and a column of unknowns.

    The registry is the source of truth for the two facts a reader needs most
    — the gate's name and whether it stops the build. Purpose, exit codes and
    the ADR reference still come from the docstring of the script the gate
    runs, because that is where they are written.

    The header row is written by the caller; this function returns rows only.
    """
    lines: list[str] = []

    from sgate.commands._registry import GATES

    for spec in GATES:
        doc_source = _docstring_source(spec.script)
        purpose, exit_codes, adr_link = (
            _extract_docstring(doc_source) if doc_source is not None else (None, {}, None)
        )

        ec_str = ", ".join(f"{k}={v}" for k, v in sorted(exit_codes.items())) if exit_codes else "—"
        adr_str = adr_link if adr_link else "—"

        purpose_str = purpose if purpose else spec.name
        if len(purpose_str) > 80:
            purpose_str = purpose_str[:77] + "..."

        lines.append(
            f"| {spec.name} | {spec.mode.value} | {purpose_str} | {ec_str} | {adr_str} |"
        )

    return "\n".join(lines)


def _docstring_source(script: Path) -> Path | None:
    """The file whose docstring describes a gate: its script, if it has one.

    A gate that runs a shell function or a PATH command has no script to read;
    for those the table carries the gate's own name as its purpose.
    """
    if not script or str(script) in ("", "."):
        return None
    candidate = sgate.repo.ROOT / script
    return candidate if candidate.is_file() else None


@click.command("render-registry")
@click.option("--check", is_flag=True, help="Fail if gate-registry.md is outdated")
@click.option("--json", is_flag=True, help="Emit machine-readable output to stdout")
def render_registry(check: bool, json: bool) -> None:
    """Generate docs/scripts/gate-registry.md from the gate registry.

    The rows come from `_registry.GATES`: every registered gate gets a row, with
    the mode the registry declares. Purpose, exit codes and the ADR reference
    come from the docstring of the script the gate runs.

    Exit codes:
      0 = clean (generated successfully or --check passed)
      1 = --check: file is outdated or missing
      3 = configuration error (docs dir not writable)
      5 = internal error
    """
    registry_md = sgate.repo.GATE_REGISTRY_MD
    commands_dir = Path(__file__).parent

    table_content = _generate_table(commands_dir)

    header = """<!-- AUTO-GENERATED by sgate render-registry — do not edit manually -->

# Gate Registry

Generated from `_registry.GATES` — the registry that says which gates exist and
whether each one is blocking. Purpose, exit codes and ADR references come from
the docstring of the script each gate runs.

| Gate | Mode | Purpose | Exit Codes | ADR |
|------|------|---------|------------|-----|"""

    full_content = header + "\n" + table_content + "\n"

    if check:
        if not registry_md.exists():
            if json:
                click.echo(_json.dumps({"status": "missing", "path": str(registry_md)}))
            else:
                logger.error("gate-registry.md is missing — run sgate render-registry to generate")
            raise SystemExit(CONFIG_ERROR)

        current = registry_md.read_text()
        if current != full_content:
            if json:
                click.echo(_json.dumps({"status": "outdated", "path": str(registry_md)}))
            else:
                logger.warning("gate-registry.md is outdated — run sgate render-registry to update")
            raise SystemExit(CONFIG_ERROR)

        if json:
            click.echo(_json.dumps({"status": "current", "path": str(registry_md)}))
        else:
            logger.info("gate-registry.md is current")
        raise SystemExit(CLEAN)

    # Write
    try:
        registry_md.parent.mkdir(parents=True, exist_ok=True)
        registry_md.write_text(full_content, encoding="utf-8")
    except OSError as exc:
        logger.error(f"failed to write {registry_md}: {exc}")
        raise SystemExit(CONFIG_ERROR)

    if json:
        click.echo(_json.dumps({"status": "generated", "path": str(registry_md)}))
    else:
        logger.info(f"generated {registry_md}")
    raise SystemExit(CLEAN)


# Register with the CLI group
register(render_registry)

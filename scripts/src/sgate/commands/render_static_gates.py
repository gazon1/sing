"""sgate.commands.render_static_gates — render the gate lines of static-gates.sh.

The shell registry used to be written by hand: 32 ``gate <mode> "<name>"
<command>`` lines, each next to the prose that explains it, while
``sgate.commands._registry.GATES`` carried the same names and modes. Two
declarations of one fact, and ``scripts/check-gate-registries.py`` existed to
compare them — a check that proves two copies agree instead of removing the
second copy.

This command is the removal. The declarations live in
``sgate.gate_manifest.PLACEMENTS``; the region of ``scripts/ci/static-gates.sh``
between the generated markers is rendered from them. ``--check`` compares the
file with what the manifest renders today and fails when they differ, so the
file cannot drift away from the manifest without a gate noticing.

Exit codes:
  0 = clean (the file was written, or it is already current)
  1 = --check: the file is not what the manifest renders
  2 = the manifest could not be read, or the file could not be written
"""
from __future__ import annotations

import json as _json
import re
from pathlib import Path

import click

from sgate.commands.base import CLEAN, CONFIG_ERROR, INTERNAL_ERROR, register
from sgate.log import logger

#: The markers that delimit the generated region of the shell registry.
BEGIN_MARKER = "# >>> BEGIN GENERATED: sgate render-static-gates — do not edit by hand >>>"
END_MARKER = "# <<< END GENERATED: sgate render-static-gates >>>"

#: The manifest this command renders from.
ROOT = Path(__file__).resolve().parent.parent.parent.parent


def _modes() -> dict[str, str]:
    """Every gate name and its mode, from the manifest of gates."""
    from sgate.commands._registry import GATES

    return {spec.name: spec.mode.value for spec in GATES}


def _rendered() -> str:
    """The generated region, as the manifest renders it today."""
    from sgate.gate_manifest import render_static_registry

    return render_static_registry(_modes())


def _replace_region(text: str, rendered: str) -> str:
    """The file with its generated region replaced by ``rendered``."""
    begin = text.find(BEGIN_MARKER)
    end = text.find(END_MARKER)
    if begin == -1 or end == -1:
        raise ValueError(
            "the generated markers are missing from the shell registry — the file "
            "is not the one this command generates"
        )
    head = text[: begin + len(BEGIN_MARKER)]
    tail = text[end:]
    return f"{head}\n{rendered}{tail}"


@click.command("render-static-gates")
@click.option("--check", is_flag=True, help="Fail if the shell registry is outdated (the default)")
@click.option("--write", is_flag=True, help="Rewrite the generated region instead of checking it")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def render_static_gates(check: bool, write: bool, emit_json: bool) -> None:
    """Generate the gate lines of scripts/ci/static-gates.sh from the manifest.

    The command checks by default and writes only with ``--write``. That is the
    opposite of its sibling ``render-registry``, and the reason is the JSON
    contract probe: that probe runs every ``sgate``-dispatched gate with
    ``--json`` and no other flag, so a command that wrote by default would
    rewrite the file it is supposed to certify — the gate would pass by
    repairing the thing it was asked to check.
    """
    from sgate.repo import STATIC_GATES_SH

    try:
        rendered = _rendered()
    except Exception as exc:  # noqa: BLE001 — any failure here means "not renderable"
        logger.error(f"the gate manifest could not be read: {exc}")
        raise SystemExit(CONFIG_ERROR)

    try:
        current = STATIC_GATES_SH.read_text(encoding="utf-8")
    except OSError as exc:
        logger.error(f"{STATIC_GATES_SH} could not be read: {exc}")
        raise SystemExit(CONFIG_ERROR)

    try:
        updated = _replace_region(current, rendered)
    except ValueError as exc:
        logger.error(str(exc))
        raise SystemExit(CONFIG_ERROR)

    if not write:
        if current != updated:
            if emit_json:
                click.echo(_json.dumps(
                    {"status": "outdated", "passed": False, "path": str(STATIC_GATES_SH)}
                ))
            else:
                logger.warning(
                    "the shell registry is outdated — run `sgate render-static-gates --write`"
                )
            raise SystemExit(CONFIG_ERROR)
        if emit_json:
            click.echo(_json.dumps(
                {"status": "current", "passed": True, "path": str(STATIC_GATES_SH)}
            ))
        else:
            logger.info("the shell registry is current")
        raise SystemExit(CLEAN)

    try:
        STATIC_GATES_SH.write_text(updated, encoding="utf-8")
    except OSError as exc:
        logger.error(f"failed to write {STATIC_GATES_SH}: {exc}")
        raise SystemExit(CONFIG_ERROR)

    if emit_json:
        click.echo(_json.dumps(
            {"status": "generated", "passed": True, "path": str(STATIC_GATES_SH)}
        ))
    else:
        logger.info(f"generated {STATIC_GATES_SH}")
    raise SystemExit(CLEAN)


#: The gate lines the manifest renders, one per line — the shape a reader of
#: the shell registry expects. Exposed for the tests that pin the rendering.
def rendered_lines() -> list[str]:
    """The generated region as a list of lines."""
    return _rendered().splitlines()


#: A gate line, as the shell registry spells it.
GATE_LINE = re.compile(r'^gate (blocking|advisory) "([^"]+)" (.+)$')


register(render_static_gates)

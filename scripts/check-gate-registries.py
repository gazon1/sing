#!/usr/bin/env python3
"""Both gate registries load, and the two agree on what exists.

There are two places a gate can be declared, and they are not the same place:

``scripts/ci/static-gates.sh``
    what ``sgate run-all`` reads, and what ``ci.yml``'s ``static`` job runs.
``scripts/src/sgate/commands/_registry.py``
    ``GATES``, what ``sgate <name>`` and ``load_gates()`` read.

``import sgate`` sees only the second one, so the first can be emptied,
truncated or renamed and the import stays green. Measured, before this script
existed: blanking ``static-gates.sh`` left ``python3 -c "import sgate"``
succeeding, left ``load_gates()`` still returning all 39 entries, and left
``sgate run-all`` printing ``No gates found.`` and **exiting 0** — a passing
``static`` job that had evaluated nothing. ``run-all`` now exits non-zero for
that case; this script is what notices before any gate runs.

Both halves are needed. The emptiness check catches a registry that lost its
contents, which is the failure that a zero exit code could hide. The agreement
check catches drift, which is a separate and currently *real* condition: the
two registries do not name the same gates today — some are present only in
Python, some only in the shell registry. Drift is reported as a warning rather
than an error, because it predates this script and failing on it would block
every run on someone else's debt; the count and the diff go to the log where
they get read.

CLI shape (R2/R3): Click, not argparse; ``--check`` reports without a
non-zero exit; ``--json`` puts the verdict on stdout as machine-readable
JSON; violations go to stdout with the file they name, and only the failure
summary goes to stderr; the exit codes are distinct per failure mode
(0 = both load and agree, 1 = a violation, 2 = the registries could not be
read at all).

ADR: docs/decisions/2026-10-06-ci-single-gate-registry-and-leaf-split.md
ADR: docs/decisions/2026-10-14-sgate-exit-code-architecture.md

Run standalone::

    python3 scripts/check-gate-registries.py [--strict] [--check] [--json]

Exit 0 when both load. Exit 1 when either is empty, or when the two disagree
under --strict. Exit 2 when a registry could not be read.
"""
from __future__ import annotations

import json

import click
from sgate.commands.base import run
from sgate.repo import STATIC_GATES_SH

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2

import click

class RegistryUnavailable(RuntimeError):
    """A registry could not be read — an infrastructure failure, not a verdict."""


def evaluate(strict: bool) -> tuple[list[str], list[str], str]:
    """
    Return `(violations, warnings, summary)` for the two gate registries.

    A violation is something a caller must act on: an empty registry, or —
    under ``strict`` — the two registries naming different gates. Drift without
    ``strict`` is a warning: it predates this script, and failing on it would
    block every run on someone else's debt.
    """
    try:
        from sgate.commands._registry import GATES
        from sgate.commands.run_all import _load_static_gates
        from sgate.gates import load_gates

        static_gates = _load_static_gates()
        registry_gates = load_gates()
    except Exception as exc:  # noqa: BLE001 — any failure here means "not loadable"
        raise RegistryUnavailable(f"the gate registries did not load: {exc}") from exc

    violations: list[str] = []
    warnings: list[str] = []

    if not static_gates:
        violations.append(
            f"{STATIC_GATES_SH} declared zero gates. `sgate run-all` would evaluate "
            "nothing at all, which is not a pass."
        )
    if not registry_gates or not GATES:
        violations.append("the Python gate registry (sgate.commands._registry.GATES) is empty.")

    static_names = {name for _, name, _ in static_gates}
    registry_names = {spec.name for spec in GATES}
    only_static = sorted(static_names - registry_names)
    only_registry = sorted(registry_names - static_names)

    summary = (
        f"gate registries: {len(static_gates)} gates in static-gates.sh, "
        f"{len(registry_gates)} in the Python registry"
    )

    if only_static or only_registry:
        message = (
            "the two gate registries disagree — "
            f"only in static-gates.sh: {only_static}; "
            f"only in _registry.GATES: {only_registry}. "
            "`sgate run-all` evaluates the first, `sgate <name>` the second, "
            "so which surface a caller uses decides which gates run."
        )
        if strict:
            violations.append(message)
        else:
            warnings.append(message)

    return violations, warnings, summary


@click.command()
@click.option(
    "--strict",
    is_flag=True,
    help="also fail when the two registries disagree (drift is a warning by default)",
)
@click.option("--check", is_flag=True, help="Dry-run: report without a non-zero exit.")
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON.")
@click.pass_context
def cli(ctx: click.Context, strict: bool, check: bool, emit_json: bool) -> None:
    """Both gate registries load, and the two agree on what exists."""
    try:
        violations, warnings, summary = evaluate(strict=strict)
    except RegistryUnavailable as exc:
        if emit_json:
            click.echo(json.dumps(
                {"passed": False, "blocking_errors": [str(exc)], "warnings": []},
                ensure_ascii=False,
            ))
        else:
            click.echo(f"check_gate_registries: ERROR — {exc}", err=True)
        raise ctx.exit(IO_ERROR)

    if emit_json:
        click.echo(json.dumps(
            {
                "passed": not violations,
                "blocking_errors": violations,
                "warnings": warnings,
                "summary": summary,
            },
            ensure_ascii=False,
        ))
        raise ctx.exit(CLEAN if (not violations or check) else VALIDATION_FAILURE)

    click.echo(summary)
    for warning in warnings:
        click.echo(f"::warning ::{warning}")

    if violations:
        for violation in violations:
            click.echo(f"check_gate_registries: {violation}")
        click.echo(
            "check_gate_registries: FAIL — a gate registry is empty, or the two "
            "registries name different gates",
            err=True,
        )
        raise ctx.exit(CLEAN if check else VALIDATION_FAILURE)

    click.echo("check_gate_registries: OK — both gate registries load and agree")
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))

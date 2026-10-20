#!/usr/bin/env python3
"""check_gate_registries.py — the two gate registries agree on what exists.

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

<<<<<<< HEAD
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
=======
The other half is drift, and drift was real: the two files disagreed about
nine names while every check agreed. Three shapes of it, each caught here:

  - a gate line in the shell file with no registry entry — CI runs it,
    ``sgate <name>`` cannot dispatch it;
  - a registry entry with no gate line — ``sgate run-all`` never runs it, so a
    control for it measures nothing. The exceptions are named, with a reason,
    in ``NOT_RUN_IN_STATIC``;
  - a name in ``NOT_RUN_IN_STATIC`` that is not a registered gate any more —
    a declaration that outlived its gate, and can hide one from the check
    above.

This was a warning for a while, because the drift predated the script and
failing on it would have blocked every run on someone else's debt. It is a
blocking gate now: the debt is paid, and a warning nobody reads is a check
that reports nothing.
>>>>>>> dc53ba4d (refactor(gates): one truth about what runs, and the heavy gates out of the static job)

Run standalone::

    python3 scripts/check-gate-registries.py [--strict] [--check] [--json]

<<<<<<< HEAD
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
=======
Exit 0 when the two registries agree. Exit 1 when they do not.
"""
from __future__ import annotations

import pathlib
import re
import sys
>>>>>>> dc53ba4d (refactor(gates): one truth about what runs, and the heavy gates out of the static job)

import click

<<<<<<< HEAD
class RegistryUnavailable(RuntimeError):
    """A registry could not be read — an infrastructure failure, not a verdict."""

=======
from sgate.commands.base import CLEAN, IO_ERROR, VALIDATION_FAILURE, run

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The registry, resolved once per run so `sgate <name>` can be traced to a script.
_REGISTRY_BY_NAME: dict[str, object] = {}


def _covered_scripts(shell_gates: list[tuple[str, str, str]]) -> set[str]:
    """The gate scripts the shell registry actually runs.

    Three steps, in order:

    1. a script named in a gate command is run by that gate;
    2. ``sgate <name>`` runs whatever the registry says that name is — so the
       command resolves through the registry to a script;
    3. a script that a covered script runs is itself covered. This is the
       delegation case: a cluster gate names its members in its own source, and
       the members are run by the cluster.

    The question this answers is "does this gate run?", not "is this name
    mentioned?". The two are different, and the difference is how a registry
    entry ended up describing a gate that no surface executed.
    """
    covered: set[str] = set()
    for _mode, _name, command in shell_gates:
        for match in re.finditer(
            r"(?:^|[\s'\"])((?:scripts/)?(?:check|gen)[-_][\w.-]+\.(?:py|sh))", command
        ):
            covered.add(match.group(1))
        tokens = command.split()
        if tokens and tokens[0] == "sgate" and len(tokens) > 1:
            spec = _REGISTRY_BY_NAME.get(tokens[1])
            if spec is not None and spec.script:
                covered.add(str(spec.script))

    frontier = list(covered)
    for _ in range(2):
        found: set[str] = set()
        for script in frontier:
            path = ROOT / script
            if not path.is_file():
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except OSError:
                continue
            for match in re.finditer(
                r"scripts/(?:check|gen)[-_][\w.-]+\.(?:py|sh)", text
            ):
                found.add(match.group(0))
        new = found - covered
        covered |= new
        frontier = sorted(new)
    return covered


@click.command()
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.pass_context
def cli(ctx: click.Context, emit_json: bool) -> None:
    """Check that both gate registries describe the same set of gates."""
    from sgate.commands._registry import GATES, NOT_RUN_IN_STATIC
    from sgate.commands.run_all import _load_static_gates

    global _REGISTRY_BY_NAME
    _REGISTRY_BY_NAME = {spec.name: spec for spec in GATES}
>>>>>>> dc53ba4d (refactor(gates): one truth about what runs, and the heavy gates out of the static job)

def evaluate(strict: bool) -> tuple[list[str], list[str], str]:
    """
    Return `(violations, warnings, summary)` for the two gate registries.

    A violation is something a caller must act on: an empty registry, or —
    under ``strict`` — the two registries naming different gates. Drift without
    ``strict`` is a warning: it predates this script, and failing on it would
    block every run on someone else's debt.
    """
    try:
<<<<<<< HEAD
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
=======
        shell_gates = _load_static_gates()
    except Exception as exc:  # noqa: BLE001 — any failure here means "not loadable"
        click.echo(
            f"check_gate_registries.py: ERROR — the shell registry did not load: {exc}",
            err=True,
        )
        raise ctx.exit(IO_ERROR)
>>>>>>> dc53ba4d (refactor(gates): one truth about what runs, and the heavy gates out of the static job)

    if not shell_gates:
        click.echo(
            "check_gate_registries.py: ERROR — scripts/ci/static-gates.sh declares zero "
            "gates. `sgate run-all` would evaluate nothing at all, which is not a pass.",
            err=True,
        )
        raise ctx.exit(IO_ERROR)
    if not GATES:
        click.echo(
            "check_gate_registries.py: ERROR — the Python gate registry is empty.",
            err=True,
        )
        raise ctx.exit(IO_ERROR)

    covered = _covered_scripts(shell_gates)
    shell_names = {name for _, name, _ in shell_gates}
    registry_names = {spec.name for spec in GATES}
    shell_modes = {name: mode for mode, name, _ in shell_gates}
    registry_modes = {spec.name: spec.mode.value for spec in GATES}

<<<<<<< HEAD
    summary = (
        f"gate registries: {len(static_gates)} gates in static-gates.sh, "
        f"{len(registry_gates)} in the Python registry"
    )
=======
    errors: list[str] = []
>>>>>>> dc53ba4d (refactor(gates): one truth about what runs, and the heavy gates out of the static job)

    for name in sorted(shell_names - registry_names):
        errors.append(
            f"'{name}' is a gate line in scripts/ci/static-gates.sh with no entry in "
            "_registry.GATES — `sgate run-all` runs it, `sgate <name>` cannot dispatch "
            "it. Add a GateSpec with the same display name."
        )
<<<<<<< HEAD
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
=======

    for name in sorted(shell_names & registry_names):
        shell_mode = shell_modes[name]
        registry_mode = registry_modes[name]
        if shell_mode != registry_mode:
            errors.append(
                f"'{name}' is {shell_mode} in scripts/ci/static-gates.sh and "
                f"{registry_mode} in _registry.GATES — the two registries disagree about "
                "whether a failing gate stops the build. One surface reads the first, "
                "the other the second, and which one a caller uses decides what a red "
                "gate means."
            )

    for name in sorted(registry_names - shell_names - set(NOT_RUN_IN_STATIC)):
        spec = _REGISTRY_BY_NAME[name]
        script = str(spec.script) if spec.script else ""
        if script and script in covered:
            # A member of a cluster: the shell registry names the cluster, the
            # cluster runs this one. It runs.
            continue
        errors.append(
            f"'{name}' is registered in _registry.GATES with no gate line in "
            "scripts/ci/static-gates.sh — `sgate run-all` never dispatches it, so a "
            "control for it measures nothing. Add a gate line, or record it in "
            "NOT_RUN_IN_STATIC with where it runs instead."
        )

    for name in sorted(set(NOT_RUN_IN_STATIC) - registry_names):
        errors.append(
            f"'{name}' is in NOT_RUN_IN_STATIC but is not a registered gate any more — "
            "the declaration outlived its gate. Remove the entry: it can hide a gate "
            "that nobody runs."
        )

    if emit_json:
        import json

        click.echo(
            json.dumps(
                {
                    "passed": not errors,
                    "shell_gates": len(shell_gates),
                    "registry_gates": len(GATES),
                    "errors": errors,
                },
                indent=2,
            )
        )
        raise ctx.exit(VALIDATION_FAILURE if errors else CLEAN)

    if errors:
        click.echo(
            f"check_gate_registries.py: {len(errors)} disagreement(s) between the two "
            "gate registries",
            err=True,
        )
        for message in errors:
            click.echo(f"  {message}", err=True)
        raise ctx.exit(VALIDATION_FAILURE)

    click.echo(
        f"check_gate_registries.py: OK — {len(shell_gates)} gates in static-gates.sh, "
        f"{len(GATES)} in the Python registry, "
        f"{len(NOT_RUN_IN_STATIC)} declared as running elsewhere"
    )
>>>>>>> dc53ba4d (refactor(gates): one truth about what runs, and the heavy gates out of the static job)
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))

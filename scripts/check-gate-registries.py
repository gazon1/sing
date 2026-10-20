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

The other half is drift, and drift was real: the two files disagreed about
nine names while every check agreed. Four shapes of it, each caught here:

  - a gate line in the shell file with no registry entry — CI runs it,
    ``sgate <name>`` cannot dispatch it;
  - a registry entry with no gate line and nothing that runs it — ``sgate
    run-all`` never dispatches it, so a control for it measures nothing. The
    exceptions are named, with a reason, in ``NOT_RUN_IN_STATIC``;
  - the same gate described with two different modes — one surface says it
    blocks the build, the other says it only warns, and which one a caller
    reads decides what a red gate means;
  - a name in ``NOT_RUN_IN_STATIC`` that is not a registered gate any more —
    a declaration that outlived its gate, and can hide one from the check
    above.

This was a warning for a while, because the drift predated the script and
failing on it would have blocked every run on someone else's debt. It is a
blocking gate now: the debt is paid, and a warning nobody reads is a check
that reports nothing.

CLI shape (R2/R3): Click, not argparse; ``--check`` reports without a
non-zero exit; ``--json`` puts the verdict on stdout as machine-readable
JSON; violations go to stdout with the file they name, and only the failure
summary goes to stderr; the exit codes are distinct per failure mode
(0 = both load and agree, 1 = a violation, 2 = the registries could not be
read at all).

ADR: docs/decisions/2026-10-06-ci-single-gate-registry-and-leaf-split.md
ADR: docs/decisions/2026-10-14-sgate-exit-code-architecture.md
ADR: docs/decisions/2026-10-20-gate-clusters-and-the-two-registries.md

Run standalone::

    python3 scripts/check-gate-registries.py [--strict] [--check] [--json]

Exit 0 when both load. Exit 1 when either is empty, or when the two disagree
under --strict. Exit 2 when a registry could not be read.
"""
from __future__ import annotations

import json
import pathlib
import re

import click
from sgate.commands.base import CLEAN, IO_ERROR, VALIDATION_FAILURE, run
from sgate.repo import STATIC_GATES_SH

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The registry, resolved once per run so `sgate <name>` can be traced to a script.
_REGISTRY_BY_NAME: dict[str, object] = {}

import click

class RegistryUnavailable(RuntimeError):
    """A registry could not be read — an infrastructure failure, not a verdict."""


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
            script = getattr(spec, "script", None)
            if script:
                covered.add(str(script))

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


def _module_classification_problems() -> list[str]:
    """Every command module is classified, and every classified module exists.

    `sgate --help` lists the gates and the utilities side by side, so nothing
    says which is which. The classification is the statement, and this check is
    what keeps it true: a module nobody classified is a decision nobody made,
    and a classified module that is gone is a claim about a file that no longer
    exists.
    """
    from sgate.commands._registry import COMMAND_MODULES

    commands_dir = ROOT / "scripts" / "src" / "sgate" / "commands"
    on_disk = {
        path.stem
        for path in commands_dir.glob("*.py")
        if path.stem != "__init__"
    }
    problems: list[str] = []
    for name in sorted(on_disk - set(COMMAND_MODULES)):
        problems.append(
            f"'{name}' is a command module nobody classified — add it to "
            "COMMAND_MODULES in _registry.py with what it is (gate, utility, "
            "suite, driver, infrastructure)."
        )
    for name in sorted(set(COMMAND_MODULES) - on_disk):
        problems.append(
            f"'{name}' is classified in COMMAND_MODULES but no longer exists — the "
            "classification is a claim about a file that is gone."
        )
    return problems


def evaluate(strict: bool) -> tuple[list[str], list[str], str]:
    """
    Return `(violations, warnings, summary)` for the two gate registries.

    A violation is something a caller must act on: an empty registry, or —
    under ``strict`` — the two registries naming different gates, describing
    one gate with two different modes, or naming a gate that nothing runs.
    Drift without ``strict`` is a warning: it predates this script, and
    failing on it would block every run on someone else's debt.
    """
    try:
        from sgate.commands._registry import GATES, NOT_RUN_IN_STATIC
        from sgate.commands.run_all import _load_static_gates
        from sgate.gates import load_gates

        global _REGISTRY_BY_NAME
        _REGISTRY_BY_NAME = {spec.name: spec for spec in GATES}

        shell_gates = _load_static_gates()
        registry_gates = load_gates()
    except Exception as exc:  # noqa: BLE001 — any failure here means "not loadable"
        raise RegistryUnavailable(f"the gate registries did not load: {exc}") from exc

    violations: list[str] = []
    warnings: list[str] = []

    if not shell_gates:
        violations.append(
            f"{STATIC_GATES_SH} declared zero gates. `sgate run-all` would evaluate "
            "nothing at all, which is not a pass."
        )
    if not registry_gates or not GATES:
        violations.append("the Python gate registry (sgate.commands._registry.GATES) is empty.")

    covered = _covered_scripts(shell_gates)
    shell_names = {name for _, name, _ in shell_gates}
    registry_names = {spec.name for spec in GATES}
    shell_modes = {name: mode for mode, name, _ in shell_gates}
    registry_modes = {spec.name: spec.mode.value for spec in GATES}

    summary = (
        f"gate registries: {len(shell_gates)} gates in static-gates.sh, "
        f"{len(registry_gates)} in the Python registry"
    )

    def report(message: str) -> None:
        if strict:
            violations.append(message)
        else:
            warnings.append(message)

    for name in sorted(shell_names - registry_names):
        report(
            f"'{name}' is a gate line in scripts/ci/static-gates.sh with no entry in "
            "_registry.GATES — `sgate run-all` runs it, `sgate <name>` cannot dispatch "
            "it. Add a GateSpec with the same display name."
        )

    for name in sorted(shell_names & registry_names):
        shell_mode = shell_modes[name]
        registry_mode = registry_modes[name]
        if shell_mode != registry_mode:
            report(
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
        report(
            f"'{name}' is registered in _registry.GATES with no gate line in "
            "scripts/ci/static-gates.sh — `sgate run-all` never dispatches it, so a "
            "control for it measures nothing. Add a gate line, or record it in "
            "NOT_RUN_IN_STATIC with where it runs instead."
        )

    for name in sorted(set(NOT_RUN_IN_STATIC) - registry_names):
        report(
            f"'{name}' is in NOT_RUN_IN_STATIC but is not a registered gate any more — "
            "the declaration outlived its gate. Remove the entry: it can hide a gate "
            "that nobody runs."
        )

    for problem in _module_classification_problems():
        report(problem)

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

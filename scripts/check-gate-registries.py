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
import sys

import click
from sgate.commands.base import CLEAN, IO_ERROR, VALIDATION_FAILURE, run
from sgate.repo import STATIC_GATES_SH

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The registry this gate reads must be *this* checkout's. `sgate` is installed
#: as an editable package, and a sibling worktree can be what is installed — so
#: the local `scripts/src` goes first, and the resolved package is checked
#: against this repository before anything is read from it. A gate that compared
#: this checkout's shell registry with another checkout's Python registry would
#: report drift that does not exist, and hide drift that does.
_SRC = ROOT / "scripts" / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

#: The registry, resolved once per run so `sgate <name>` can be traced to a script.
_REGISTRY_BY_NAME: dict[str, object] = {}

import click

class RegistryUnavailable(RuntimeError):
    """A registry could not be read — an infrastructure failure, not a verdict."""


def _covered_scripts(shell_gates: list[tuple[str, str, str]]) -> set[str]:
    """The gate scripts the shell registry actually runs.

    The scan lives in `sgate.gate_reachability`, which `check-gate-wiring.py`
    uses for the same question over every surface: a script named in a gate
    command, `sgate <name>` resolved through the registry, and the delegation a
    gate script performs on another.
    """
    from sgate import gate_reachability

    return gate_reachability.covered_scripts(ROOT, shell_gates, _REGISTRY_BY_NAME)


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


def _wrapper_members(script: str) -> list[str] | None:
    """The member names a cluster wrapper declares, or None if it cannot be read.

    The wrapper is the authority on what it runs: it holds the list it executes.
    The manifest declares the same list, and the two are compared here — a
    wrapper that runs a member the manifest does not know, or a manifest that
    names a member the wrapper does not run, is a gate that runs on no one (or
    a member that runs on nobody's account).
    """
    import importlib.util

    path = ROOT / script
    if not path.is_file():
        return None
    spec = importlib.util.spec_from_file_location(f"_cluster_{path.stem}", path)
    if spec is None or spec.loader is None:
        return None
    module = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(module)
    except Exception:  # noqa: BLE001 — a wrapper that cannot be imported says nothing
        return None
    members = getattr(module, "MEMBERS", None)
    if members is None:
        return None
    return [getattr(member, "name", str(member)) for member in members]


def _cluster_problems(
    registry_by_name: dict[str, object],
) -> list[str]:
    """Every cluster's declared membership matches the wrapper's own list.

    The membership used to be inferred: a regex over the wrapper's source for
    the file names it mentions. Renaming a line inside a wrapper then dropped a
    gate from the covered set — nothing failed, the gate simply stopped being
    known to run. It is declared now, and this is what holds the declaration to
    the wrapper.
    """
    from sgate.gate_manifest import CLUSTERS

    problems: list[str] = []
    for cluster, members in sorted(CLUSTERS.items()):
        spec = registry_by_name.get(cluster)
        if spec is None:
            # The registry under test does not have this cluster — a synthetic
            # registry, or a cluster whose gate is gone. The unit tests cover
            # the second case; here it would be noise.
            continue
        script = str(getattr(spec, "script", "") or "")
        if not script:
            problems.append(
                f"'{cluster}' is a cluster in the manifest but names no script — a "
                "cluster nobody runs."
            )
            continue
        declared = list(members)
        wrapper = _wrapper_members(script)
        if wrapper is None:
            problems.append(
                f"'{cluster}' runs {script}, which declares no MEMBERS list — the "
                "cluster's membership cannot be checked against the wrapper that "
                "runs it."
            )
            continue
        if sorted(wrapper) != sorted(declared):
            problems.append(
                f"'{cluster}' declares members {sorted(declared)} but "
                f"{script} runs {sorted(wrapper)} — the manifest and the wrapper "
                "disagree about what the cluster runs. A member the wrapper does "
                "not run is a gate that runs on no one; a member the manifest does "
                "not name is a gate nobody looks for."
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

    # ── The placement table ───────────────────────────────────────────────────
    # One table says where every gate runs. A gate that runs elsewhere says so,
    # with the place that runs it — a gate that runs on no one reports success
    # forever, and a placement without a pointer is a claim nobody can check.
    for name, placement in sorted(NOT_RUN_IN_STATIC.items()):
        if placement is None:
            continue
        if not placement.reason or not placement.tracked_by:
            report(
                f"'{name}' runs somewhere else, but the placement does not say "
                "where (tracked_by) or why (reason) — a gate that runs on no one "
                "is a gate that reports success forever."
            )

    for problem in _cluster_problems(_REGISTRY_BY_NAME):
        report(problem)

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

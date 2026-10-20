#!/usr/bin/env python3
"""check-waiver-references.py — a waiver whose premise is prose is a waiver that can rot.

Why this exists
---------------
A gate that is *red on a clean tree* cannot be proven by sabotage: the sabotage
would prove nothing, because the gate was already failing. Such a control is
waived from the clean-run guard (`needs_clean_run=False`) — and the waiver is
paid for with a promise about where the compensating pass happens.

Today exactly one control is waived:

    detekt-rule-coverage-floor
        the task is `:detekt-rules:koverVerify`, and the promise is that **both
        surfaces run the very same task on the very same tree a step later** —
        `check.sh` step 8b and CI's `Kover verify (detekt-rules)` step.

That promise lives in a comment. Nothing checks it. So the failure mode is
silent and one-sided: someone renames the CI step, or deletes the
`:detekt-rules:koverVerify` line from it, and the waiver keeps waiving — while
the compensating pass it was granted *for* no longer exists. The gate stays
red, the waiver stays, and the reason nobody notices is that a permanent
red gate is exactly the thing a waiver exists to stop being.

The same is true of the reference itself. `tracked_by` is a pointer — to a
file, and to an anchor inside it. A pointer that no longer resolves is a
pointer to nothing, and it is the only thing the waiver has instead of a date.

What this gate asserts
----------------------
1. **Every reference resolves.** A `tracked_by` of the form `path#anchor`
   names a file that exists, and the anchor names something *in* it. For a
   GitHub workflow the anchor must be a **step name** — not merely a string
   that occurs somewhere in the file. `Kover verify (detekt-rules)` occurs
   three times in `ci.yml`: twice in a comment explaining why the step is
   where it is, and once as the step itself. Only the third one is the
   premise; a substring check would accept the first two and pass a waiver
   whose compensating pass had been deleted.
2. **Every waiver states a premise that is still true.** For a control that
   proves a Gradle task, the task must appear in the anchored step's `run:`
   block *and* in `check.sh` — the two surfaces the waiver's reason names.
3. **An expiry date that has passed is reported.** A waiver with a date is a
   promise with an end; a date in the past is a promise nobody kept.
4. **The two copies of the control agree.** The registry declares the
   waiver; `scripts/check-gate-wiring.py` carries the hand-written twin that
   actually runs. If one copy waives the clean-run guard and the other does
   not, the guard is applied inconsistently — and neither copy notices.

It does **not** add the clean-run Gradle pass the waiver was granted to avoid.
That pass costs a full configuration cycle on every run of the wiring gate, on
both surfaces, to learn something the two surfaces already establish a step
later. The premise of the waiver is sound; what was missing was the check that
the premise still holds.

Usage:
    python3 scripts/check-waiver-references.py [--verbose]

Exit codes:
    0 — every waiver's premise still holds
    1 — a reference does not resolve, or a premise is no longer true
    2 — the registry could not be read
"""

from __future__ import annotations

import datetime
import importlib.util
import json
import pathlib
import sys

import click
from sgate.commands.base import run
from sgate.repo import ROOT

#: The gate registry and the wiring twin live here, relative to the repo root.
REGISTRY_MODULE = "sgate.commands._registry"
WIRING_SCRIPT = ROOT / "scripts" / "check-gate-wiring.py"
CHECK_SH = ROOT / "check.sh"

#: A control kind that can be waived from the clean-run guard. `fixture` cannot
#: (it never runs against the clean tree — it builds its own, so the guard is
#: inapplicable rather than waived), and `exempt` has its own `reason`.
WAIVABLE_KINDS = frozenset({"sabotage", "gradle"})


# ── Registry access ───────────────────────────────────────────────────────────


_WIRING_MODULE: object | None = None


def load_wiring_module() -> object:
    """Import `check-gate-wiring.py` as a module, once.

    `sgate` is installed as an editable package, so a bare import can resolve
    into a sibling worktree. `check-gate-wiring.py` has the hardened loader for
    exactly this reason; reuse it rather than reimplementing it, so this gate
    reads the same registry the wiring gate reads — and the same twin.

    The module is executed, not merely parsed: the twin list is built at import
    time, and `dataclasses` needs the module registered in `sys.modules`
    *before* it is executed.
    """
    global _WIRING_MODULE
    if _WIRING_MODULE is not None:
        return _WIRING_MODULE

    spec = importlib.util.spec_from_file_location("_waiver_wiring", WIRING_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {WIRING_SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    try:
        spec.loader.exec_module(module)
    except Exception:
        sys.modules.pop(spec.name, None)
        raise
    _WIRING_MODULE = module
    return module


def load_registry() -> object:
    """This checkout's gate registry, loaded through the hardened loader."""
    return load_wiring_module()._load_registry()  # noqa: SLF001


# ── Reference resolution ──────────────────────────────────────────────────────


def _workflow_step_runs(path: pathlib.Path) -> dict[str, list[str]]:
    """`{step name: every run block of every step with that name}` in a workflow.

    A workflow may have two steps with the same name in different jobs, so the
    value is a list: the premise holds if **any** of them runs the task.
    """
    import yaml  # noqa: PLC0415 — imported late so a missing PyYAML is one error, not two

    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    runs: dict[str, list[str]] = {}
    jobs = data.get("jobs") or {}
    if not isinstance(jobs, dict):
        return runs
    for job in jobs.values():
        if not isinstance(job, dict):
            continue
        for step in job.get("steps") or []:
            if not isinstance(step, dict):
                continue
            name = step.get("name")
            run_text = step.get("run")
            if not isinstance(name, str) or not isinstance(run_text, str):
                continue
            runs.setdefault(name, []).append(run_text)
    return runs


def resolve_reference(
    reference: str, root: pathlib.Path | None = None
) -> tuple[str | None, str | None, list[str]]:
    """Resolve one `tracked_by` reference.

    Returns `(anchor, anchor_kind, problems)`. `anchor` is `None` when the
    reference carries no anchor. `anchor_kind` is `"step"` when the anchor was
    resolved to a workflow step, `"text"` when it was resolved to a file that
    contains it, and `None` when the reference did not resolve.

    `root` is the repository root the reference is relative to. It is a
    parameter so the rule can be exercised against a synthetic tree; the gate
    itself always passes the real one.
    """
    base = ROOT if root is None else root
    problems: list[str] = []
    if "#" in reference:
        relative, _, anchor = reference.partition("#")
    else:
        relative, anchor = reference, ""

    target = base / relative
    if not target.is_file():
        # A bare reference may name an issue slug rather than a path.
        issue = base / ".specify" / "issues" / f"{relative}.yaml"
        if issue.is_file():
            return None, None, []
        problems.append(
            f"'{reference}' names '{relative}', which is not a file in this repository"
        )
        return None, None, problems

    if not anchor:
        return None, None, problems

    if target.suffix in {".yml", ".yaml"} and "workflows" in target.parts:
        steps = _workflow_step_runs(target)
        if anchor not in steps:
            problems.append(
                f"'{reference}': no step named '{anchor}' in {relative}. The anchor must "
                "name a step, not merely occur in the file — a string that appears only "
                "in a comment is not the step the reference points at."
            )
            return anchor, "step", problems
        return anchor, "step", problems

    if anchor not in target.read_text(encoding="utf-8"):
        problems.append(
            f"'{reference}': '{relative}' does not contain the anchor '{anchor}'"
        )
        return anchor, "text", problems
    return anchor, "text", problems


def _gradle_tasks(argv: tuple[str, ...]) -> list[str]:
    """The Gradle task paths in a control's command (`./gw -q :mod:task`)."""
    return [token for token in argv if token.startswith(":") and ":" in token[1:]]


# ── The check ─────────────────────────────────────────────────────────────────


def evaluate(verbose: bool = False) -> tuple[list[str], list[str], list[str]]:
    """Return `(violations, notes, listing)` for every control in the registry."""
    violations: list[str] = []
    notes: list[str] = []
    listing: list[str] = []

    registry = load_registry()
    controls = getattr(registry, "GATE_CONTROLS", {})

    check_sh_text = CHECK_SH.read_text(encoding="utf-8") if CHECK_SH.is_file() else ""

    for name, control in sorted(controls.items()):
        reference = control.tracked_by
        is_waiver = (
            control.needs_clean_run is False and control.kind in WAIVABLE_KINDS
        )

        if verbose:
            listing.append(
                f"  {name}: kind={control.kind} "
                f"needs_clean_run={control.needs_clean_run} "
                f"until={control.until or '-'} tracked_by={reference or '-'}"
            )

        # (3) An expiry that has passed.
        if control.until:
            try:
                deadline = datetime.date.fromisoformat(control.until)
            except ValueError:
                violations.append(
                    f"{name}: `until` is '{control.until}', which is not an ISO date"
                )
            else:
                if deadline < datetime.date.today():
                    violations.append(
                        f"{name}: the waiver's expiry date {deadline.isoformat()} has "
                        "passed — the waiver has to be renewed or the gate fixed"
                    )

        if not reference:
            if is_waiver:
                violations.append(
                    f"{name}: waived from the clean-run guard with neither an `until` "
                    "date nor a `tracked_by` reference — a waiver that points at nothing "
                    "is how a permanently-red gate becomes noise nobody reads"
                )
            continue

        anchor, kind, problems = resolve_reference(reference)
        violations.extend(f"{name}: {problem}" for problem in problems)
        if problems:
            continue

        # (2) The premise of a waiver: the compensating pass still runs, on both
        # surfaces the waiver's reason names.
        if is_waiver:
            violations.extend(
                _premise_problems(
                    name=name,
                    control=control,
                    reference=reference,
                    anchor=anchor,
                    kind=kind,
                    check_sh_text=check_sh_text,
                )
            )
            if not _gradle_tasks(tuple(control.argv)):
                notes.append(
                    f"{name}: waived from the clean-run guard, tracked by {reference}. "
                    "It proves no Gradle task, so only the reference is checked."
                )

    # (4) The two copies of the controls must agree about which are waived.
    violations.extend(_waived_set_disagreements(controls))

    return violations, notes, listing


def _premise_problems(
    name: str,
    control: object,
    reference: str,
    anchor: str | None,
    kind: str | None,
    check_sh_text: str,
    root: pathlib.Path | None = None,
) -> list[str]:
    """The premise of a waived control: the compensating pass is still there.

    A waiver of this kind says "the task runs on the other surface, a step
    later". That sentence is a claim about two files, and both halves can rot
    without anybody noticing: the step can be renamed, and the task can be
    dropped from the step that replaced it. Either way the waiver keeps
    waiving and the pass it was granted for is gone.
    """
    problems: list[str] = []
    tasks = _gradle_tasks(tuple(control.argv))
    if not tasks:
        return problems

    step_runs: list[str] = []
    if kind == "step":
        base = ROOT if root is None else root
        workflow = base / reference.partition("#")[0]
        step_runs = _workflow_step_runs(workflow).get(anchor or "", [])

    for task in tasks:
        if not any(task in text for text in step_runs):
            problems.append(
                f"{name}: the waiver's premise is that '{task}' runs in the "
                f"step '{anchor}' of {reference.partition('#')[0]}, but that "
                f"step does not run '{task}' — the compensating pass the "
                "waiver was granted for is gone"
            )
        if task not in check_sh_text:
            problems.append(
                f"{name}: the waiver's premise is that '{task}' runs on both "
                f"surfaces, but check.sh never names '{task}' — the local "
                "rehearsal of the compensating pass is gone"
            )
    return problems


def _waived_set_disagreements(controls: dict) -> list[str]:
    """Compare the registry's waived set with the wiring twin's."""
    module = load_wiring_module()

    registry_waived = {
        name for name, control in controls.items() if control.needs_clean_run is False
    }
    twin_waived = {
        gate.name
        for gate in (
            *module.SCRIPT_GATES,
            *module.SABOTAGE_ONLY_GATES,
            *module.FIXTURE_GATES,
        )
        if gate.needs_clean_run is False
    }
    if registry_waived == twin_waived:
        return []
    only_registry = sorted(registry_waived - twin_waived)
    only_twin = sorted(twin_waived - registry_waived)
    return [
        "the registry and the wiring twin disagree about which controls are waived "
        f"from the clean-run guard — only in the registry: {only_registry or 'none'}; "
        f"only in the twin: {only_twin or 'none'}. The guard is applied inconsistently, "
        "and neither copy notices."
    ]


@click.command()
@click.option("--verbose", is_flag=True, help="List every control it inspected.")
@click.option("--check", is_flag=True, help="Dry-run: report without a non-zero exit.")
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON.")
@click.pass_context
def cli(ctx: click.Context, verbose: bool, check: bool, emit_json: bool) -> None:
    """A waiver whose premise is prose is a waiver that can rot."""
    try:
        violations, notes, listing = evaluate(verbose=verbose)
    except (OSError, RuntimeError, ValueError) as exc:
        if emit_json:
            click.echo(json.dumps({"passed": False, "blocking_errors": [str(exc)],
                                   "warnings": []}, ensure_ascii=False))
        else:
            click.echo(f"check_waiver_references: ERROR — {exc}", err=True)
        raise ctx.exit(2)

    if emit_json:
        click.echo(json.dumps(
            {
                "passed": not violations,
                "blocking_errors": violations,
                "warnings": notes,
            },
            ensure_ascii=False,
        ))
        raise ctx.exit(0 if (not violations or check) else 1)

    for line in listing:
        click.echo(line)
    for note in notes:
        click.echo(f"check_waiver_references: note: {note}")

    if violations:
        for violation in violations:
            click.echo(f"check_waiver_references: {violation}", err=True)
        click.echo(
            "check_waiver_references: FAIL — a waiver that points at nothing, or at "
            "something that has moved, waives nothing",
            err=True,
        )
        raise ctx.exit(0 if check else 1)

    click.echo(
        "check_waiver_references: OK — every waiver's premise still holds"
    )


if __name__ == "__main__":
    raise SystemExit(run(cli))

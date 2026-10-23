#!/usr/bin/env python3
"""check-hook-gates.py — the hook and CI must not drift apart silently.

What went wrong
---------------
The pre-push hook ran three of the forty-one gates and judged two of the eight
detekt modules. Both numbers were written as lines in a bash script, and
nothing compared them with the other side: the CI judged all eight modules,
the hook judged two, and the difference was recorded nowhere. A reader of the
hook saw "detekt" and read it as "detekt" — the whole tree.

A gate that is added to CI and not to the hook is the ordinary case and not a
bug; what is a bug is a difference nobody wrote down. So the difference is
written down — in `config/hooks/pre-push.json` — and this gate checks that the
code still matches the record, in both directions:

  - every gate the record says the hook runs is invoked by the hook;
  - every gate script the hook invokes is in the record;
  - the detekt modules the record says CI judges are the ones CI judges;
  - those modules are the ones the module graph knows about;
  - the hook does not name a detekt module itself — the set is computed, so a
    list written into the hook is drift waiting to happen;
  - the partiality is stated: the hook judges what the change reaches, CI
    judges everything.

The decision this gate enforces is recorded in
`docs/decisions/2026-10-23-the-local-verdict-judges-what-the-change-reaches.md`.

Usage:
    python3 scripts/check-hook-gates.py
    python3 scripts/check-hook-gates.py --json
"""

from __future__ import annotations

import json
import pathlib
import re
import sys

import click

from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = ROOT / 'config' / 'hooks' / 'pre-push.json'
HOOK = ROOT / '.githooks' / 'pre-push'
CI_WORKFLOW = ROOT / '.github' / 'workflows' / 'ci.yml'

#: A script the hook runs: `python3 "$REPO_ROOT/scripts/<path>"`.
HOOK_SCRIPT_RE = re.compile(r'\$REPO_ROOT/(scripts/[\w./-]+\.py)')

#: A detekt task named in the CI workflow.
CI_DETEKT_RE = re.compile(r':([\w-]+):detekt')

#: A detekt task named in the hook — a list written into the hook is drift.
HOOK_DETEKT_RE = re.compile(r':[\w-]+:detekt')


def load_module_graph():
    """The module graph — scripts/changed-modules.py — loaded by path.

    The graph is the single source of truth for which modules exist; loading
    it here is what makes "the record and the graph agree" a fact rather than
    a second hand-written list.
    """
    path = ROOT / 'scripts' / 'changed-modules.py'
    if not path.is_file():
        return None
    import importlib.util

    spec = importlib.util.spec_from_file_location('changed_modules', path)
    if spec is None or spec.loader is None:
        return None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def ci_detekt_modules(ci_path: pathlib.Path) -> set[str]:
    """The detekt modules the CI workflow judges."""
    if not ci_path.is_file():
        return set()
    return set(CI_DETEKT_RE.findall(ci_path.read_text(encoding='utf-8')))


def collect_errors(
    registry_path: pathlib.Path,
    hook_path: pathlib.Path,
    ci_path: pathlib.Path,
    graph_modules: set[str] | None = None,
) -> tuple[list[str], dict]:
    """The disagreements between the record, the hook and the CI.

    Returns the errors and the facts the report prints — so a caller can
    assert on either without re-reading the files.
    """
    errors: list[str] = []

    if not registry_path.is_file():
        errors.append(
            f'{registry_path.name} is missing — the record of what the hook judges '
            f'is the thing this gate reads',
        )
        registry: dict = {}
    else:
        try:
            registry = json.loads(registry_path.read_text(encoding='utf-8'))
        except json.JSONDecodeError as exc:
            errors.append(f'{registry_path.name} is not valid JSON: {exc}')
            registry = {}

    hook_text = hook_path.read_text(encoding='utf-8') if hook_path.is_file() else ''

    declared = [
        gate.get('script', '')
        for gate in registry.get('structural_gates', [])
        if isinstance(gate, dict)
    ]
    delegate = registry.get('verdict_delegate', '')
    if delegate:
        declared.append(delegate)

    hook_scripts = set(HOOK_SCRIPT_RE.findall(hook_text))

    for script in declared:
        if script and script not in hook_scripts:
            errors.append(
                f'the record says the hook runs {script}, and the hook does not invoke it — '
                f'either the hook lost the gate or the record is stale',
            )

    for script in sorted(hook_scripts):
        if script not in declared:
            errors.append(
                f'the hook runs {script}, and the record does not mention it — '
                f'a gate that is in the code but in no record is a gate nobody knows about',
            )

    detekt = registry.get('detekt', {}) if isinstance(registry.get('detekt'), dict) else {}
    recorded = set(detekt.get('judged_by_ci', []))
    outside = detekt.get('judged_by_ci_outside_the_module_graph', {})
    outside_modules = {task.split(':')[1] for task in outside} if isinstance(outside, dict) else set()
    recorded_modules = {task.split(':')[1] for task in recorded}

    ci_modules = ci_detekt_modules(ci_path)
    if recorded and ci_modules:
        expected = recorded_modules | outside_modules
        for module in sorted(ci_modules - expected):
            errors.append(
                f'CI judges :{module}:detekt, and the record does not list it — '
                f'the record says what only CI judges, so a module CI judges must be in it',
            )
        for module in sorted(expected - ci_modules):
            errors.append(
                f'the record says CI judges :{module}:detekt, and the CI workflow does not — '
                f'the record overstates what CI does',
            )

    if graph_modules is not None and recorded:
        for module in sorted(recorded_modules - set(graph_modules)):
            errors.append(
                f'the record lists :{module}:detekt, and the module graph has no such module — '
                f'the record names a module that does not exist',
            )

    if HOOK_DETEKT_RE.search(hook_text):
        errors.append(
            'the hook names a detekt module itself — the set of modules to judge is computed '
            'from the module graph, and a list written into the hook goes stale silently',
        )

    if delegate and delegate not in hook_scripts:
        errors.append(f'the hook does not delegate the detekt decision to {delegate}')

    partiality = detekt.get('partiality', '')
    if not partiality or 'CI' not in partiality:
        errors.append(
            'the record does not state the partiality — the hook judges what the change reaches '
            'and CI judges everything, so the difference has to be written down',
        )

    facts = {
        'structural_gates': len(declared) - (1 if delegate else 0),
        'ci_modules': len(recorded) + len(outside_modules),
        'partiality': partiality,
    }
    return errors, facts


@click.command()
@click.option('--check', is_flag=True, help='Dry-run: report without a non-zero exit.')
@click.option('--json', 'emit_json', is_flag=True, help='Emit the verdict as JSON.')
@click.pass_context
def cli(ctx: click.Context, check: bool, emit_json: bool) -> None:
    """Check that the hook, the CI and the record of both still agree."""
    graph = load_module_graph()
    graph_modules = set(graph.TASKS) if graph is not None else None
    errors, facts = collect_errors(REGISTRY, HOOK, CI_WORKFLOW, graph_modules)

    if emit_json:
        click.echo(json.dumps(
            {
                'passed': not errors,
                'blocking_errors': errors,
                'warnings': [],
            },
            ensure_ascii=False,
        ))
        raise ctx.exit(CLEAN if (not errors or check) else VALIDATION_FAILURE)

    for err in errors:
        click.echo(f'ERROR: {err}')

    if errors:
        click.echo('')
        click.echo('check-hook-gates: FAIL — the hook, the CI and the record of both disagree')
        raise ctx.exit(CLEAN if check else VALIDATION_FAILURE)

    click.echo(
        f'check-hook-gates: OK — the hook runs {facts["structural_gates"]} structural gates '
        f'and delegates detekt;',
    )
    click.echo(
        f'  CI judges {facts["ci_modules"]} detekt modules, the hook judges the ones the change reaches.',
    )
    if facts['partiality']:
        click.echo(f'  {facts["partiality"]}')
    raise ctx.exit(CLEAN)


if __name__ == '__main__':
    raise SystemExit(run(cli))

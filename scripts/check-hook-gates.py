#!/usr/bin/env python3
"""check-hook-gates.py — the hook and CI must not drift apart silently.

What went wrong
---------------
The pre-push hook judged two of the eight detekt modules and the CI judged
all of them. Both numbers were written as lines in a bash script, and nothing
compared them with the other side: the difference was recorded nowhere, and a
reader of the hook saw "detekt" and read it as "detekt" — the whole tree.

A gate that is added to CI and not to the hook is the ordinary case and not a
bug; what is a bug is a difference nobody wrote down. So the difference is
written down — in `config/hooks/pre-push.json` — and this gate checks that the
code still matches the record, in both directions:

  - the hook names no gate: the gates it runs are declared in the registry
    (`PRE_PUSH_STEPS`) and the hook reads them from there, so the only
    scripts it may name are the machinery and the verdict delegate;
  - the hook calls the delegate;
  - the hook does not name a detekt module itself — the set is computed, so a
    list written into the hook is drift waiting to happen;
  - the detekt modules the record says CI judges are the ones CI judges;
  - those modules are the ones the module graph knows about;
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

# The sgate package lives in this checkout's own `scripts/src`, and a sibling
# worktree is a different tree under the same package name: an import that
# happens first is already bound to it. When this file is loaded by a test
# harness via importlib, sys.path[0] is `scripts/tests` and the package is
# invisible — so this file's own location is authoritative.
_scripts_src = str(pathlib.Path(__file__).resolve().parent / 'src')
_added = _scripts_src not in sys.path
if _added:
    sys.path.insert(0, _scripts_src)

from sgate.commands._registry import PRE_PUSH_DECLARATION  # noqa: E402
from sgate.commands.base import run  # noqa: E402

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = ROOT / 'config' / 'hooks' / 'pre-push.json'
HOOK = ROOT / '.githooks' / 'pre-push'
CI_WORKFLOW = ROOT / '.github' / 'workflows' / 'ci.yml'

#: The script CI runs to correct the tree. It judges the same modules the
#: workflow judges, so it is a second place where the list of modules could be
#: written out by hand.
CI_DETEKT_SCRIPT = ROOT / 'scripts' / 'ci' / 'detekt-auto-fix.sh'

#: A script the hook runs: `python3 "$REPO_ROOT/scripts/<path>"` or
#: `python3 "$tree/scripts/<path>"` — the hook runs some gates against the
#: repository and some against the tree that is being pushed.
HOOK_SCRIPT_RE = re.compile(r'(?:\$REPO_ROOT|\$tree)/(scripts/[\w./-]+\.py)')

#: A detekt task named in the CI workflow.
CI_DETEKT_RE = re.compile(r':([\w-]+):detekt')

#: A detekt task named in the hook — a list written into the hook is drift.
HOOK_DETEKT_RE = re.compile(r':[\w-]+:detekt')


def load_module_graph():
    """The module graph — scripts/changed-modules.py — loaded by path.

    The graph is the single source of truth for which modules exist; loading
    it here is what makes "the record and the graph agree" a fact rather than
    a second hand-written list.

    The loader itself lives in ``sgate.module_graph``: another gate (the
    Gradle-task parity check) needs the same answer, and two loaders of the
    same file are two places where the answer can differ.
    """
    from sgate.module_graph import load_module_graph as _load

    return _load(ROOT)


def declared_hook_scripts() -> tuple[set[str], set[str], set[str]]:
    """The scripts the registry declares the hook runs, the tree rules, the machinery.

    The declaration lives in the sgate registry, because the hook and the test
    fixture that drives it already read it from there. This gate reads it too,
    so the three — hook, fixture, record — cannot drift apart.
    """
    from sgate.commands._registry import (
        PRE_PUSH_MACHINERY,
        PRE_PUSH_SCRIPTS,
        PRE_PUSH_TREE_RULES,
    )

    fixed = set(PRE_PUSH_SCRIPTS) - {f'scripts/{rule}.py' for rule in PRE_PUSH_TREE_RULES}
    rules = {f'scripts/{rule}.py' for rule in PRE_PUSH_TREE_RULES}
    return fixed, rules, set(PRE_PUSH_MACHINERY)


def command_lines(ci_path: pathlib.Path) -> list[str]:
    """The command lines of a CI file — the lines that are not comments.

    A comment is prose about a module, not a list of the modules CI judges,
    and prose is not a command: a file that only MENTIONS the graph has not
    asked it for anything.
    """
    if not ci_path.is_file():
        return []
    return [
        line.strip()
        for line in ci_path.read_text(encoding='utf-8').splitlines()
        if line.strip() and not line.strip().startswith('#')
    ]


def ci_detekt_modules(ci_path: pathlib.Path) -> set[str]:
    """The detekt modules a CI file NAMES in a command.

    Only the lines that are commands count. A comment that mentions
    `:shared:detekt` in passing — a paragraph about a past measurement, a
    sentence about the auto-correct flag — is prose about a module, not a list
    of the modules CI judges. Reading both is how a workflow ends up "judging"
    a module that only a paragraph mentions, and how a gate that compares two
    lists ends up comparing a list with a paragraph.
    """
    modules: set[str] = set()
    for line in command_lines(ci_path):
        modules.update(CI_DETEKT_RE.findall(line))
    return modules


def collect_errors(
    registry_path: pathlib.Path,
    hook_path: pathlib.Path,
    ci_path: pathlib.Path,
    graph: object | None = None,
    declared: set[str] | None = None,
    tree_rules: set[str] | None = None,
    ci_script_path: pathlib.Path | None = None,
    machinery: set[str] | None = None,
    declaration: str = '',
) -> tuple[list[str], dict]:
    """The disagreements between the record, the hook and the CI.

    Returns the errors and the facts the report prints — so a caller can
    assert on either without re-reading the files.

    ``graph`` is the module graph itself (scripts/changed-modules.py), not a
    set of names: the gate needs two views of it — every module it knows, and
    the modules that have a detekt verdict — and a set of names could carry
    only one of them.
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
    hook_scripts = set(HOOK_SCRIPT_RE.findall(hook_text))

    hook_section = registry.get('hook_scripts', {}) if isinstance(registry.get('hook_scripts'), dict) else {}
    delegate = hook_section.get('verdict_delegate', '')

    if declared is None:
        declared = set()
    if tree_rules is None:
        tree_rules = set()

    # The hook does not name a gate.
    #
    # The gates it runs are declared in the registry, and the hook reads them
    # from there: a script written into the hook is a gate the registry does
    # not know about, and the fixture that drives the hook stubs the
    # registry's list — so the hook would run a gate nobody stubbed, and the
    # failure would be about the stub rather than about the gate.
    #
    # What the hook may name is the machinery — the scripts it calls with
    # logic of their own — and the delegate that decides the detekt verdict.
    allowed = set(machinery or ()) | ({delegate} if delegate else set())
    # The declaration the gates come from is not a gate: the hook has to know
    # where it is. It is declared in the registry, so a path that does not
    # match the declaration is drift like any other.
    allowed.add(declaration or '')
    for script in sorted(hook_scripts - allowed):
        errors.append(
            f'the hook names {script} itself — the gates the hook runs are declared in the '
            f'registry, and a script written into the hook is a gate the registry does not '
            f'know about',
        )

    # And the hook has to read them from there. A hook that ran no gate and
    # said OK turned "not checked" into "checked and green".
    if declaration and declaration not in hook_text:
        errors.append(
            'the hook does not read the gates from the registry — the list of gates it runs '
            'has to come from the one declaration, or a gate added to the registry is a gate '
            'nobody runs',
        )

    if delegate and delegate not in hook_scripts:
        errors.append(f'the hook does not delegate the detekt decision to {delegate}')

    detekt = registry.get('detekt', {}) if isinstance(registry.get('detekt'), dict) else {}
    recorded = set(detekt.get('judged_by_ci', []))
    outside = detekt.get('judged_by_ci_outside_the_module_graph', {})
    outside_modules = {task.split(':')[1] for task in outside} if isinstance(outside, dict) else set()
    recorded_modules = {task.split(':')[1] for task in recorded}

    # The modules CI judges are the modules the BUILD declares and that have a
    # detekt verdict: CI asks the module graph for the list (the workflow and
    # `scripts/ci/detekt-auto-fix.sh` both run `changed-modules.py
    # --detekt-tasks`), so the list is read from the build and cannot be a list
    # somebody wrote out by hand.
    graph_modules: set[str] = set()
    graph_detekt_modules: set[str] = set()
    if graph is not None:
        graph_modules = set(graph.TASKS)
        graph_detekt_modules = set(graph.TASKS.detekt_modules())

    # What the CI files NAME. Read from the lines that are commands, not from
    # the prose: a comment that mentions `:shared:detekt` in passing is a
    # sentence about a module, not a list of the modules CI judges. Reading
    # both is how a workflow ends up "judging" a module that only a paragraph
    # mentions.
    ci_modules = ci_detekt_modules(ci_path)
    ci_script = CI_DETEKT_SCRIPT if ci_script_path is None else ci_script_path
    ci_script_modules = ci_detekt_modules(ci_script)

    if recorded and graph_detekt_modules:
        # The record and the build must name the same modules. CI judges what
        # the graph says, so the record is a claim about CI — and a claim that
        # is checked against the build rather than against another hand-written
        # list.
        for module in sorted(graph_detekt_modules - recorded_modules):
            errors.append(
                f'CI judges :{module}:detekt — it is in the module graph — and the record '
                f'does not list it; the record says what only CI judges, so a module CI judges '
                f'must be in it',
            )
        for module in sorted(recorded_modules - graph_detekt_modules):
            errors.append(
                f'the record says CI judges :{module}:detekt, and the module graph has no such '
                f'module — the record overstates what CI does',
            )

    # The modules CI judges OUTSIDE the graph — the pro build, which only
    # exists behind a flag — are named by the workflow, and the record has to
    # name them too.
    for module in sorted(outside_modules):
        if module not in ci_modules:
            errors.append(
                f'the record says CI judges :{module}:detekt outside the module graph, and the '
                f'CI workflow does not name it — the record claims a verdict nobody produces',
            )

    if graph_modules and recorded:
        for module in sorted(recorded_modules - set(graph_modules)):
            errors.append(
                f'the record lists :{module}:detekt, and the module graph has no such module — '
                f'the record names a module that does not exist',
            )
        # The other direction. The record ⊆ the graph was checked above; this
        # is the graph ⊆ the record. A module the graph knows, that the record
        # does not name, is judged by the hook and by nobody else — a module
        # the local verdict certifies and CI never sees. That asymmetry was
        # silent: nothing went red when a module was added to
        # settings.gradle.kts and forgotten in the CI workflow.
        #
        # A module with no detekt task of its own is not in this set: its
        # verdict is its test suite, and the record's `test_verdicts` section
        # says so.
        for module in sorted(set(graph_modules) - recorded_modules - outside_modules):
            if module in graph_detekt_modules:
                # CI judges it — reported above, against the record.
                continue
            errors.append(
                f'the module graph knows :{module}, and neither CI nor the record names it — '
                f'a module the hook judges and nobody else certifies is a module nobody '
                f'certifies at all; add it to CI and to the record, or say in the record '
                f'why it is not judged there',
            )

    if HOOK_DETEKT_RE.search(hook_text):
        errors.append(
            'the hook names a detekt module itself — the set of modules to judge is computed '
            'from the module graph, and a list written into the hook goes stale silently',
        )

    # The same rule for CI: a hand-written list of detekt modules in a
    # workflow or in the auto-fix script is a second copy of a fact the build
    # already states. The pro module is the exception the record declares: it
    # is not in the graph, so it has to be named by hand.
    for source, modules in (('the CI workflow', ci_modules), ('the CI auto-fix script', ci_script_modules)):
        for module in sorted(modules - outside_modules):
            errors.append(
                f'{source} names :{module}:detekt itself — the list of modules to judge is read '
                f'from the build (`changed-modules.py --detekt-tasks`), and a list written out '
                f'by hand goes stale silently',
            )

    # And the list has to actually be asked for. A CI job that judged nothing
    # would be green, and nothing else in the repository would say so.
    #
    # The question is asked of the COMMANDS: a comment that mentions
    # `--detekt-tasks` is a sentence about the graph, not a command that
    # asks it, and a file that only talks about the graph judges nothing.
    for source, path in (('the CI workflow', ci_path), ('the CI auto-fix script', ci_script)):
        if not path.is_file():
            continue
        if not any('--detekt-tasks' in line for line in command_lines(path)):
            errors.append(
                f'{source} does not ask the module graph for the detekt modules '
                f'(`changed-modules.py --detekt-tasks`) — without it the list of modules to '
                f'judge is a list somebody wrote out, and a job that judged nothing would be '
                f'green',
            )

    partiality = detekt.get('partiality', '')
    if not partiality or 'CI' not in partiality:
        errors.append(
            'the record does not state the partiality — the hook judges what the change reaches '
            'and CI judges everything, so the difference has to be written down',
        )

    facts = {
        'hook_scripts': len(declared - tree_rules) + len(tree_rules),
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
    declared, tree_rules, machinery = declared_hook_scripts()
    errors, facts = collect_errors(
        REGISTRY, HOOK, CI_WORKFLOW, graph, declared, tree_rules, None,
        machinery, PRE_PUSH_DECLARATION,
    )

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
        f'check-hook-gates: OK — the hook runs {facts["hook_scripts"]} declared scripts '
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

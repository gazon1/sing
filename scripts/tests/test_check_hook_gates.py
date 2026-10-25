"""Tests for check-hook-gates.py — the record, the hook and the CI must agree.

The gate exists because the difference between what the pre-push hook judges
and what CI judges was written nowhere: the hook judged two of the eight
detekt modules, the CI judged all of them, and a reader of the hook read
"detekt" as "the whole tree".

These tests hold the gate to its own claims — each one breaks one side of the
agreement and expects the gate to say so:

  - a module the CI judges and the record does not mention;
  - a module the record claims the CI judges but it does not;
  - a gate the hook names itself — the gates it runs are declared in one
    place, and the hook reads them from there;
  - a hook that does not read the declaration;
  - a detekt module named in the hook itself;
  - a record that does not state the partiality;
  - a delegate the hook does not call.
"""

import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = ROOT / 'scripts' / 'check-hook-gates.py'
spec = importlib.util.spec_from_file_location('check_hook_gates', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

GRAPH_MODULES = {
    'core-base', 'adr-core', 'adr-cli', 'shared',
    'desktopApp', 'androidApp', 'mcp-server', 'detekt-rules',
}


class FakeChecks(dict):
    """The module graph's checks, with the same two views it has.

    ``scripts/changed-modules.py``'s `ModuleChecks` has two: every module it
    knows, and the modules that have a detekt verdict. The gate needs both, so
    the fixture has to carry both.
    """

    def __iter__(self):
        return iter(self.detekt_modules())

    def detekt_modules(self) -> list[str]:
        return sorted(
            name for name, checks in dict.items(self)
            if any(check.endswith(':detekt') for check in checks)
        )

    def all_modules(self) -> list[str]:
        return sorted(dict.keys(self))


class FakeGraph:
    """The module graph, as the gate sees it."""

    def __init__(self, modules: set[str], detekt: set[str] | None = None) -> None:
        detekt = modules if detekt is None else detekt
        self.TASKS = FakeChecks(
            {name: [f':{name}:detekt'] for name in modules}
            | {name: [f':{name}:test'] for name in modules - detekt}
        )


GRAPH = FakeGraph(GRAPH_MODULES)

DECLARED = {
    'scripts/build-version-catalog-gate.py',
    'scripts/check-ui-wait-budgets.py',
    'scripts/ci/validate-maestro-selectors.py',
}
TREE_RULES = {'scripts/check-issue-registry.py'}
MACHINERY = {'scripts/run-gate-tests.py', 'scripts/ci-verdict.py'}
DECLARATION = 'scripts/src/sgate/gatekit/pre_push.py'.replace('"""', '')


class HookGatesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='check-hook-gates-'))
        self.addCleanup(__import__('shutil').rmtree, self.tmp, True)
        self.registry_path = self.tmp / 'pre-push.json'
        self.hook_path = self.tmp / 'pre-push'
        self.ci_path = self.tmp / 'ci.yml'
        self.ci_script_path = self.tmp / 'detekt-auto-fix.sh'
        self.registry_path.write_text(json.dumps(self._registry()), encoding='utf-8')
        self.hook_path.write_text(self._hook(), encoding='utf-8')
        self.ci_path.write_text(self._ci(), encoding='utf-8')
        self.ci_script_path.write_text(self._ci_script(), encoding='utf-8')

    @staticmethod
    def _registry() -> dict:
        return {
            'hook_scripts': {
                'declared_in': 'scripts/src/sgate/commands/_registry.py',
                'verdict_delegate': 'scripts/pre-push-verdict.py',
            },
            'detekt': {
                'judged_by_ci': [f':{module}:detekt' for module in sorted(GRAPH_MODULES)],
                'judged_by_ci_outside_the_module_graph': {},
                'partiality': 'The hook judges what the change reaches; CI judges everything.',
            },
        }

    @staticmethod
    def _hook() -> str:
        """A hook that names no gate: the gates come from the declaration.

        What it may name is the machinery it calls with logic of its own, the
        delegate that decides the detekt verdict, and the declaration the
        gates are read from.
        """
        return (
            '#!/usr/bin/env bash\n'
            'PRE_PUSH_STEPS_TABLE="$(python3 "$REPO_ROOT/'
            + DECLARATION
            + '" --table)"\n'
            'python3 "$tree/scripts/run-gate-tests.py"\n'
            'python3 "$REPO_ROOT/scripts/ci-verdict.py" "$sha"\n'
            'python3 "$REPO_ROOT/scripts/pre-push-verdict.py" --sha "$sha"\n'
        )

    @staticmethod
    def _ci() -> str:
        # CI asks the module graph for the list. A workflow that wrote the
        # list out by hand is drift, and the gate says so.
        return (
            'run: |\n'
            '  mapfile -t DETEKT_TASKS < <(python3 scripts/changed-modules.py --detekt-tasks)\n'
            '  ./gradlew "${DETEKT_TASKS[@]}"\n'
        )

    @staticmethod
    def _ci_script() -> str:
        # The auto-fix script judges the same modules, so it asks the graph
        # too. A list written out here is a second copy of a fact the build
        # already states.
        return (
            '#!/bin/bash\n'
            'mapfile -t DETEKT_TASKS < <(python3 scripts/changed-modules.py --detekt-tasks)\n'
            './gradlew "${DETEKT_TASKS[@]}" --auto-correct\n'
        )

    def _errors(self, graph=None, ci_path=None, ci_script=None) -> list[str]:
        errors, _ = mod.collect_errors(
            self.registry_path,
            self.hook_path,
            self.ci_path if ci_path is None else ci_path,
            GRAPH if graph is None else graph,
            DECLARED,
            TREE_RULES,
            self.ci_script_path if ci_script is None else ci_script,
            MACHINERY,
            DECLARATION,
        )
        return errors

    def test_a_record_that_matches_the_code_has_no_errors(self):
        self.assertEqual([], self._errors())

    def test_a_module_the_ci_judges_and_the_record_does_not_mention_is_an_error(self):
        registry = self._registry()
        registry['detekt']['judged_by_ci'].remove(':mcp-server:detekt')
        self.registry_path.write_text(json.dumps(registry), encoding='utf-8')
        errors = self._errors()
        self.assertTrue(any('mcp-server' in err for err in errors), errors)

    def test_a_module_the_record_claims_the_ci_judges_but_the_build_does_not_is_an_error(self):
        """CI judges what the build declares; the record may not claim more.

        The list CI judges is read from the build, so a module the record
        names and the build does not declare is a claim nobody produces.
        """
        registry = self._registry()
        registry['detekt']['judged_by_ci'].append(':ghostModule:detekt')
        self.registry_path.write_text(json.dumps(registry), encoding='utf-8')
        errors = self._errors()
        self.assertTrue(any('ghostModule' in err for err in errors), errors)

    def test_a_gate_the_hook_names_itself_is_an_error(self):
        """The hook names no gate: the gates are declared in one place.

        A gate written into the hook is a gate the registry does not know
        about, and the fixture that drives the hook stubs the registry's
        list — so the hook would run a gate nobody stubbed, and the failure
        would be about the stub rather than about the gate.
        """
        self.hook_path.write_text(
            self._hook() + 'python3 "$REPO_ROOT/scripts/check-something-new.py"\n', encoding='utf-8',
        )
        errors = self._errors()
        self.assertTrue(any('check-something-new.py' in err for err in errors), errors)

    def test_a_hook_that_does_not_read_the_declaration_is_an_error(self):
        """A hook that ran no gate and said OK is not a hook that passed.

        The gates come from the declaration, so the hook has to read it. A
        hook that does not runs no gate at all — and says the tree is clean.
        """
        self.hook_path.write_text(
            '#!/usr/bin/env bash\n'
            'python3 "$REPO_ROOT/scripts/pre-push-verdict.py" --sha "$sha"\n',
            encoding='utf-8',
        )
        errors = self._errors()
        self.assertTrue(any('does not read the gates' in err for err in errors), errors)

    def test_the_machinery_and_the_delegate_may_be_named(self):
        """What the hook may name is what it calls with logic of its own."""
        errors = self._errors()
        self.assertEqual([], errors)

    def test_a_detekt_module_named_in_the_hook_is_an_error(self):
        self.hook_path.write_text(
            self._hook() + '# ./gradlew :shared:detekt\n', encoding='utf-8',
        )
        errors = self._errors()
        self.assertTrue(any('names a detekt module' in err for err in errors), errors)

    def test_a_record_without_the_partiality_is_an_error(self):
        registry = self._registry()
        registry['detekt']['partiality'] = ''
        self.registry_path.write_text(json.dumps(registry), encoding='utf-8')
        errors = self._errors()
        self.assertTrue(any('partiality' in err for err in errors), errors)

    def test_a_delegate_the_hook_does_not_call_is_an_error(self):
        self.hook_path.write_text(
            '#!/usr/bin/env bash\n'
            'python3 "$REPO_ROOT/scripts/build-version-catalog-gate.py" --quiet .\n'
            'python3 "$tree/scripts/check-ui-wait-budgets.py" --root "$tree"\n',
            encoding='utf-8',
        )
        errors = self._errors()
        self.assertTrue(any('delegate' in err for err in errors), errors)

    def test_a_module_outside_the_module_graph_is_declared_as_such(self):
        registry = self._registry()
        registry['detekt']['judged_by_ci_outside_the_module_graph'] = {':pro:detekt': 'the pro build'}
        self.registry_path.write_text(json.dumps(registry), encoding='utf-8')
        # The pro build is not in the graph — it only exists behind a flag —
        # so the workflow names it, and the record says so.
        self.ci_path.write_text(
            'run: |\n'
            '  mapfile -t DETEKT_TASKS < <(python3 scripts/changed-modules.py --detekt-tasks)\n'
            '  ./gradlew "${DETEKT_TASKS[@]}" -PwithPro=true :pro:detekt\n',
            encoding='utf-8',
        )
        self.assertEqual([], self._errors())

    def test_a_module_the_graph_knows_and_neither_ci_nor_the_record_names_is_an_error(self):
        """A module judged by the hook and certified by nobody.

        The record ⊆ the graph was checked before; this is the other
        direction. A module added to `settings.gradle.kts` and forgotten in
        CI's hand-written detekt list is judged by the local verdict and by
        nobody else — and nothing went red when that happened.
        """
        errors, _ = mod.collect_errors(
            self.registry_path,
            self.hook_path,
            self.ci_path,
            FakeGraph(GRAPH_MODULES | {'aNewModule'}),
            DECLARED,
            TREE_RULES,
            self.ci_script_path,
        )
        self.assertTrue(
            any('aNewModule' in err for err in errors),
            errors,
        )

    def test_a_hand_written_detekt_list_in_the_workflow_is_an_error(self):
        """A list written out by hand is a second copy of a fact the build states.

        The list CI judges is read from the build. A workflow that writes the
        module names out itself is a list that goes stale silently — and the
        gate that was supposed to catch it was reading that very list.
        """
        self.ci_path.write_text(
            'run: ./gradlew :shared:detekt :desktopApp:detekt\n', encoding='utf-8',
        )
        errors = self._errors()
        self.assertTrue(
            [err for err in errors if 'names :shared:detekt itself' in err],
            errors,
        )

    def test_a_hand_written_detekt_list_in_the_auto_fix_script_is_an_error(self):
        self.ci_script_path.write_text(
            '#!/bin/bash\n./gradlew :shared:detekt --auto-correct\n', encoding='utf-8',
        )
        errors = self._errors()
        self.assertTrue(
            [err for err in errors if 'auto-fix script names :shared:detekt itself' in err],
            errors,
        )

    def test_a_ci_surface_that_does_not_ask_the_graph_is_an_error(self):
        """A job that judged nothing would be green, and nothing else would say so."""
        self.ci_path.write_text('run: ./gradlew koverReport\n', encoding='utf-8')
        self.ci_script_path.write_text('#!/bin/bash\n./gradlew koverReport\n', encoding='utf-8')
        errors = self._errors()
        self.assertTrue(
            [err for err in errors if 'does not ask the module graph' in err],
            errors,
        )

    def test_a_prose_mention_of_a_detekt_module_is_not_a_list(self):
        """A comment that mentions a module in passing is not a list of modules.

        The gate used to read the workflow with a plain regex, so a paragraph
        about a past measurement counted as a module CI judges.
        """
        self.ci_path.write_text(
            'run: |\n'
            '  # Measured on a pristine checkout: `:shared:detekt` fails with\n'
            '  mapfile -t DETEKT_TASKS < <(python3 scripts/changed-modules.py --detekt-tasks)\n'
            '  ./gradlew "${DETEKT_TASKS[@]}"\n',
            encoding='utf-8',
        )
        self.assertEqual([], self._errors())

    def test_a_prose_mention_of_the_graph_is_not_a_question(self):
        """A comment that mentions `--detekt-tasks` has not asked the graph.

        The question is asked of the COMMANDS. A file that only talks about
        the graph — in a paragraph, in a note — judges nothing, and a gate
        that accepted the prose would let the command go missing unnoticed.
        """
        self.ci_path.write_text(
            'run: |\n'
            '  # The list used to be written out; now it comes from\n'
            '  # `python3 scripts/changed-modules.py --detekt-tasks`.\n'
            '  ./gradlew "${DETEKT_TASKS[@]}"\n',
            encoding='utf-8',
        )
        errors = self._errors()
        self.assertTrue(
            [err for err in errors if 'does not ask the module graph' in err],
            errors,
        )

    def test_a_module_the_graph_knows_and_ci_names_is_not_that_error(self):
        """CI names it, so only the missing record entry is an error."""
        errors, _ = mod.collect_errors(
            self.registry_path,
            self.hook_path,
            self.ci_path,
            FakeGraph(GRAPH_MODULES | {'mcp-server'}),
            DECLARED,
            TREE_RULES,
            self.ci_script_path,
        )
        self.assertFalse(
            [err for err in errors if 'mcp-server' in err and 'nobody else certifies' in err],
            errors,
        )


if __name__ == '__main__':
    unittest.main()

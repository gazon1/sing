"""Tests for check-hook-gates.py — the record, the hook and the CI must agree.

The gate exists because the difference between what the pre-push hook judges
and what CI judges was written nowhere: the hook judged two of the eight
detekt modules, the CI judged all of them, and a reader of the hook read
"detekt" as "the whole tree".

These tests hold the gate to its own claims — each one breaks one side of the
agreement and expects the gate to say so:

  - a module the CI judges and the record does not mention;
  - a module the record claims the CI judges but it does not;
  - a script the hook runs and the declaration does not name;
  - a script the declaration names and the hook does not call;
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

DECLARED = {
    'scripts/build-version-catalog-gate.py',
    'scripts/check-ui-wait-budgets.py',
    'scripts/ci/validate-maestro-selectors.py',
}
TREE_RULES = {'scripts/check-issue-registry.py'}


class HookGatesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='check-hook-gates-'))
        self.addCleanup(__import__('shutil').rmtree, self.tmp, True)
        self.registry_path = self.tmp / 'pre-push.json'
        self.hook_path = self.tmp / 'pre-push'
        self.ci_path = self.tmp / 'ci.yml'
        self.registry_path.write_text(json.dumps(self._registry()), encoding='utf-8')
        self.hook_path.write_text(self._hook(), encoding='utf-8')
        self.ci_path.write_text(self._ci(), encoding='utf-8')

    @staticmethod
    def _registry() -> dict:
        return {
            'hook_scripts': {
                'declared_in': 'scripts/src/sgate/commands/_registry.py',
                'verdict_delegate': 'scripts/pre-push-verdict.py',
            },
            'detekt': {
                'judged_by_ci': [':shared:detekt', ':desktopApp:detekt'],
                'judged_by_ci_outside_the_module_graph': {},
                'partiality': 'The hook judges what the change reaches; CI judges everything.',
            },
        }

    @staticmethod
    def _hook() -> str:
        return (
            '#!/usr/bin/env bash\n'
            'python3 "$REPO_ROOT/scripts/build-version-catalog-gate.py" --quiet .\n'
            'python3 "$REPO_ROOT/scripts/ci/validate-maestro-selectors.py" Maestro/flows\n'
            'python3 "$tree/scripts/check-ui-wait-budgets.py" --root "$tree"\n'
            'python3 "$REPO_ROOT/scripts/pre-push-verdict.py" --sha "$sha"\n'
        )

    @staticmethod
    def _ci() -> str:
        return 'run: ./gradlew :shared:detekt :desktopApp:detekt\n'

    def _errors(self) -> list[str]:
        errors, _ = mod.collect_errors(
            self.registry_path,
            self.hook_path,
            self.ci_path,
            GRAPH_MODULES,
            DECLARED,
            TREE_RULES,
        )
        return errors

    def test_a_record_that_matches_the_code_has_no_errors(self):
        self.assertEqual([], self._errors())

    def test_a_module_the_ci_judges_and_the_record_does_not_mention_is_an_error(self):
        self.ci_path.write_text(
            'run: ./gradlew :shared:detekt :desktopApp:detekt :mcp-server:detekt\n', encoding='utf-8',
        )
        errors = self._errors()
        self.assertTrue(any('mcp-server' in err for err in errors), errors)

    def test_a_module_the_record_claims_the_ci_judges_but_it_does_not_is_an_error(self):
        registry = self._registry()
        registry['detekt']['judged_by_ci'].append(':androidApp:detekt')
        self.registry_path.write_text(json.dumps(registry), encoding='utf-8')
        errors = self._errors()
        self.assertTrue(any('androidApp' in err for err in errors), errors)

    def test_a_script_the_hook_runs_and_the_declaration_does_not_name_is_an_error(self):
        self.hook_path.write_text(
            self._hook() + 'python3 "$REPO_ROOT/scripts/check-something-new.py"\n', encoding='utf-8',
        )
        errors = self._errors()
        self.assertTrue(any('check-something-new.py' in err for err in errors), errors)

    def test_a_script_the_declaration_names_and_the_hook_does_not_call_is_an_error(self):
        errors, _ = mod.collect_errors(
            self.registry_path,
            self.hook_path,
            self.ci_path,
            GRAPH_MODULES,
            DECLARED | {'scripts/check-navigation-doc-sync.py'},
            TREE_RULES,
        )
        self.assertTrue(
            any('check-navigation-doc-sync.py' in err for err in errors), errors,
        )

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
        self.ci_path.write_text(
            'run: ./gradlew :shared:detekt :desktopApp:detekt :pro:detekt\n', encoding='utf-8',
        )
        self.assertEqual([], self._errors())


if __name__ == '__main__':
    unittest.main()

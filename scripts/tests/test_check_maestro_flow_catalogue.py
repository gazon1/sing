"""Tests for check-maestro-flow-catalogue.py.

The gate exists so a flow cannot disappear from the suite without anyone
noticing. A suite is a directory, and a directory shrinks quietly: delete a
flow and the run is still green — it just reports the flows that are left.

Two properties matter, and they are opposite:

  - a flow in the tree that the catalogue does not know about is a finding
    (it runs in CI, and nothing records that it exists);
  - a catalogue line with no file behind it is a finding (the coverage it
    promised is gone).

A gate that only checked one direction would pass a tree where every flow was
deleted along with its catalogue entry.
"""

import importlib.util
import pathlib
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-maestro-flow-catalogue.py'
spec = importlib.util.spec_from_file_location('check_maestro_flow_catalogue', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class CatalogueParsingTest(unittest.TestCase):
    def test_comments_and_blank_lines_are_not_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'catalogue.txt'
            path.write_text(
                '# a comment\n'
                '\n'
                'Maestro/flows/smoke/01-launch-today.yaml\n'
                '   \n'
                '# another comment\n'
                'Maestro/flows/smoke/02-menu-settings.yaml\n'
            )
            old = mod.CATALOGUE
            mod.CATALOGUE = path
            try:
                entries = mod.catalogue_entries()
            finally:
                mod.CATALOGUE = old
        self.assertEqual(
            entries,
            [
                'Maestro/flows/smoke/01-launch-today.yaml',
                'Maestro/flows/smoke/02-menu-settings.yaml',
            ],
        )

    def test_an_entry_is_the_path_as_written(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'catalogue.txt'
            path.write_text('Maestro/flows/auth/07-account-attach.yaml\n')
            old = mod.CATALOGUE
            mod.CATALOGUE = path
            try:
                entries = mod.catalogue_entries()
            finally:
                mod.CATALOGUE = old
        self.assertEqual(entries, ['Maestro/flows/auth/07-account-attach.yaml'])


class MismatchTest(unittest.TestCase):
    """Both directions are findings, and each says which way it failed."""

    def _scan(self, catalogue: list[str], tree: list[str]) -> list[dict]:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            catalogue_path = root / 'catalogue.txt'
            catalogue_path.write_text('\n'.join(catalogue) + '\n')
            flows_dir = root / 'Maestro' / 'flows'
            flows_dir.mkdir(parents=True)
            for relative in tree:
                flow = root / relative
                flow.parent.mkdir(parents=True, exist_ok=True)
                flow.write_text('appId: com.singularity.todo\n---\n')
            old_catalogue, old_flows = mod.CATALOGUE, mod.FLOWS_DIR
            mod.CATALOGUE, mod.FLOWS_DIR = catalogue_path, flows_dir
            try:
                return mod.scan()
            finally:
                mod.CATALOGUE, mod.FLOWS_DIR = old_catalogue, old_flows

    def test_a_matching_catalogue_has_no_findings(self):
        findings = self._scan(
            catalogue=['Maestro/flows/smoke/01-launch-today.yaml'],
            tree=['Maestro/flows/smoke/01-launch-today.yaml'],
        )
        self.assertEqual(findings, [])

    def test_a_flow_missing_from_the_catalogue_is_a_finding(self):
        findings = self._scan(
            catalogue=[],
            tree=['Maestro/flows/smoke/01-launch-today.yaml'],
        )
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0]['kind'], 'not in catalogue')
        self.assertEqual(findings[0]['flow'], 'Maestro/flows/smoke/01-launch-today.yaml')

    def test_a_catalogue_line_without_a_file_is_a_finding(self):
        findings = self._scan(
            catalogue=['Maestro/flows/smoke/01-launch-today.yaml'],
            tree=[],
        )
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0]['kind'], 'missing from tree')

    def test_a_flow_listed_twice_is_a_finding(self):
        findings = self._scan(
            catalogue=[
                'Maestro/flows/smoke/01-launch-today.yaml',
                'Maestro/flows/smoke/01-launch-today.yaml',
            ],
            tree=['Maestro/flows/smoke/01-launch-today.yaml'],
        )
        self.assertEqual([f['kind'] for f in findings], ['listed twice'])


class RepositoryCatalogueTest(unittest.TestCase):
    """The catalogue in this repository must match the tree in this repository."""

    def test_the_committed_catalogue_matches_the_flows(self):
        findings = mod.scan()
        self.assertEqual(
            findings,
            [],
            'the flow catalogue and the flow tree disagree; update '
            'config/docs/maestro-flows.txt in the same commit as the flow',
        )


if __name__ == '__main__':
    unittest.main()

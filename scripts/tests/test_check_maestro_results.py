"""Tests for check-maestro-results.py.

The gate exists so that a Maestro run's verdict is a number the repository
owns instead of a line in a CI log. Four properties matter, and each of them
is a way the measurement can lie:

  - a flow that produced no report is a finding, not a skip — a flow that did
    not run is a flow that quietly stopped being covered;
  - a report for a flow outside the scope is a finding — a leftover from an
    earlier run makes the measurement a measurement of the wrong run;
  - more failures than the floor allows is a finding, and fewer is not — the
    floor ratchets in one direction only;
  - the failures are grouped by cause, so the table says what the debt is
    made of and not only how much of it there is.

The tests write their own reports: a gate that is only exercised against the
reports of one particular run is a gate whose behaviour nobody knows when the
run changes shape.
"""

import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest

MODULE_PATH = (
    pathlib.Path(__file__).resolve().parent.parent / 'ci' / 'check-maestro-results.py'
)
spec = importlib.util.spec_from_file_location('check_maestro_results', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['check_maestro_results'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


REPORT_TEMPLATE = """<?xml version='1.0' encoding='UTF-8'?>
<testsuites>
  <testsuite name="Test Suite" device="Medium_Phone" tests="1" failures="{failures}" time="1.0">
    <testcase id="{stem}" name="{stem}" classname="{stem}" file="{flow}" time="1.0" status="{status}">
      {body}
    </testcase>
  </testsuite>
</testsuites>
"""


def report(flow, failure=None, status=None):
    """A JUnit XML report for one flow, as the runner writes it."""
    stem = pathlib.PurePosixPath(flow).stem
    if failure is not None:
        body = f'<failure>{failure}</failure>'
        status = status or 'ERROR'
        failures = 1
    else:
        body = ''
        status = status or 'SUCCESS'
        failures = 0
    return REPORT_TEMPLATE.format(
        stem=stem, flow=flow, body=body, status=status, failures=failures
    )


class NormalisationTest(unittest.TestCase):
    """The cause behind a message, with the run-specific parts removed."""

    def test_an_id_value_becomes_a_placeholder(self):
        self.assertEqual(
            mod.normalise_failure(
                'Assertion is false: id: task_detail_title is visible'
            ),
            'Assertion is false: id: <id> is visible',
        )

    def test_two_ids_of_the_same_shape_land_in_one_group(self):
        first = mod.normalise_failure('No visible element found: id: attachments_add_file_row')
        second = mod.normalise_failure('No visible element found: id: settings_tab_backup')
        self.assertEqual(first, second)

    def test_a_quoted_text_becomes_a_placeholder(self):
        self.assertEqual(
            mod.normalise_failure('Assertion is false: "No priority" is visible'),
            'Assertion is false: <text> is visible',
        )

    def test_a_run_id_inside_a_text_disappears_with_it(self):
        self.assertEqual(
            mod.normalise_failure(
                'Assertion is false: "\u2713 r5552136040 Child task one" is visible'
            ),
            'Assertion is false: <text> is visible',
        )

    def test_a_long_number_becomes_a_placeholder(self):
        self.assertEqual(
            mod.normalise_failure('Timed out after 30000 ms'),
            'Timed out after <n> ms',
        )

    def test_a_regex_tail_becomes_a_placeholder(self):
        self.assertEqual(
            mod.normalise_failure('Element not found: Text matching regex: High priority'),
            'Element not found: Text matching regex: <text>',
        )

    def test_visible_and_not_visible_stay_apart(self):
        """Collapsing them would hide a flow that asserted the opposite."""
        self.assertNotEqual(
            mod.normalise_failure('Assertion is false: id: a is visible'),
            mod.normalise_failure('Assertion is false: id: a is not visible'),
        )


class FloorFileTest(unittest.TestCase):
    """The floor is data, read from a file, never written by the gate."""

    def test_comments_and_blank_lines_are_not_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'floor.txt'
            path.write_text('# a comment\n\nfull 39\nsmoke 10\n')
            floors = mod.read_floor(path)
        self.assertEqual(floors, {'full': 39, 'smoke': 10})

    def test_a_malformed_line_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'floor.txt'
            path.write_text('full 39\nsmoke\n')
            with self.assertRaises(ValueError):
                mod.read_floor(path)

    def test_a_non_numeric_count_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'floor.txt'
            path.write_text('full many\n')
            with self.assertRaises(ValueError):
                mod.read_floor(path)

    def test_the_repository_floor_covers_both_scopes(self):
        """A scope with no floor is a measurement nobody can judge."""
        floors = mod.read_floor(mod.DEFAULT_FLOOR)
        for scope in mod.SCOPES:
            self.assertIn(scope, floors)


class CatalogueTest(unittest.TestCase):
    def test_comments_and_blank_lines_are_not_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'catalogue.txt'
            path.write_text(
                '# a comment\n'
                '\n'
                'Maestro/flows/smoke/01-launch-today.yaml\n'
                'Maestro/flows/tasks/create-task.yaml\n'
            )
            entries = mod.read_catalogue(path)
        self.assertEqual(
            entries,
            [
                'Maestro/flows/smoke/01-launch-today.yaml',
                'Maestro/flows/tasks/create-task.yaml',
            ],
        )


class ScopeTest(unittest.TestCase):
    """The scope is the suite the runner ran, selected the way it selects it."""

    def _tree(self, root):
        flows = {
            'Maestro/flows/smoke/01-launch-today.yaml': 'tags:\n- smoke\n---\nappId: x\n',
            'Maestro/flows/tasks/create-task.yaml': 'appId: x\n---\nlaunchApp: x\n',
            'Maestro/flows/notes/create-note.yaml': 'tags:\n- smoke\n- notes\n---\nappId: x\n',
        }
        for name, body in flows.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body)
        return list(flows)

    def test_full_is_the_whole_catalogue(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            catalogue = self._tree(root)
            selected, findings = mod.scope_flows(mod.SCOPE_FULL, catalogue, root)
        self.assertEqual(selected, catalogue)
        self.assertEqual(findings, [])

    def test_smoke_is_the_tagged_subset_not_the_directory(self):
        """The tag is the selection: 22 of the 29 smoke flows live elsewhere."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            catalogue = self._tree(root)
            selected, findings = mod.scope_flows(mod.SCOPE_SMOKE, catalogue, root)
        self.assertEqual(
            selected,
            [
                'Maestro/flows/smoke/01-launch-today.yaml',
                'Maestro/flows/notes/create-note.yaml',
            ],
        )
        self.assertEqual(findings, [])

    def test_a_flow_that_is_not_in_the_tree_is_a_finding(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            selected, findings = mod.scope_flows(
                mod.SCOPE_SMOKE, ['Maestro/flows/gone.yaml'], root
            )
        self.assertEqual(selected, [])
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].kind, 'flow file missing')


class MeasurementTest(unittest.TestCase):
    """What the gate refuses to accept."""

    CATALOGUE = [
        'Maestro/flows/smoke/01-launch-today.yaml',
        'Maestro/flows/tasks/create-task.yaml',
        'Maestro/flows/tasks/delete-task.yaml',
    ]

    def _measure(self, reports, floor=0, scope=mod.SCOPE_FULL):
        return mod.measure(scope, self.CATALOGUE, reports, floor, mod.ROOT)

    def _report(self, flow, failure=None):
        return mod.Report(
            name=pathlib.PurePosixPath(flow).stem + '.xml',
            flow=flow,
            messages=(failure,) if failure else (),
        )

    def test_a_flow_with_no_report_is_a_finding(self):
        reports = [self._report(self.CATALOGUE[0]), self._report(self.CATALOGUE[1])]
        measurement = self._measure(reports)
        kinds = [f.kind for f in measurement.findings]
        self.assertIn('flow did not report', kinds)
        self.assertEqual(measurement.reported, self.CATALOGUE[:2])

    def test_a_report_for_a_flow_outside_the_scope_is_a_finding(self):
        reports = [self._report(flow) for flow in self.CATALOGUE]
        reports.append(self._report('Maestro/flows/other/99-stray.yaml'))
        measurement = self._measure(reports)
        kinds = [f.kind for f in measurement.findings]
        self.assertIn('report outside the scope', kinds)

    def test_more_failures_than_the_floor_is_a_finding(self):
        reports = [
            self._report(self.CATALOGUE[0], 'No visible element found: id: a'),
            self._report(self.CATALOGUE[1], 'No visible element found: id: b'),
            self._report(self.CATALOGUE[2]),
        ]
        measurement = self._measure(reports, floor=1)
        kinds = [f.kind for f in measurement.findings]
        self.assertIn('over the floor', kinds)

    def test_exactly_the_floor_is_not_a_finding(self):
        reports = [
            self._report(self.CATALOGUE[0], 'No visible element found: id: a'),
            self._report(self.CATALOGUE[1]),
            self._report(self.CATALOGUE[2]),
        ]
        measurement = self._measure(reports, floor=1)
        self.assertEqual(measurement.findings, [])

    def test_fewer_failures_than_the_floor_is_not_a_finding(self):
        """The ratchet only bites when the debt grows."""
        reports = [self._report(flow) for flow in self.CATALOGUE]
        measurement = self._measure(reports, floor=3)
        self.assertEqual(measurement.findings, [])
        self.assertEqual(measurement.failed_count, 0)

    def test_failures_are_grouped_by_cause(self):
        reports = [
            self._report(self.CATALOGUE[0], 'No visible element found: id: a'),
            self._report(self.CATALOGUE[1], 'No visible element found: id: b'),
            self._report(self.CATALOGUE[2], 'Assertion is false: id: c is visible'),
        ]
        measurement = self._measure(reports, floor=3)
        self.assertEqual(len(measurement.groups), 2)
        counts = sorted(len(v) for v in measurement.groups.values())
        self.assertEqual(counts, [1, 2])

    def test_two_flows_sharing_a_report_name_are_a_finding(self):
        """One report would overwrite the other, and the run would look complete."""
        catalogue = [
            'Maestro/flows/a/01-open.yaml',
            'Maestro/flows/b/01-open.yaml',
        ]
        reports = [
            mod.Report(name='01-open.xml', flow=catalogue[0], messages=()),
        ]
        measurement = mod.measure(mod.SCOPE_FULL, catalogue, reports, 0, mod.ROOT)
        kinds = [f.kind for f in measurement.findings]
        self.assertIn('two flows, one report', kinds)


class CliTest(unittest.TestCase):
    """The command as it runs: the exit codes are the contract."""

    CATALOGUE = [
        'Maestro/flows/smoke/01-launch-today.yaml',
        'Maestro/flows/tasks/create-task.yaml',
    ]

    def _tree(self, root, reports, floor='full 1\n'):
        (root / 'catalogue.txt').write_text('\n'.join(self.CATALOGUE) + '\n')
        (root / 'floor.txt').write_text(floor)
        results = root / 'results'
        results.mkdir()
        for flow, failure in reports:
            (results / (pathlib.PurePosixPath(flow).stem + '.xml')).write_text(
                report(flow, failure)
            )
        return results

    def _run(self, *argv):
        """Run the gate and return (exit code, stdout)."""
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = mod.main(list(argv))
        return code, out.getvalue()

    def test_a_clean_run_exits_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            results = self._tree(
                root,
                [(self.CATALOGUE[0], None), (self.CATALOGUE[1], None)],
            )
            code, _ = self._run(
                '--scope', 'full',
                '--results-dir', str(results),
                '--catalogue', str(root / 'catalogue.txt'),
                '--floor', str(root / 'floor.txt'),
            )
        self.assertEqual(code, mod.CLEAN)

    def test_a_run_over_the_floor_exits_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            results = self._tree(
                root,
                [
                    (self.CATALOGUE[0], 'No visible element found: id: a'),
                    (self.CATALOGUE[1], None),
                ],
                floor='full 0\n',
            )
            code, _ = self._run(
                '--scope', 'full',
                '--results-dir', str(results),
                '--catalogue', str(root / 'catalogue.txt'),
                '--floor', str(root / 'floor.txt'),
            )
        self.assertEqual(code, mod.VALIDATION_FAILURE)

    def test_a_missing_results_directory_is_an_input_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / 'catalogue.txt').write_text('\n'.join(self.CATALOGUE) + '\n')
            (root / 'floor.txt').write_text('full 0\n')
            code, _ = self._run(
                '--scope', 'full',
                '--results-dir', str(root / 'nothing-here'),
                '--catalogue', str(root / 'catalogue.txt'),
                '--floor', str(root / 'floor.txt'),
            )
        self.assertEqual(code, mod.IO_ERROR)

    def test_a_scope_without_a_floor_is_an_input_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            results = self._tree(
                root,
                [(self.CATALOGUE[0], None), (self.CATALOGUE[1], None)],
                floor='full 0\n',
            )
            code, _ = self._run(
                '--scope', 'smoke',
                '--results-dir', str(results),
                '--catalogue', str(root / 'catalogue.txt'),
                '--floor', str(root / 'floor.txt'),
            )
        self.assertEqual(code, mod.IO_ERROR)

    def test_the_json_document_is_the_measurement(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            results = self._tree(
                root,
                [
                    (self.CATALOGUE[0], 'No visible element found: id: a'),
                    (self.CATALOGUE[1], None),
                ],
                floor='full 1\n',
            )
            code, out = self._run(
                '--scope', 'full',
                '--results-dir', str(results),
                '--catalogue', str(root / 'catalogue.txt'),
                '--floor', str(root / 'floor.txt'),
                '--json',
            )
        self.assertEqual(code, mod.CLEAN)
        payload = json.loads(out)
        self.assertEqual(payload['scope'], 'full')
        self.assertEqual(payload['failed'], 1)
        self.assertEqual(payload['groups'], {'No visible element found: id: <id>': 1})


if __name__ == '__main__':
    unittest.main()

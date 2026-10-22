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

A floor counts, and a count cannot see the debt change kind: one cause fixed,
another introduced, the same number of failing flows. So the gate also reads
the cause registry, and four more properties matter:

  - a cause the registry does not list is a finding — the debt changed kind;
  - a cause the registry marks as fixed that comes back is a finding — that
    is a regression, which a floor that only ratchets down would hide;
  - a registry cause that did not happen is reported, not failed — the debt
    shrank, and that is somebody's argument to make;
  - the run has to have happened where the floor was calibrated — a phone's
    number and an emulator's number are two different quantities.

The tests write their own reports, their own registry and their own
environment: a gate that is only exercised against the data of one particular
run is a gate whose behaviour nobody knows when the run changes shape.
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

#: The catalogue the CLI tests measure. Two flows is enough: the gate reads
#: the catalogue, not the number of flows in it.
CLI_CATALOGUE = [
    'Maestro/flows/smoke/01-launch-today.yaml',
    'Maestro/flows/tasks/create-task.yaml',
]

#: The causes the fixture registry knows. The first is what the CLI tests
#: fail on; the second is marked fixed, so its return is a regression; the
#: third is registered but never fails, so the debt it stands for shrinks.
KNOWN_CAUSES = (
    ('No visible element found: id: a', 818, 'product', 'accepted', 5),
    ('Assertion is false: id: fixed_one is visible', 864, 'test', 'fixed', 4),
    ('Assertion is false: id: gone is visible', 865, 'unclassified', 'accepted', 2),
)


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


def write_registry(path, causes=KNOWN_CAUSES, kind='emulator', api=36):
    """A registry file, written the way the repository writes one.

    The file is read back with the gate's own reader, so the tests exercise
    the parsing as well as the rules — and they never depend on the
    repository's own registry, which is data that changes.
    """
    lines = [
        'environment:',
        f'  kind: {kind}',
        f'  api: {api}',
        'causes:',
    ]
    for key, issue, owner, status, flows in causes:
        lines.append(f'- key: {json.dumps(key)}')
        lines.append(f'  issue: {issue}')
        lines.append(f'  owner: {owner}')
        lines.append(f'  status: {status}')
        lines.append(f'  flows: {flows}')
    path.write_text('\n'.join(lines) + '\n')
    return mod.read_registry(path)


def build_run(
    root,
    reports,
    floor='full 5\n',
    causes=KNOWN_CAUSES,
    environment=None,
    kind='emulator',
    api=36,
):
    """Every input a measurement needs: catalogue, floor, registry, reports.

    `environment` is the run's environment file; `None` means the run did not
    write one, which is itself a finding.
    """
    catalogue = root / 'catalogue.txt'
    catalogue.write_text('\n'.join(CLI_CATALOGUE) + '\n')
    floor_file = root / 'floor.txt'
    floor_file.write_text(floor)
    debt = write_registry(root / 'debt.yaml', causes, kind=kind, api=api)
    results = root / 'results'
    results.mkdir()
    for flow, failure in reports:
        (results / (pathlib.PurePosixPath(flow).stem + '.xml')).write_text(
            report(flow, failure)
        )
    if environment is not None:
        (results / 'environment.json').write_text(json.dumps(environment))
    return {
        'catalogue': catalogue,
        'floor': floor_file,
        'debt': debt,
        'results': results,
    }


#: The environment the fixture registry is calibrated for.
CALIBRATED_ENVIRONMENT = {'kind': 'emulator', 'api': 36, 'device': 'sdk_gphone64_x86_64'}


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
                'Assertion is false: "✓ r5552136040 Child task one" is visible'
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


class CauseKeyTest(unittest.TestCase):
    """The registry key: the message, with only the numbers taken out.

    The key keeps the element id and the expected text — the id is what says
    which cause it is — and takes out the run-specific numbers, so the same
    cause produces the same key in every run.
    """

    def test_the_numbers_go_and_the_id_stays(self):
        self.assertEqual(
            mod.cause_key(
                'Assertion is false: id: task_item_r5530242440_taskwithdesc2026 is visible'
            ),
            'Assertion is false: id: task_item_r<n>_taskwithdesc<n> is visible',
        )

    def test_a_run_id_inside_a_text_goes_too(self):
        self.assertEqual(
            mod.cause_key('Assertion is false: "r5549817520 Buy milk" is visible'),
            'Assertion is false: "r<n> Buy milk" is visible',
        )

    def test_two_digits_are_content_and_stay(self):
        self.assertEqual(
            mod.cause_key('Assertion is false: id: task_item_savedalpha01 is visible'),
            'Assertion is false: id: task_item_savedalpha01 is visible',
        )

    def test_the_whitespace_is_collapsed(self):
        self.assertEqual(
            mod.cause_key('No visible element found:  id:   a\n'),
            'No visible element found: id: a',
        )

    def test_two_causes_of_one_shape_stay_apart(self):
        """The coarse group key cannot tell them apart; the cause key can."""
        self.assertNotEqual(
            mod.cause_key('No visible element found: id: attachments_add_file_row'),
            mod.cause_key('No visible element found: id: settings_tab_backup'),
        )

    def test_a_stack_trace_does_not_come_into_the_key(self):
        """The trace moves with the code; the cause does not.

        A device-level failure carries its whole stack, and the line numbers
        in it change with every release. With the trace in the key, the same
        cause would be a *new* cause in every run, and the registry could
        never learn it.
        """
        message = (
            "maestro.android.DeviceServerDiedException: Device server died "
            "during 'deviceInfo' on 0O65321F32204DD5 (1200ms since last byte, "
            "connection age 3400ms): StatusRuntimeException: UNAVAILABLE\n"
            "  at maestro.android.AndroidDeviceConnection.onGrpcDeath(AndroidDeviceConnection.kt:118)\n"
            "  at maestro.Maestro.cachedDeviceInfo_delegate$lambda$0(Maestro.kt:56)\n"
        )
        self.assertEqual(
            mod.cause_key(message),
            "maestro.android.DeviceServerDiedException: Device server died "
            "during 'deviceInfo' on 0O<n>F<n>DD5 (<n>ms since last byte, "
            "connection age <n>ms): StatusRuntimeException: UNAVAILABLE",
        )

    def test_two_traces_of_one_cause_have_one_key(self):
        """The same cause, two versions of the code, one key."""
        head = "maestro.android.DeviceServerDiedException: Device server died"
        self.assertEqual(
            mod.cause_key(head + "\n  at A.kt:118\n"),
            mod.cause_key(head + "\n  at A.kt:124\n"),
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


class RegistryFileTest(unittest.TestCase):
    """The registry is data: the causes the repository has accepted."""

    def test_the_causes_are_read_with_their_owners(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'debt.yaml'
            registry = write_registry(path)
        cause = registry.causes['No visible element found: id: a']
        self.assertEqual(cause.issue, 818)
        self.assertEqual(cause.owner, 'product')
        self.assertEqual(cause.status, 'accepted')
        self.assertEqual(cause.flows, 5)
        self.assertFalse(cause.fixed)

    def test_the_environment_block_is_read(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'debt.yaml'
            registry = write_registry(path, kind='device', api=34)
        self.assertEqual(registry.environment['kind'], 'device')
        self.assertEqual(registry.environment['api'], 34)

    def test_a_cause_marked_fixed_says_so(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'debt.yaml'
            registry = write_registry(path)
        cause = registry.causes['Assertion is false: id: fixed_one is visible']
        self.assertTrue(cause.fixed)

    def test_a_registry_that_is_not_a_mapping_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'debt.yaml'
            path.write_text('- just\n- a list\n')
            with self.assertRaises(ValueError):
                mod.read_registry(path)

    def test_a_cause_without_a_key_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'debt.yaml'
            path.write_text('causes:\n- issue: 1\n')
            with self.assertRaises(ValueError):
                mod.read_registry(path)


class MeasurementTest(unittest.TestCase):
    """What the gate refuses to accept."""

    CATALOGUE = [
        'Maestro/flows/smoke/01-launch-today.yaml',
        'Maestro/flows/tasks/create-task.yaml',
        'Maestro/flows/tasks/delete-task.yaml',
    ]

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = pathlib.Path(tmp.name)
        # The registry knows every cause these tests fail on, so that the
        # findings below are the ones the test is about.
        self.registry = write_registry(
            root / 'debt.yaml',
            causes=(
                ('No visible element found: id: a', 818, 'product', 'accepted', 5),
                ('No visible element found: id: b', 819, 'product', 'accepted', 5),
                ('Assertion is false: id: c is visible', 820, 'product', 'accepted', 5),
            ),
        )

    def _measure(self, reports, floor=0, scope=mod.SCOPE_FULL):
        return mod.measure(
            scope, self.CATALOGUE, reports, floor, mod.ROOT, self.registry
        )

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
        measurement = mod.measure(
            mod.SCOPE_FULL, catalogue, reports, 0, mod.ROOT, self.registry
        )
        kinds = [f.kind for f in measurement.findings]
        self.assertIn('two flows, one report', kinds)


class CauseRuleTest(unittest.TestCase):
    """The rules a floor cannot carry: the shape of the debt, not its size."""

    CATALOGUE = [
        'Maestro/flows/a/01-open.yaml',
        'Maestro/flows/b/02-open.yaml',
    ]

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = pathlib.Path(tmp.name)
        self.registry = write_registry(root / 'debt.yaml')

    def _measure(self, reports, floor=5):
        return mod.measure(
            mod.SCOPE_FULL, self.CATALOGUE, reports, floor, mod.ROOT, self.registry
        )

    def _report(self, flow, failure=None):
        return mod.Report(
            name=pathlib.PurePosixPath(flow).stem + '.xml',
            flow=flow,
            messages=(failure,) if failure else (),
        )

    def test_a_cause_the_registry_does_not_know_is_a_finding(self):
        """The debt changed kind, and a count of failing flows cannot see that."""
        reports = [
            self._report(self.CATALOGUE[0], 'No visible element found: id: brand_new'),
            self._report(self.CATALOGUE[1]),
        ]
        measurement = self._measure(reports)
        kinds = [f.kind for f in measurement.findings]
        self.assertIn('cause not in the registry', kinds)
        detail = next(
            f.detail
            for f in measurement.findings
            if f.kind == 'cause not in the registry'
        )
        self.assertIn('No visible element found: id: brand_new', detail)
        self.assertIn('changing kind', detail)
        self.assertEqual(
            measurement.unknown_causes, ['No visible element found: id: brand_new']
        )

    def test_a_cause_the_registry_does_not_know_is_a_finding_even_within_the_floor(self):
        """The floor is not the whole gate: the debt can change kind and not grow."""
        reports = [
            self._report(self.CATALOGUE[0], 'No visible element found: id: brand_new'),
            self._report(self.CATALOGUE[1]),
        ]
        measurement = self._measure(reports, floor=5)
        kinds = [f.kind for f in measurement.findings]
        self.assertNotIn('over the floor', kinds)
        self.assertIn('cause not in the registry', kinds)

    def test_a_fixed_cause_that_comes_back_is_a_finding(self):
        """A regression is the one thing a floor that only ratchets down hides."""
        reports = [
            self._report(
                self.CATALOGUE[0], 'Assertion is false: id: fixed_one is visible'
            ),
            self._report(self.CATALOGUE[1]),
        ]
        measurement = self._measure(reports)
        kinds = [f.kind for f in measurement.findings]
        self.assertIn('fixed cause came back', kinds)
        detail = next(
            f.detail for f in measurement.findings if f.kind == 'fixed cause came back'
        )
        self.assertIn('regression', detail)
        self.assertEqual(
            measurement.regressed_causes,
            ['Assertion is false: id: fixed_one is visible'],
        )

    def test_a_registry_cause_that_did_not_happen_is_reported_not_failed(self):
        """The debt shrank, and a shrinking debt is somebody's argument to make."""
        reports = [self._report(flow) for flow in self.CATALOGUE]
        measurement = self._measure(reports)
        self.assertEqual(measurement.findings, [])
        absent = [cause.key for cause in measurement.absent_causes]
        self.assertIn('Assertion is false: id: gone is visible', absent)

    def test_the_absent_causes_are_printed_with_the_flows_they_took_down(self):
        """Lowering the floor has to be visible, not a forgotten step."""
        reports = [self._report(flow) for flow in self.CATALOGUE]
        measurement = self._measure(reports)
        text = mod.format_measurement(measurement, self.registry)
        self.assertIn('registry causes that did not happen', text)
        self.assertIn('Assertion is false: id: gone is visible', text)
        self.assertIn('#865', text)

    def test_a_fixed_cause_that_stayed_away_is_not_reported_as_shrinking_debt(self):
        """A fixed cause is not debt, so its absence is not an argument."""
        reports = [self._report(flow) for flow in self.CATALOGUE]
        measurement = self._measure(reports)
        absent = [cause.key for cause in measurement.absent_causes]
        self.assertNotIn('Assertion is false: id: fixed_one is visible', absent)

    def test_a_cause_that_happened_is_tabulated_with_its_owner(self):
        reports = [
            self._report(self.CATALOGUE[0], 'No visible element found: id: a'),
            self._report(self.CATALOGUE[1]),
        ]
        measurement = self._measure(reports)
        text = mod.format_measurement(measurement, self.registry)
        self.assertIn('No visible element found: id: a', text)
        self.assertIn('#818', text)
        self.assertIn('product', text)
        self.assertIn('accepted', text)


class EnvironmentTest(unittest.TestCase):
    """The floor belongs to one environment; a run elsewhere is not comparable."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = pathlib.Path(tmp.name)

    def _run(self, *argv):
        out = io.StringIO()
        err = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = mod.main(list(argv))
        return code, out.getvalue(), err.getvalue()

    def _gate(self, fixture, *extra):
        return self._run(
            '--scope', 'full',
            '--results-dir', str(fixture['results']),
            '--catalogue', str(fixture['catalogue']),
            '--floor', str(fixture['floor']),
            '--debt', str(fixture['debt'].path),
            *extra,
        )

    def test_a_run_that_says_where_it_happened_passes(self):
        fixture = build_run(
            self.root,
            [(CLI_CATALOGUE[0], None), (CLI_CATALOGUE[1], None)],
            environment=CALIBRATED_ENVIRONMENT,
        )
        code, _, err = self._gate(fixture)
        self.assertEqual(code, mod.CLEAN, err)

    def test_a_missing_environment_is_a_finding(self):
        """A measurement that does not say where it ran cannot be compared."""
        fixture = build_run(
            self.root,
            [(CLI_CATALOGUE[0], None), (CLI_CATALOGUE[1], None)],
            environment=None,
        )
        code, _, err = self._gate(fixture)
        self.assertEqual(code, mod.VALIDATION_FAILURE)
        self.assertIn('environment missing', err)
        self.assertIn('where it happened', err)

    def test_a_run_on_another_kind_of_device_is_a_finding(self):
        """A phone's number and an emulator's floor are two different quantities."""
        fixture = build_run(
            self.root,
            [(CLI_CATALOGUE[0], None), (CLI_CATALOGUE[1], None)],
            environment={'kind': 'device', 'api': 36, 'device': 'pixel-7'},
        )
        code, _, err = self._gate(fixture)
        self.assertEqual(code, mod.VALIDATION_FAILURE)
        self.assertIn('environment mismatch', err)
        self.assertIn('device', err)

    def test_a_run_on_another_api_is_a_finding(self):
        fixture = build_run(
            self.root,
            [(CLI_CATALOGUE[0], None), (CLI_CATALOGUE[1], None)],
            environment={'kind': 'emulator', 'api': 34, 'device': 'sdk_gphone64_x86_64'},
        )
        code, _, err = self._gate(fixture)
        self.assertEqual(code, mod.VALIDATION_FAILURE)
        self.assertIn('environment mismatch', err)
        self.assertIn('API', err)

    def test_the_environment_file_can_be_pointed_somewhere_else(self):
        """The path is overridable, so a run can put it wherever it likes."""
        fixture = build_run(
            self.root,
            [(CLI_CATALOGUE[0], None), (CLI_CATALOGUE[1], None)],
            environment=None,
        )
        elsewhere = self.root / 'elsewhere.json'
        elsewhere.write_text(json.dumps(CALIBRATED_ENVIRONMENT))
        code, _, err = self._gate(fixture, '--environment', str(elsewhere))
        self.assertEqual(code, mod.CLEAN, err)

    def test_an_unreadable_environment_is_a_finding(self):
        fixture = build_run(
            self.root,
            [(CLI_CATALOGUE[0], None), (CLI_CATALOGUE[1], None)],
            environment=None,
        )
        broken = self.root / 'broken.json'
        broken.write_text('{not json')
        code, _, err = self._gate(fixture, '--environment', str(broken))
        self.assertEqual(code, mod.VALIDATION_FAILURE)
        self.assertIn('environment unreadable', err)


class CliTest(unittest.TestCase):
    """The command as it runs: the exit codes are the contract."""

    def _tree(self, root, reports, floor='full 1\n', environment=None):
        return build_run(
            root,
            reports,
            floor=floor,
            environment=environment
            if environment is not None
            else CALIBRATED_ENVIRONMENT,
        )

    def _run(self, *argv):
        """Run the gate and return (exit code, stdout, stderr)."""
        out = io.StringIO()
        err = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = mod.main(list(argv))
        return code, out.getvalue(), err.getvalue()

    def _gate(self, fixture, *extra):
        return self._run(
            '--scope', 'full',
            '--results-dir', str(fixture['results']),
            '--catalogue', str(fixture['catalogue']),
            '--floor', str(fixture['floor']),
            '--debt', str(fixture['debt'].path),
            *extra,
        )

    def test_a_clean_run_exits_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            fixture = self._tree(
                root,
                [(CLI_CATALOGUE[0], None), (CLI_CATALOGUE[1], None)],
            )
            code, _, err = self._gate(fixture)
        self.assertEqual(code, mod.CLEAN, err)

    def test_a_run_over_the_floor_exits_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            fixture = self._tree(
                root,
                [
                    (CLI_CATALOGUE[0], 'No visible element found: id: a'),
                    (CLI_CATALOGUE[1], None),
                ],
                floor='full 0\n',
            )
            code, _, err = self._gate(fixture)
        self.assertEqual(code, mod.VALIDATION_FAILURE)
        self.assertIn('over the floor', err)

    def test_a_missing_results_directory_is_an_input_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / 'catalogue.txt').write_text('\n'.join(CLI_CATALOGUE) + '\n')
            (root / 'floor.txt').write_text('full 0\n')
            code, _, _ = self._run(
                '--scope', 'full',
                '--results-dir', str(root / 'nothing-here'),
                '--catalogue', str(root / 'catalogue.txt'),
                '--floor', str(root / 'floor.txt'),
            )
        self.assertEqual(code, mod.IO_ERROR)

    def test_a_scope_without_a_floor_is_an_input_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            fixture = self._tree(
                root,
                [(CLI_CATALOGUE[0], None), (CLI_CATALOGUE[1], None)],
                floor='full 0\n',
            )
            code, _, _ = self._run(
                '--scope', 'smoke',
                '--results-dir', str(fixture['results']),
                '--catalogue', str(fixture['catalogue']),
                '--floor', str(fixture['floor']),
                '--debt', str(fixture['debt'].path),
            )
        self.assertEqual(code, mod.IO_ERROR)

    def test_a_missing_registry_is_an_input_error(self):
        """The registry is one of the gate's inputs; without it there is nothing to compare."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            fixture = self._tree(
                root,
                [(CLI_CATALOGUE[0], None), (CLI_CATALOGUE[1], None)],
            )
            code, _, _ = self._run(
                '--scope', 'full',
                '--results-dir', str(fixture['results']),
                '--catalogue', str(fixture['catalogue']),
                '--floor', str(fixture['floor']),
                '--debt', str(root / 'no-such-registry.yaml'),
            )
        self.assertEqual(code, mod.IO_ERROR)

    def test_the_json_document_is_the_measurement(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            fixture = self._tree(
                root,
                [
                    (CLI_CATALOGUE[0], 'No visible element found: id: a'),
                    (CLI_CATALOGUE[1], None),
                ],
                floor='full 1\n',
            )
            code, out, _ = self._gate(fixture, '--json')
        self.assertEqual(code, mod.CLEAN)
        payload = json.loads(out)
        self.assertEqual(payload['scope'], 'full')
        self.assertEqual(payload['failed'], 1)
        self.assertEqual(payload['groups'], {'No visible element found: id: <id>': 1})

    def test_the_json_document_carries_the_causes_and_the_cross_checks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            fixture = self._tree(
                root,
                [
                    (CLI_CATALOGUE[0], 'No visible element found: id: a'),
                    (CLI_CATALOGUE[1], None),
                ],
                floor='full 1\n',
            )
            code, out, _ = self._gate(fixture, '--json')
        self.assertEqual(code, mod.CLEAN)
        payload = json.loads(out)
        self.assertEqual(
            payload['causes'],
            [
                {
                    'key': 'No visible element found: id: a',
                    'flows': 1,
                    'issue': 818,
                    'owner': 'product',
                    'status': 'accepted',
                    'in_registry': True,
                }
            ],
        )
        self.assertEqual(payload['registry']['unknown_causes'], [])
        self.assertEqual(payload['registry']['regressed_causes'], [])
        self.assertIn(
            'Assertion is false: id: gone is visible',
            [entry['key'] for entry in payload['registry']['did_not_happen']],
        )
        self.assertEqual(payload['environment']['calibrated']['kind'], 'emulator')
        self.assertEqual(payload['environment']['run']['api'], 36)
        self.assertTrue(payload['environment']['matches'])

    def test_a_new_cause_exits_one_and_names_itself(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            fixture = self._tree(
                root,
                [
                    (CLI_CATALOGUE[0], 'No visible element found: id: brand_new'),
                    (CLI_CATALOGUE[1], None),
                ],
                floor='full 5\n',
            )
            code, _, err = self._gate(fixture)
        self.assertEqual(code, mod.VALIDATION_FAILURE)
        self.assertIn('cause not in the registry', err)
        self.assertIn('No visible element found: id: brand_new', err)

    def test_a_fixed_cause_that_comes_back_exits_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            fixture = self._tree(
                root,
                [
                    (
                        CLI_CATALOGUE[0],
                        'Assertion is false: id: fixed_one is visible',
                    ),
                    (CLI_CATALOGUE[1], None),
                ],
                floor='full 5\n',
            )
            code, _, err = self._gate(fixture)
        self.assertEqual(code, mod.VALIDATION_FAILURE)
        self.assertIn('fixed cause came back', err)

    def test_a_cause_that_did_not_happen_is_reported_and_does_not_fail_the_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            fixture = self._tree(
                root,
                [(CLI_CATALOGUE[0], None), (CLI_CATALOGUE[1], None)],
                floor='full 5\n',
            )
            code, out, err = self._gate(fixture)
        self.assertEqual(code, mod.CLEAN, err)
        self.assertIn('registry causes that did not happen', out)


if __name__ == '__main__':
    unittest.main()

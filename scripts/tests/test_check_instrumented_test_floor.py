"""Tests for check-instrumented-test-floor.py.

The gate holds the instrumented suite to a recorded floor: the number of test
classes and tests that must run, and the number that must not be skipped.

Five properties matter, and each of them is a way the gate could report green
while the defect it exists for is still there:

  - a run with fewer classes than the floor is a finding (a deleted class);
  - a run with fewer tests than the floor is a finding (a deleted test);
  - a run with MORE classes or tests than the floor is a finding too — the floor
    is behind the suite and describes a tier that no longer exists. A floor
    that only fails downwards goes stale the moment a test is added, and it
    stays stale because nothing tells anyone it is stale;
  - a skipped test is a finding (a test that did not run on a device);
  - no report at all is a finding (a tier that did not run is not a tier that
    ran) — a gate that treats a missing report as "nothing to say" is the same
    gate that lets the tier disappear.

And the floor must be raisable on purpose: `--update-floor` writes it from a
real run, refuses a drop, and leaves the reasoning in the file alone. A floor
that regenerates itself stops being a floor.

The tests build a miniature report tree and point the module at it, so nothing
here depends on the repository's own numbers — except the one test that
asserts the committed floor and the committed fixture agree.
"""

import contextlib
import importlib.util
import io
import pathlib
import tempfile
import unittest

import click

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-instrumented-test-floor.py'
spec = importlib.util.spec_from_file_location('check_instrumented_test_floor', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

FIXTURE_DIR = pathlib.Path(__file__).resolve().parent / 'fixtures' / 'instrumented-report'


def run_cli(*argv) -> tuple[int, str]:
    """Run the gate and capture what it printed, so a failing test says why."""
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        rc = mod.main(list(argv))
    return rc, out.getvalue()


FLOOR = """\
# a comment
classes 5
tests 19
skipped 0
"""

#: A floor file in the shape the committed one has: prose around a generated
#: block. The block is the only part `--update-floor` may rewrite.
FLOOR_WITH_BLOCK = (
    "# a comment that must survive\n"
    "# GENERATED:BEGIN — rewritten by --update-floor; do not hand-edit\n"
    "classes 2\n"
    "tests 4\n"
    "skipped 0\n"
    "# GENERATED:END\n"
)


def report_xml(cases: list[tuple[str, str, bool]]) -> str:
    """A JUnit XML with the given (class, name, skipped) cases."""
    body = []
    for class_name, case_name, skipped in cases:
        if skipped:
            body.append(
                f'<testcase classname="{class_name}" name="{case_name}"><skipped/></testcase>'
            )
        else:
            body.append(f'<testcase classname="{class_name}" name="{case_name}"/>')
    return '<testsuite name="suite">' + ''.join(body) + '</testsuite>'


class FloorParsingTest(unittest.TestCase):
    def _read(self, text):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'floor.txt'
            path.write_text(text, encoding='utf-8')
            return mod.read_floor(path)

    def test_comments_and_blank_lines_are_not_floors(self):
        self.assertEqual(
            {'classes': 5, 'tests': 19, 'skipped': 0},
            self._read(FLOOR),
        )

    def test_a_line_that_is_not_a_floor_is_an_error(self):
        with self.assertRaises(click.ClickException):
            self._read('classes = 5\ntests 19\nskipped 0\n')

    def test_a_missing_floor_is_an_error(self):
        with self.assertRaises(click.ClickException):
            self._read('classes 5\ntests 19\n')

    def test_a_floor_listed_twice_is_an_error(self):
        with self.assertRaises(click.ClickException):
            self._read('classes 5\nclasses 6\ntests 19\nskipped 0\n')


class ScanTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.report_dir = pathlib.Path(self._tmp.name) / 'connected'
        self.report_dir.mkdir(parents=True)
        self.floor = {'classes': 2, 'tests': 4, 'skipped': 0}

    def tearDown(self):
        self._tmp.cleanup()

    def _write_report(self, cases, name='TEST-device.xml'):
        (self.report_dir / name).write_text(report_xml(cases), encoding='utf-8')

    def test_a_run_that_meets_the_floor_has_no_findings(self):
        self._write_report([
            ('com.example.A', 'a1', False),
            ('com.example.A', 'a2', False),
            ('com.example.B', 'b1', False),
            ('com.example.B', 'b2', False),
        ])
        self.assertEqual([], mod.scan(self.report_dir, self.floor))

    def test_a_missing_class_is_a_finding(self):
        self._write_report([('com.example.A', 'a1', False)])
        findings = mod.scan(self.report_dir, self.floor)
        self.assertEqual(2, len(findings))
        kinds = {finding['kind'] for finding in findings}
        self.assertEqual({'classes below the floor', 'tests below the floor'}, kinds)

    def test_a_skipped_test_is_a_finding(self):
        self._write_report([
            ('com.example.A', 'a1', False),
            ('com.example.A', 'a2', True),
            ('com.example.B', 'b1', False),
            ('com.example.B', 'b2', False),
        ])
        findings = mod.scan(self.report_dir, self.floor)
        self.assertEqual(1, len(findings))
        self.assertEqual('tests skipped', findings[0]['kind'])
        self.assertIn('com.example.A.a2', findings[0]['detail'])

    def test_no_report_at_all_is_a_finding(self):
        findings = mod.scan(self.report_dir, self.floor)
        self.assertEqual(1, len(findings))
        self.assertEqual('no report', findings[0]['kind'])

    def test_a_missing_report_directory_is_a_finding(self):
        findings = mod.scan(pathlib.Path(self._tmp.name) / 'nowhere', self.floor)
        self.assertEqual(1, len(findings))
        self.assertEqual('no report', findings[0]['kind'])

    def test_the_measured_counts_are_the_reported_ones(self):
        self._write_report([
            ('com.example.A', 'a1', False),
            ('com.example.B', 'b1', True),
        ])
        measured = mod.measure(self.report_dir)
        self.assertEqual(2, measured['classes'])
        self.assertEqual(2, measured['tests'])
        self.assertEqual(1, measured['skipped'])


class RatchetTest(unittest.TestCase):
    """A floor that only fails downwards goes stale the moment a test is added.

    This is the defect the ratchet exists for: the floor sat at five classes
    and nineteen tests while the suite had grown to six and twenty-one, the
    gate compared 21 against 19, found no drop, and stayed green — so the file
    that describes the tier went on describing a tier that no longer existed.
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.report_dir = pathlib.Path(self._tmp.name) / 'connected'
        self.report_dir.mkdir(parents=True)
        self.floor = {'classes': 2, 'tests': 4, 'skipped': 0}
        (self.report_dir / 'TEST-device.xml').write_text(
            report_xml([
                ('com.example.A', 'a1', False),
                ('com.example.A', 'a2', False),
                ('com.example.B', 'b1', False),
                ('com.example.B', 'b2', False),
                ('com.example.C', 'c1', False),
            ]),
            encoding='utf-8',
        )

    def tearDown(self):
        self._tmp.cleanup()

    def test_a_run_above_the_floor_is_a_finding(self):
        findings = mod.scan(self.report_dir, self.floor)
        kinds = {finding['kind'] for finding in findings}
        self.assertEqual({'classes above the floor', 'tests above the floor'}, kinds)

    def test_the_finding_says_the_floor_is_behind_the_suite(self):
        findings = mod.scan(self.report_dir, self.floor)
        detail = ' '.join(finding['detail'] for finding in findings)
        self.assertIn('behind the suite', detail)
        self.assertIn('--update-floor', detail)

    def test_a_floor_at_the_measured_count_is_clean(self):
        floor = {'classes': 3, 'tests': 5, 'skipped': 0}
        self.assertEqual([], mod.scan(self.report_dir, floor))

    def test_the_ratchet_does_not_disturb_the_skipped_ceiling(self):
        floor = {'classes': 3, 'tests': 5, 'skipped': 0}
        findings = mod.scan(self.report_dir, floor)
        self.assertEqual([], findings)


class UpdateFloorTest(unittest.TestCase):
    """Raising the floor is a deliberate act, and it is not a silent rewrite.

    The write is modelled on `scripts/check-test-runs.py --update-baseline`,
    because it answers the same question: a drop is refused (a drop means the
    tier lost coverage — investigate, do not regenerate), a rise is written but
    warned about, and the prose around the number is never touched.
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.report_dir = self.root / 'connected'
        self.report_dir.mkdir(parents=True)
        self.floor_path = self.root / 'floor.txt'
        self.floor_path.write_text(FLOOR_WITH_BLOCK, encoding='utf-8')

    def tearDown(self):
        self._tmp.cleanup()

    def _write_report(self, cases):
        (self.report_dir / 'TEST-device.xml').write_text(report_xml(cases), encoding='utf-8')

    def _run(self, *extra):
        rc, _ = run_cli(
            '--report-dir', str(self.report_dir),
            '--floor', str(self.floor_path),
            *extra,
        )
        return rc

    def _floor(self):
        return mod.read_floor(self.floor_path)

    def test_a_rise_is_written(self):
        self._write_report([
            ('com.example.A', 'a1', False),
            ('com.example.A', 'a2', False),
            ('com.example.B', 'b1', False),
            ('com.example.B', 'b2', False),
            ('com.example.C', 'c1', False),
        ])
        self.assertEqual(0, self._run('--update-floor'))
        self.assertEqual({'classes': 3, 'tests': 5, 'skipped': 0}, self._floor())

    def test_the_prose_around_the_block_survives(self):
        self._write_report([
            ('com.example.A', 'a1', False),
            ('com.example.A', 'a2', False),
            ('com.example.B', 'b1', False),
            ('com.example.B', 'b2', False),
            ('com.example.C', 'c1', False),
        ])
        self._run('--update-floor')
        self.assertIn('a comment that must survive', self.floor_path.read_text(encoding='utf-8'))

    def test_a_drop_is_refused_and_the_floor_is_untouched(self):
        self._write_report([('com.example.A', 'a1', False)])
        before = self.floor_path.read_text(encoding='utf-8')
        self.assertEqual(1, self._run('--update-floor'))
        self.assertEqual(before, self.floor_path.read_text(encoding='utf-8'))

    def test_allow_drop_permits_a_deliberate_drop(self):
        self._write_report([('com.example.A', 'a1', False)])
        self.assertEqual(0, self._run('--update-floor', '--allow-drop'))
        self.assertEqual({'classes': 1, 'tests': 1, 'skipped': 0}, self._floor())

    def test_a_run_with_skipped_tests_is_refused(self):
        self._write_report([
            ('com.example.A', 'a1', False),
            ('com.example.A', 'a2', True),
            ('com.example.B', 'b1', False),
            ('com.example.B', 'b2', False),
            ('com.example.C', 'c1', False),
        ])
        before = self.floor_path.read_text(encoding='utf-8')
        self.assertEqual(1, self._run('--update-floor'))
        self.assertEqual(before, self.floor_path.read_text(encoding='utf-8'))

    def test_a_run_with_no_report_is_refused(self):
        before = self.floor_path.read_text(encoding='utf-8')
        self.assertEqual(1, self._run('--update-floor'))
        self.assertEqual(before, self.floor_path.read_text(encoding='utf-8'))

    def test_the_skipped_ceiling_is_carried_over_not_measured(self):
        """The ceiling is a policy value; a writer that measured it would raise
        it every time a run skipped something."""
        self._write_report([
            ('com.example.A', 'a1', False),
            ('com.example.A', 'a2', False),
            ('com.example.B', 'b1', False),
            ('com.example.B', 'b2', False),
            ('com.example.C', 'c1', False),
        ])
        self._run('--update-floor')
        self.assertEqual(0, self._floor()['skipped'])

    def test_a_floor_file_without_the_block_gets_one(self):
        path = self.root / 'legacy.txt'
        path.write_text('# legacy header\nclasses 2\ntests 4\nskipped 0\n', encoding='utf-8')
        self._write_report([
            ('com.example.A', 'a1', False),
            ('com.example.A', 'a2', False),
            ('com.example.B', 'b1', False),
            ('com.example.B', 'b2', False),
            ('com.example.C', 'c1', False),
        ])
        rc, _ = run_cli(
            '--report-dir', str(self.report_dir),
            '--floor', str(path),
            '--update-floor',
        )
        self.assertEqual(0, rc)
        text = path.read_text(encoding='utf-8')
        self.assertIn('legacy header', text)
        self.assertIn('classes 3', text)
        self.assertIn('tests 5', text)


class GateExitCodeTest(unittest.TestCase):
    """The CLI contract: 0 clean, 1 findings, 2 unreadable floor."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.report_dir = self.root / 'connected'
        self.report_dir.mkdir(parents=True)
        self.floor_path = self.root / 'floor.txt'
        self.floor_path.write_text(FLOOR_WITH_BLOCK, encoding='utf-8')

    def tearDown(self):
        self._tmp.cleanup()

    def _run(self, *extra):
        rc, _ = run_cli(
            '--report-dir', str(self.report_dir),
            '--floor', str(self.floor_path),
            *extra,
        )
        return rc

    def test_a_clean_run_exits_zero(self):
        (self.report_dir / 'TEST-device.xml').write_text(
            report_xml([
                ('com.example.A', 'a1', False),
                ('com.example.A', 'a2', False),
                ('com.example.B', 'b1', False),
                ('com.example.B', 'b2', False),
            ]),
            encoding='utf-8',
        )
        self.assertEqual(0, self._run())

    def test_a_ratchet_finding_exits_one(self):
        (self.report_dir / 'TEST-device.xml').write_text(
            report_xml([('com.example.A', 'a1', False)]),
            encoding='utf-8',
        )
        self.assertEqual(1, self._run())

    def test_a_missing_report_exits_one(self):
        self.assertEqual(1, self._run())

    def test_an_unreadable_floor_exits_two(self):
        rc, _ = run_cli(
            '--report-dir', str(self.report_dir),
            '--floor', str(self.root / 'absent.txt'),
        )
        self.assertEqual(2, rc)


class RepositoryFloorTest(unittest.TestCase):
    """The repository's own floor is readable, complete, and self-consistent.

    The one test in this file that depends on the repository's own numbers: the
    committed floor and the committed fixture report must agree exactly. The
    floor ratchets, so a fixture that is off by one class in either direction
    makes the gate's positive control fail for the wrong reason — and a control
    that fails for the wrong reason proves nothing.
    """

    def test_the_committed_floor_names_the_three_bounds(self):
        floor = mod.read_floor(mod.FLOOR_FILE)
        self.assertEqual({'classes', 'tests', 'skipped'}, set(floor))

    def test_the_committed_floor_matches_the_committed_fixture(self):
        floor = mod.read_floor(mod.FLOOR_FILE)
        measured = mod.measure(FIXTURE_DIR)
        self.assertEqual(
            (floor['classes'], floor['tests']),
            (measured['classes'], measured['tests']),
            'the committed floor and the committed fixture report disagree; the '
            'floor ratchets, so a fixture off by one class makes the gate fail '
            'on a clean tree',
        )
        self.assertEqual(0, floor['skipped'])

    def test_the_committed_fixture_meets_the_committed_floor(self):
        findings = mod.scan(FIXTURE_DIR, mod.read_floor(mod.FLOOR_FILE))
        self.assertEqual([], findings)


if __name__ == '__main__':
    unittest.main()

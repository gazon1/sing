"""Tests for check-instrumented-test-floor.py.

The gate holds the instrumented suite to a recorded floor: the number of test
classes and tests that must run, and the number that must not be skipped.

Four properties matter, and each of them is a way the gate could report green
while the defect it exists for is still there:

  - a run with fewer classes than the floor is a finding (a deleted class);
  - a run with fewer tests than the floor is a finding (a deleted test);
  - a skipped test is a finding (a test that did not run on a device);
  - no report at all is a finding (a tier that did not run is not a tier that
    ran) — a gate that treats a missing report as "nothing to say" is the same
    gate that lets the tier disappear.

The tests build a miniature report tree and point the module at it, so nothing
here depends on the repository's own floor.
"""

import importlib.util
import pathlib
import tempfile
import unittest

import click

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-instrumented-test-floor.py'
spec = importlib.util.spec_from_file_location('check_instrumented_test_floor', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

FLOOR = """\
# a comment
classes 5
tests 19
skipped 0
"""


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


class RepositoryFloorTest(unittest.TestCase):
    """The repository's own floor is readable and complete."""

    def test_the_committed_floor_names_the_three_bounds(self):
        floor = mod.read_floor(mod.FLOOR_FILE)
        self.assertEqual({'classes', 'tests', 'skipped'}, set(floor))


if __name__ == '__main__':
    unittest.main()

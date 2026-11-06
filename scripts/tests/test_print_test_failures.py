"""Tests for print-test-failures.py — the failures of a test run, as text.

The summary is what a red build is read through, so the cases below are the
ways it could say too little or too much:

  - a failing test is named with its class, its name and its message;
  - several failures are all reported — a summary that stops at the first one
    hides the rest of the work;
  - an error (not only a failure) is reported too: an exception in a test is
    the failure it is;
  - a broken report has no failures to report, and says so in a line instead of
    raising in the middle of a red build;
  - a report that does not exist is not a failure of the run.
"""

import importlib.util
import pathlib
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'print-test-failures.py'
spec = importlib.util.spec_from_file_location('print_test_failures', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def report_xml(cases: list[tuple[str, str, str]]) -> str:
    """A JUnit XML with the given (class, name, tag) cases."""
    body = []
    for class_name, case_name, tag in cases:
        if tag == 'skipped':
            body.append(
                f'<testcase classname="{class_name}" name="{case_name}"><skipped/></testcase>'
            )
        else:
            body.append(
                f'<testcase classname="{class_name}" name="{case_name}">'
                f'<{tag} message="the message">the body</{tag}></testcase>'
            )
    return '<testsuite name="suite">' + ''.join(body) + '</testsuite>'


class SummarizeTest(unittest.TestCase):
    def test_a_failing_test_is_named_with_its_message(self):
        failures = mod.summarize(report_xml([
            ('com.example.A', 'a1', 'failure'),
        ]))
        self.assertEqual(1, len(failures))
        self.assertEqual('com.example.A', failures[0]['classname'])
        self.assertEqual('a1', failures[0]['name'])
        self.assertEqual('the message', failures[0]['message'])

    def test_several_failures_are_all_reported(self):
        failures = mod.summarize(report_xml([
            ('com.example.A', 'a1', 'failure'),
            ('com.example.B', 'b1', 'failure'),
        ]))
        self.assertEqual(['a1', 'b1'], [failure['name'] for failure in failures])

    def test_an_error_is_a_failure_too(self):
        failures = mod.summarize(report_xml([
            ('com.example.A', 'a1', 'error'),
        ]))
        self.assertEqual(1, len(failures))

    def test_a_passing_test_is_not_a_failure(self):
        failures = mod.summarize(report_xml([
            ('com.example.A', 'a1', 'skipped'),
        ]))
        self.assertEqual([], failures)

    def test_a_broken_report_has_no_failures_to_report(self):
        self.assertEqual([], mod.summarize('<testsuite name="suite">'))

    def test_the_rendered_failure_carries_the_body(self):
        rendered = mod.render({
            'classname': 'com.example.A',
            'name': 'a1',
            'message': 'the message',
            'body': 'the body',
        })
        self.assertIn('com.example.A#a1: the message', rendered)
        self.assertIn('the body', rendered)


class CliTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_a_missing_directory_is_not_a_failure_of_the_run(self):
        self.assertEqual(0, mod.main([str(self.root / 'nowhere')]))

    def test_a_broken_report_is_reported_as_a_line_not_a_traceback(self):
        (self.root / 'TEST-broken.xml').write_text('<testsuite', encoding='utf-8')
        self.assertEqual(0, mod.main([str(self.root)]))


if __name__ == '__main__':
    unittest.main()

#!/usr/bin/env python3
"""Unit tests for traceability.junit_xml.

Run with: python3 -m unittest discover -s scripts/tests

Scope note
----------
``junit_xml.py``'s docstring pointed its reader at ``scripts/tests/test_kiwi_sync.py``
for the safety net. That file was deleted along with the Kiwi TCMS stand, so the
docstring asserted coverage that no longer existed — and the behaviour it described
had drifted into ``test_traceability.py`` instead, where it is reachable but not
obviously located.

This file covers the parser's own contracts directly, and covers the two paths the
``test_traceability.py`` helper structurally cannot reach:

* ``_junit()`` there always emits a bare ``<testsuite>`` root, so the
  ``<testsuites>`` container — the shape **Maestro** actually produces, one suite
  per flow — had no test at all. That is the same shape ``file`` matching and
  ``_FLOW_ROOTS`` joining depend on.
* message truncation and non-numeric ``time`` were unpinned.
"""
from __future__ import annotations

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "src"))

from traceability.junit_xml import (  # noqa: E402
    MESSAGE_LIMIT,
    TestCaseResult,
    iter_result_files,
    parse_junit,
    parse_junit_file,
)


def _write(directory: pathlib.Path, body: str, name: str = "TEST-x.xml") -> pathlib.Path:
    path = pathlib.Path(directory) / name
    path.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>'
        + body,
        encoding="utf-8",
    )
    return path


class RootShapeTest(unittest.TestCase):
    """Both root shapes must parse; Maestro uses the container, Gradle the bare suite."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_bare_testsuite_root(self):
        path = _write(self.dir, '<testsuite name="s"><testcase classname="C" name="a()"/></testsuite>')
        results = parse_junit_file(path)
        self.assertEqual([r.name for r in results], ["a()"])

    def test_testsuites_container_collects_every_suite(self):
        """The shape Maestro writes: one <testsuite> per flow inside a container."""
        path = _write(self.dir, """
        <testsuites>
          <testsuite name="flow A">
            <testcase classname="dev.mobile.maestro" name="launch"
                      file="Maestro/flows/smoke/01-launch.yaml"/>
          </testsuite>
          <testsuite name="flow B">
            <testcase classname="dev.mobile.maestro" name="create"
                      file="Maestro/flows/tasks/17-create.yaml"/>
          </testsuite>
        </testsuites>
        """)
        results = parse_junit_file(path)
        self.assertEqual(
            sorted(r.file for r in results),
            ["Maestro/flows/smoke/01-launch.yaml",
             "Maestro/flows/tasks/17-create.yaml"],
            "the <testsuites> container must yield every suite's cases",
        )

    def test_classname_comes_from_the_case_not_the_suite(self):
        """Gradle writes the Gradle task suffix into <testsuite name>.

        That value matches no FQN in the repository, so taking the class from the
        suite instead of the case silently breaks the join.
        """
        path = _write(self.dir,
                      '<testsuite name="AgendaDslTest[jvm]">'
                      '<testcase classname="com.singularity.todo.X" name="a()"/>'
                      "</testsuite>")
        self.assertEqual(parse_junit_file(path)[0].classname,
                         "com.singularity.todo.X")


class PrecedenceTest(unittest.TestCase):
    """failure > error > skipped > passed. Not arbitrary; see _classify."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_failure_beats_skipped(self):
        path = _write(self.dir, '<testsuite><testcase classname="C" name="a()">'
                                 '<skipped/><failure message="boom"/></testcase></testsuite>')
        self.assertEqual(parse_junit_file(path)[0].status, "failed")

    def test_error_beats_skipped(self):
        path = _write(self.dir, '<testsuite><testcase classname="C" name="a()">'
                                 '<skipped/><error message="infra"/></testcase></testsuite>')
        self.assertEqual(parse_junit_file(path)[0].status, "error")

    def test_no_child_is_passed(self):
        path = _write(self.dir, '<testsuite><testcase classname="C" name="a()"/></testsuite>')
        self.assertEqual(parse_junit_file(path)[0].status, "passed")

    def test_error_is_a_failing_outcome(self):
        """`error` is distinct from `failed` but must not read as success."""
        path = _write(self.dir, '<testsuite><testcase classname="C" name="a()">'
                                 '<error message="x"/></testcase></testsuite>')
        self.assertTrue(parse_junit_file(path)[0].failed_like)


class MessageAndTimeTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_message_is_truncated_to_the_limit(self):
        """Kiwi's column is a TEXT field, not a log sink."""
        long = "E" * (MESSAGE_LIMIT + 500)
        path = _write(self.dir, f'<testsuite><testcase classname="C" name="a()">'
                                f'<failure message="{long}"/></testcase></testsuite>')
        self.assertEqual(len(parse_junit_file(path)[0].message), MESSAGE_LIMIT)

    def test_elapsed_time_is_parsed(self):
        path = _write(self.dir,
                      '<testsuite><testcase classname="C" name="a()" time="1.5"/></testsuite>')
        self.assertEqual(parse_junit_file(path)[0].time, 1.5)

    def test_non_numeric_time_becomes_zero(self):
        """A malformed duration must not lose the whole result."""
        path = _write(self.dir,
                      '<testsuite><testcase classname="C" name="a()" time="n/a"/></testsuite>')
        self.assertEqual(parse_junit_file(path)[0].time, 0.0)

    def test_missing_attributes_default_to_empty(self):
        path = _write(self.dir, "<testsuite><testcase/></testsuite>")
        result = parse_junit_file(path)[0]
        self.assertEqual(result.classname, "")
        self.assertEqual(result.name, "")
        self.assertEqual(result.file, "")


class MalformedInputTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_malformed_file_is_skipped_not_raised(self):
        """One bad artefact must not abort a whole sync."""
        path = _write(self.dir, "<testsuite><testcase></testsuite>")
        self.assertEqual(parse_junit_file(path), [])

    def test_one_bad_file_does_not_lose_the_good_ones(self):
        _write(self.dir, "<testsuite><testcase></testsuite>", name="TEST-bad.xml")
        _write(self.dir, '<testsuite><testcase classname="C" name="good()"/></testsuite>',
               name="TEST-good.xml")
        self.assertEqual([r.name for r in parse_junit([self.dir])], ["good()"])


class ResultFileSelectionTest(unittest.TestCase):

    def test_missing_directory_yields_empty(self):
        self.assertEqual(parse_junit([pathlib.Path("/nonexistent-xyz")]), [])
        self.assertEqual(iter_result_files(pathlib.Path("/nonexistent-xyz")), [])

    def test_files_are_sorted_for_a_byte_identical_rebuild(self):
        with tempfile.TemporaryDirectory() as tmp:
            for name in ("TEST-b.xml", "TEST-a.xml", "TEST-c.xml"):
                _write(pathlib.Path(tmp), f'<testsuite><testcase name="{name}"/></testsuite>',
                       name=name)
            self.assertEqual([p.name for p in iter_result_files(pathlib.Path(tmp))],
                             ["TEST-a.xml", "TEST-b.xml", "TEST-c.xml"])

    def test_non_matching_files_are_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write(pathlib.Path(tmp), "<testsuite/>", name="other.xml")
            self.assertEqual(iter_result_files(pathlib.Path(tmp)), [])


class DtoTest(unittest.TestCase):

    def test_result_is_immutable_and_comparable(self):
        """Frozen so a projection step cannot mutate another caller's view."""
        r = TestCaseResult(classname="C", name="n", status="passed")
        with self.assertRaises(Exception):
            r.status = "failed"


if __name__ == "__main__":
    unittest.main()
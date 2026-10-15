#!/usr/bin/env python3
"""Tests for check-backlog-issue-refs.py — pure GitHub-issue backlog consistency gate.

Single invariant (I4):
  Every OPEN issue in the snapshot cites a backlog entry via a Backlog: field.

The four original invariants (I1–I3) checked consistency between local deferred/
files and GH issues. PR #645 deleted that directory; only I4 remains.
"""

import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / "check-backlog-issue-refs.py"

_spec = importlib.util.spec_from_file_location("check_backlog_issue_refs", SCRIPT)
cbir = importlib.util.module_from_spec(_spec)
sys.modules["check_backlog_issue_refs"] = cbir
_spec.loader.exec_module(cbir)


class HasBacklogFieldTest(unittest.TestCase):
    """_has_backlog_field reads the Backlog: field from an issue body."""

    def test_present_with_value(self):
        issue = {"number": 42, "state": "OPEN", "body": "Some text\nBacklog: my-slug\nMore text"}
        self.assertTrue(cbir._has_backlog_field(issue))

    def test_present_bare(self):
        issue = {"number": 42, "state": "OPEN", "body": "Backlog: another-slug"}
        self.assertTrue(cbir._has_backlog_field(issue))

    def test_missing(self):
        issue = {"number": 42, "state": "OPEN", "body": "Some description without any backlog field"}
        self.assertFalse(cbir._has_backlog_field(issue))

    def test_empty_body(self):
        issue = {"number": 42, "state": "OPEN", "body": ""}
        self.assertFalse(cbir._has_backlog_field(issue))

    def test_none_body(self):
        issue = {"number": 42, "state": "OPEN", "body": None}
        self.assertFalse(cbir._has_backlog_field(issue))

    def test_case_insensitive(self):
        issue = {"number": 42, "state": "OPEN", "body": "backlog: some-slug"}
        self.assertTrue(cbir._has_backlog_field(issue))

    def test_whitespace_around_value(self):
        issue = {"number": 42, "state": "OPEN", "body": "Backlog:   slug-with-spaces   "}
        self.assertTrue(cbir._has_backlog_field(issue))


class InvariantI4Test(unittest.TestCase):
    """I4: every OPEN issue must have a Backlog: field in its body."""

    def test_open_issue_without_backlog_field_is_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot = pathlib.Path(tmp) / "snapshot.json"
            snapshot.write_text(
                json.dumps([
                    {"number": 42, "state": "OPEN", "title": "A ticket", "body": "Description only"},
                ]),
                encoding="utf-8",
            )

            findings = cbir.check_invariants(snapshot)
            self.assertEqual(1, len(findings))
            self.assertIn("#42", findings[0])
            self.assertIn("Backlog:", findings[0])

    def test_open_issue_with_backlog_field_is_not_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot = pathlib.Path(tmp) / "snapshot.json"
            snapshot.write_text(
                json.dumps([
                    {"number": 42, "state": "OPEN", "title": "A ticket", "body": "Backlog: my-slug"},
                ]),
                encoding="utf-8",
            )

            findings = cbir.check_invariants(snapshot)
            self.assertEqual([], findings)

    def test_closed_issue_without_backlog_field_is_not_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot = pathlib.Path(tmp) / "snapshot.json"
            snapshot.write_text(
                json.dumps([
                    {"number": 7, "state": "CLOSED", "title": "Done", "body": "No backlog field here"},
                ]),
                encoding="utf-8",
            )

            findings = cbir.check_invariants(snapshot)
            self.assertEqual([], findings)

    def test_multiple_open_issues_all_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot = pathlib.Path(tmp) / "snapshot.json"
            snapshot.write_text(
                json.dumps([
                    {"number": 1, "state": "OPEN", "title": "A", "body": ""},
                    {"number": 2, "state": "OPEN", "title": "B", "body": "Backlog: b-slug"},
                    {"number": 3, "state": "OPEN", "title": "C", "body": "Description"},
                ]),
                encoding="utf-8",
            )

            findings = cbir.check_invariants(snapshot)
            self.assertEqual(2, len(findings))
            nos = sorted(int(f.split("#")[1].split()[0]) for f in findings)
            self.assertEqual([1, 3], nos)

    def test_mixed_open_and_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot = pathlib.Path(tmp) / "snapshot.json"
            snapshot.write_text(
                json.dumps([
                    {"number": 1, "state": "OPEN", "title": "Open", "body": "No backlog field"},
                    {"number": 2, "state": "CLOSED", "title": "Closed", "body": ""},
                    {"number": 3, "state": "RESOLVED", "title": "Resolved", "body": "Backlog: s"},
                ]),
                encoding="utf-8",
            )

            findings = cbir.check_invariants(snapshot)
            self.assertEqual(1, len(findings))
            self.assertIn("#1", findings[0])


class MissingSnapshotTest(unittest.TestCase):
    """The gate exits 1 when the snapshot file does not exist."""

    def test_missing_snapshot_exits(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot = pathlib.Path(tmp) / "does-not-exist.json"

            with self.assertRaises(SystemExit) as cm:
                cbir.check_invariants(snapshot)
            self.assertEqual(1, cm.exception.code)


if __name__ == "__main__":
    unittest.main()

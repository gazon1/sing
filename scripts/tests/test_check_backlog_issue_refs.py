#!/usr/bin/env python3
"""Tests for check-backlog-issue-refs.py.

The gate enforces four invariants:
  I1  Every #NN cited as `Tracked as:` exists in the snapshot.
  I2  An OPEN/PARTIAL entry citing a CLOSED issue is a stale tracking.
  I3  An OPEN/PARTIAL entry without Tracked as: or Tracking: none is untracked.
  I4  Every OPEN issue cites a backlog entry via Backlog: field. (advisory)

Tests are organised around each invariant and the parser functions that feed them.
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


def _entry(slug: str, body: str) -> str:
    return f"## {slug}\n\n{body}\n\n---\n"


class ExtractTrackedIssueTest(unittest.TestCase):
    """_extract_tracked_issue reads the #NN from `**Tracked as:** #NN`."""

    def test_plain_reference(self):
        body = "**Tracked as:** #123"
        self.assertEqual(123, cbir._extract_tracked_issue("slug", body))

    def test_markdown_link_reference(self):
        body = "**Tracked as:** [#123](https://github.com/gazon1/sing/issues/123)"
        self.assertEqual(123, cbir._extract_tracked_issue("slug", body))

    def test_markdown_link_with_suffix(self):
        # Real shape from the backlog: `[#110](https://github.com/gazon1/sing/issues/110)`
        body = "**Tracked as:** [#110](https://github.com/gazon1/sing/issues/110)"
        self.assertEqual(110, cbir._extract_tracked_issue("slug", body))

    def test_no_reference(self):
        body = "**Status: OPEN**\n\nSome text without any issue reference."
        self.assertIsNone(cbir._extract_tracked_issue("slug", body))

    def test_empty_body(self):
        self.assertIsNone(cbir._extract_tracked_issue("slug", ""))


class ClassifyTest(unittest.TestCase):
    """_classify distinguishes open/partial/closed/unclassifiable."""

    def test_open(self):
        self.assertEqual("open", cbir._classify("OPEN"))

    def test_open_uppercase_period(self):
        self.assertEqual("open", cbir._classify("OPEN."))

    def test_partial(self):
        self.assertEqual("partial", cbir._classify("PARTIALLY CLOSED, re-tracked as #110"))

    def test_closed(self):
        self.assertEqual("closed", cbir._classify("CLOSED"))

    def test_resolved(self):
        self.assertEqual("closed", cbir._classify("RESOLVED (2026-10-04)."))

    def test_none(self):
        self.assertEqual("unclassifiable", cbir._classify(None))


class InvariantI1Test(unittest.TestCase):
    """I1: every #NN cited as `Tracked as:` must exist in the snapshot."""

    def test_missing_issue_is_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            backlog = pathlib.Path(tmp) / "backlog.md"
            snapshot = pathlib.Path(tmp) / "snapshot.json"

            backlog.write_text(
                _entry("a-real-finding", "**Status: OPEN**\n\n**Tracked as:** [#99999](url)")
                + _entry("another", "**Status: CLOSED**"),
                encoding="utf-8",
            )
            snapshot.write_text(json.dumps([{"number": 99998, "state": "OPEN", "title": "x"}]))

            findings = cbir.check_invariants(backlog, snapshot)
            i1_findings = [f for f in findings if "[I1]" in f]
            self.assertEqual(1, len(i1_findings))
            self.assertIn("#99999", i1_findings[0])

    def test_existing_issue_is_not_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            backlog = pathlib.Path(tmp) / "backlog.md"
            snapshot = pathlib.Path(tmp) / "snapshot.json"

            backlog.write_text(
                _entry("a-real-finding", "**Status: OPEN**\n\n**Tracked as:** [#42](url)"),
                encoding="utf-8",
            )
            snapshot.write_text(json.dumps([{"number": 42, "state": "OPEN", "title": "x", "backlog_slug": ""}]))

            findings = cbir.check_invariants(backlog, snapshot)
            i1_findings = [f for f in findings if "[I1]" in f]
            self.assertEqual([], i1_findings)


class InvariantI2Test(unittest.TestCase):
    """I2: an OPEN/PARTIAL entry tracking a CLOSED issue is a stale tracking."""

    def test_open_entry_tracking_closed_issue_is_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            backlog = pathlib.Path(tmp) / "backlog.md"
            snapshot = pathlib.Path(tmp) / "snapshot.json"

            backlog.write_text(
                _entry("stale-tracking", "**Status: OPEN**\n\n**Tracked as:** [#7](url)"),
                encoding="utf-8",
            )
            snapshot.write_text(json.dumps([{"number": 7, "state": "CLOSED", "title": "Done", "backlog_slug": ""}]))

            findings = cbir.check_invariants(backlog, snapshot)
            i2_findings = [f for f in findings if "[I2]" in f]
            self.assertEqual(1, len(i2_findings))
            self.assertIn("#7", i2_findings[0])

    def test_closed_entry_tracking_closed_issue_is_not_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            backlog = pathlib.Path(tmp) / "backlog.md"
            snapshot = pathlib.Path(tmp) / "snapshot.json"

            backlog.write_text(
                _entry("closed-entry", "**Status: CLOSED**\n\n**Tracked as:** [#7](url)"),
                encoding="utf-8",
            )
            snapshot.write_text(json.dumps([{"number": 7, "state": "CLOSED", "title": "Done", "backlog_slug": ""}]))

            findings = cbir.check_invariants(backlog, snapshot)
            i2_findings = [f for f in findings if "[I2]" in f]
            self.assertEqual([], i2_findings)


class InvariantI3Test(unittest.TestCase):
    """I3: an OPEN/PARTIAL entry without Tracked as: or Tracking: none is untracked."""

    def test_open_untracked_is_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            backlog = pathlib.Path(tmp) / "backlog.md"
            snapshot = pathlib.Path(tmp) / "snapshot.json"

            backlog.write_text(
                _entry("untracked-open", "**Status: OPEN**\n\nJust some text."),
                encoding="utf-8",
            )
            snapshot.write_text(json.dumps([]))

            findings = cbir.check_invariants(backlog, snapshot)
            i3_findings = [f for f in findings if "[I3]" in f]
            self.assertEqual(1, len(i3_findings))

    def test_tracking_none_is_not_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            backlog = pathlib.Path(tmp) / "backlog.md"
            snapshot = pathlib.Path(tmp) / "snapshot.json"

            backlog.write_text(
                _entry("env-finding", "**Status: OPEN**\n\n**Tracking:** none — this is an environment observation."),
                encoding="utf-8",
            )
            snapshot.write_text(json.dumps([]))

            findings = cbir.check_invariants(backlog, snapshot)
            i3_findings = [f for f in findings if "[I3]" in f]
            self.assertEqual([], i3_findings)


class InvariantI4Test(unittest.TestCase):
    """I4: every OPEN issue should cite a backlog entry via Backlog: field. (advisory)"""

    def test_open_issue_without_backlog_field_is_warning(self):
        with tempfile.TemporaryDirectory() as tmp:
            backlog = pathlib.Path(tmp) / "backlog.md"
            snapshot = pathlib.Path(tmp) / "snapshot.json"

            backlog.write_text(
                _entry("some-entry", "**Status: OPEN**\n\n**Tracked as:** [#1](url)"),
                encoding="utf-8",
            )
            snapshot.write_text(json.dumps([{"number": 42, "state": "OPEN", "title": "A ticket", "backlog_slug": ""}]))

            findings = cbir.check_invariants(backlog, snapshot)
            i4_findings = [f for f in findings if "[I4]" in f]
            self.assertEqual(1, len(i4_findings))
            self.assertIn("#42", i4_findings[0])
            self.assertIn("no Backlog", i4_findings[0])

    def test_open_issue_with_backlog_field_is_not_warning(self):
        with tempfile.TemporaryDirectory() as tmp:
            backlog = pathlib.Path(tmp) / "backlog.md"
            snapshot = pathlib.Path(tmp) / "snapshot.json"

            backlog.write_text(
                _entry("some-entry", "**Status: OPEN**\n\n**Tracked as:** [#1](url)"),
                encoding="utf-8",
            )
            snapshot.write_text(
                json.dumps(
                    [{"number": 42, "state": "OPEN", "title": "A ticket", "backlog_slug": "some-entry"}]
                )
            )

            findings = cbir.check_invariants(backlog, snapshot)
            i4_findings = [f for f in findings if "[I4]" in f]
            self.assertEqual([], i4_findings)


class NoSnapshotTest(unittest.TestCase):
    """The gate exits 1 when the snapshot file does not exist."""

    def test_missing_snapshot_exits(self):
        with tempfile.TemporaryDirectory() as tmp:
            backlog = pathlib.Path(tmp) / "backlog.md"
            snapshot = pathlib.Path(tmp) / "does-not-exist.json"
            backlog.write_text(_entry("x", "**Status: OPEN**"), encoding="utf-8")

            with self.assertRaises(SystemExit) as cm:
                cbir.check_invariants(backlog, snapshot)
            self.assertEqual(1, cm.exception.code)


if __name__ == "__main__":
    unittest.main()

"""Tests for the local toolbox-usage report.

The report reads two sources and must not confuse them: the *records* of what happened
and the *registry snapshots* of what the toolbox was. The failure this file exists to
prevent is a report that says "unused" when it only knows "not observed in this window",
and a report that crashes on a file a process was killed halfway through writing.
"""

import importlib.util
import json
import pathlib
import sys
import tempfile
import time
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent


def _load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


usage = _load('mcp_toolbox_usage', 'mcp-toolbox-usage.py')


def _record(tool: str, outcome: str = "ok", ts: int = 1_700_000_000_000, **extra) -> dict:
    record = {
        "schemaVersion": 1,
        "event": "tool_call",
        "ts": ts,
        "source": "MCP",
        "tool": tool,
        "outcome": outcome,
        "durationMs": 12,
    }
    record.update(extra)
    return record


class ReadRecordsTest(unittest.TestCase):
    """What the reader does with a directory of records."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.dir = pathlib.Path(self._tmp.name)

    def _write(self, name: str, lines: list[str]) -> None:
        (self.dir / name).write_text("\n".join(lines) + "\n", encoding="utf-8")

    def test_records_are_read_from_every_file(self):
        self._write("2026-10-20-1.jsonl", [json.dumps(_record("create_task"))])
        self._write("2026-10-20-2.jsonl", [json.dumps(_record("list_tasks"))])

        records, malformed, files = usage.read_records(self.dir, None)

        self.assertEqual(2, len(records))
        self.assertEqual(0, malformed)
        self.assertEqual(2, files)

    def test_a_truncated_line_is_counted_and_skipped(self):
        """A process killed mid-write leaves half a line. It must not lose the file."""
        self._write(
            "2026-10-20-1.jsonl",
            [json.dumps(_record("create_task")), '{"ts": 1700, "tool": "half-wri'],
        )

        records, malformed, _ = usage.read_records(self.dir, None)

        self.assertEqual(1, len(records), "the good record must survive")
        self.assertEqual(1, malformed, "the truncated line must be counted")

    def test_a_window_excludes_the_older_records(self):
        # The window is measured against the real clock, so the timestamps have to be
        # real too — a fixed epoch in the past would age out of every window.
        now = int(time.time() * 1000)
        self._write(
            "2026-10-20-1.jsonl",
            [
                json.dumps(_record("create_task", ts=now - 2 * 24 * 3600 * 1000)),
                json.dumps(_record("list_tasks", ts=now)),
            ],
        )

        records, _, _ = usage.read_records(self.dir, 1)

        self.assertEqual(["list_tasks"], [r["tool"] for r in records])


class SummaryTest(unittest.TestCase):
    """What the report says about the records it read."""

    def test_outcomes_are_counted_per_tool(self):
        summary = usage.summarize([
            _record("create_task"),
            _record("create_task"),
            _record("create_task", outcome="business_error", errorCode="validation"),
        ])

        entry = summary["create_task"]
        self.assertEqual(3, entry["calls"])
        self.assertEqual(2, entry["ok"])
        self.assertEqual(1, entry["errors"])

    def test_a_tool_with_no_duration_has_no_average(self):
        record = _record("create_task")
        del record["durationMs"]
        summary = usage.summarize([record])

        self.assertIsNone(summary["create_task"]["avgMs"])


class RegistryTest(unittest.TestCase):
    """The registry snapshot is the other half of the answer."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.dir = pathlib.Path(self._tmp.name)

    def test_a_registered_tool_that_was_never_called_is_named(self):
        (self.dir / "registry.json").write_text(
            json.dumps({"registeredTools": ["create_task", "list_open_deferred"]}),
            encoding="utf-8",
        )
        records, _, _ = usage.read_records(self.dir, None)
        summary = usage.summarize(records)
        registry = usage.read_registry(self.dir)

        report = usage.render(summary, registry, 0, 1, len(records), None)

        self.assertIn("Registered but not observed", report)
        self.assertIn("list_open_deferred", report)

    def test_a_tool_that_is_no_longer_registered_is_named(self):
        """A client working from a stale toolbox is a signal, not a cosmetic detail."""
        (self.dir / "registry.json").write_text(
            json.dumps({"registeredTools": ["create_task"]}),
            encoding="utf-8",
        )
        (self.dir / "2026-10-20-1.jsonl").write_text(
            json.dumps(_record("old_tool")) + "\n",
            encoding="utf-8",
        )
        records, _, _ = usage.read_records(self.dir, None)
        summary = usage.summarize(records)
        registry = usage.read_registry(self.dir)

        report = usage.render(summary, registry, 0, 1, len(records), None)

        self.assertIn("Observed but not in the last registry snapshot", report)
        self.assertIn("old_tool", report)

    def test_without_a_registry_the_report_says_so(self):
        records, _, _ = usage.read_records(self.dir, None)
        summary = usage.summarize(records)

        report = usage.render(summary, usage.read_registry(self.dir), 0, 0, len(records), None)

        self.assertIn("No registry snapshot found", report)

    def test_a_corrupt_registry_is_ignored_rather_than_fatal(self):
        (self.dir / "registry.json").write_text("{not json", encoding="utf-8")

        self.assertIsNone(usage.read_registry(self.dir))


if __name__ == "__main__":
    unittest.main()

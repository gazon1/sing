"""Tests for the local toolbox-usage report.

The report reads two sources and must not confuse them: the *records* of what happened
and the *registry snapshots* of what the toolbox was. The failure this file exists to
prevent is a report that says "unused" when it only knows "not observed in this window",
and a report that crashes on a file a process was killed halfway through writing.

The registry snapshot carries a version, and the version decides how it is read. That
is not decoration: the reader used to accept a string or an object and move on, and
the price was a report that said nothing at all while looking as if it had. So the
tests below write the shape a version declares — objects for version 1, strings for
the legacy version without one — and check that an entry which fits neither is
counted rather than guessed at.
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


def _registry(tools, **extra) -> dict:
    """A registry snapshot of the shape the server writes today: version 1, objects."""
    document = {
        "schemaVersion": 1,
        "registeredTools": [{"name": name} for name in tools],
    }
    document.update(extra)
    return document


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

        records, malformed, files, _unknown, _outside = usage.read_records(self.dir, None)

        self.assertEqual(2, len(records))
        self.assertEqual(0, malformed)
        self.assertEqual(2, files)

    def test_a_truncated_line_is_counted_and_skipped(self):
        """A process killed mid-write leaves half a line. It must not lose the file."""
        self._write(
            "2026-10-20-1.jsonl",
            [json.dumps(_record("create_task")), '{"ts": 1700, "tool": "half-wri'],
        )

        records, malformed, _, _unknown, _outside = usage.read_records(self.dir, None)

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

        records, _, _, _unknown, outside = usage.read_records(self.dir, 1)

        self.assertEqual(["list_tasks"], [r["tool"] for r in records])
        self.assertEqual(1, outside, "the record the window excluded must be counted")

    def test_the_report_says_how_many_records_the_window_excluded(self):
        """The total is the total of the window; the excluded ones are named."""
        now = int(time.time() * 1000)
        self._write(
            "2026-10-20-1.jsonl",
            [
                json.dumps(_record("create_task", ts=now - 2 * 24 * 3600 * 1000)),
                json.dumps(_record("list_tasks", ts=now)),
            ],
        )
        records, malformed, files, unknown, outside = usage.read_records(self.dir, 1)

        report = usage.render(
            usage.summarize(records), None, malformed, files, len(records), 1, unknown, outside
        )

        self.assertIn("older than the window", report)


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

    def test_the_outcome_is_read_the_way_the_writer_writes_it(self):
        """The writer owns the vocabulary: `ToolCallOutcome.OK` is written as `OK`.

        A reader that expected `ok` counted every real call as an error — a report
        that said every tool in the toolbox failed, every time, and said it
        confidently. The contract test caught it against a real record.
        """
        summary = usage.summarize([
            _record("create_task", outcome="OK"),
            _record("create_task", outcome="BUSINESS_ERROR"),
        ])

        entry = summary["create_task"]
        self.assertEqual(1, entry["ok"])
        self.assertEqual(1, entry["errors"])
        self.assertEqual(50.0, entry["errorRate"])

    def test_the_error_rate_is_a_share_of_the_calls(self):
        """Three errors beside three calls is a division a reader may not do."""
        summary = usage.summarize([
            _record("create_task"),
            _record("create_task"),
            _record("create_task", outcome="business_error", errorCode="validation"),
        ])

        self.assertEqual(33.3, summary["create_task"]["errorRate"])

    def test_a_tool_that_never_failed_has_no_error_rate(self):
        summary = usage.summarize([_record("create_task")])

        self.assertEqual(0.0, summary["create_task"]["errorRate"])

    def test_a_tool_with_no_duration_has_no_average(self):
        record = _record("create_task")
        del record["durationMs"]
        summary = usage.summarize([record])

        self.assertIsNone(summary["create_task"]["avgMs"])


class UnknownVersionTest(unittest.TestCase):
    """A record from an event schema this script does not know.

    The records outlive the code that wrote them, so a future version of the event
    will land in these files. Its fields may mean something else, and a report that
    counted it as a call would state a number it cannot stand behind.
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.dir = pathlib.Path(self._tmp.name)

    def test_a_record_of_an_unknown_version_is_not_counted_as_a_call(self):
        (self.dir / "2026-10-20-1.jsonl").write_text(
            json.dumps(_record("create_task", schemaVersion=2)) + "\n",
            encoding="utf-8",
        )

        records, _malformed, _files, unknown, _outside = usage.read_records(self.dir, None)

        self.assertEqual([], records, "a record this script cannot read is not a call")
        self.assertEqual(1, unknown)

    def test_the_report_says_how_many_records_it_could_not_read(self):
        (self.dir / "2026-10-20-1.jsonl").write_text(
            json.dumps(_record("create_task", schemaVersion=2)) + "\n",
            encoding="utf-8",
        )
        records, malformed, files, unknown, _outside = usage.read_records(self.dir, None)

        report = usage.render(
            usage.summarize(records), None, malformed, files, len(records), None, unknown
        )

        self.assertIn("unknown schema version", report)


class RegistryTest(unittest.TestCase):
    """The registry snapshot is the other half of the answer."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.dir = pathlib.Path(self._tmp.name)

    def _report(self, registry) -> str:
        records, malformed, files, unknown, outside = usage.read_records(self.dir, None)
        return usage.render(
            usage.summarize(records), registry, malformed, files, len(records), None,
            unknown, outside,
        )

    def test_a_registered_tool_that_was_never_called_is_named(self):
        # The shape the server writes: version 1, and the tool list a list of objects,
        # because the same shape is what `describe_toolbox` reports.
        (self.dir / "registry.json").write_text(
            json.dumps(_registry(["create_task", "list_open_deferred"])),
            encoding="utf-8",
        )

        report = self._report(usage.read_registry(self.dir))

        self.assertIn("Registered but not observed", report)
        self.assertIn("list_open_deferred", report)

    def test_a_tool_that_is_no_longer_registered_is_named(self):
        """A client working from a stale toolbox is a signal, not a cosmetic detail."""
        (self.dir / "registry.json").write_text(
            json.dumps(_registry(["create_task"])),
            encoding="utf-8",
        )
        (self.dir / "2026-10-20-1.jsonl").write_text(
            json.dumps(_record("old_tool")) + "\n",
            encoding="utf-8",
        )

        report = self._report(usage.read_registry(self.dir))

        self.assertIn("Observed but not in the last registry snapshot", report)
        self.assertIn("old_tool", report)

    def test_a_registry_of_an_older_build_is_still_read(self):
        """A registry written before the list became a list of objects.

        No version at all means version 0, and version 0's lists are strings.
        """
        (self.dir / "registry.json").write_text(
            json.dumps({"registeredTools": ["create_task"]}),
            encoding="utf-8",
        )

        report = self._report(usage.read_registry(self.dir))

        self.assertIn("Registered but not observed", report)
        self.assertIn("create_task", report)

    def test_an_entry_that_does_not_match_its_version_is_counted(self):
        """A string in a version 1 list is a defect, and a defect is counted."""
        (self.dir / "registry.json").write_text(
            json.dumps({
                "schemaVersion": 1,
                "registeredTools": [{"name": "create_task"}, "list_tasks"],
            }),
            encoding="utf-8",
        )

        registry = usage.read_registry(self.dir)
        names, malformed, unknown = usage.tool_names(registry, "registeredTools")

        self.assertEqual(["create_task"], names)
        self.assertEqual(1, malformed, "the entry that fits no shape must be counted")
        self.assertFalse(unknown)

    def test_a_registry_of_an_unknown_version_is_not_read(self):
        """Its fields may mean something else, so the tool list is not read at all."""
        (self.dir / "registry.json").write_text(
            json.dumps({
                "schemaVersion": 99,
                "registeredTools": [{"name": "create_task"}],
            }),
            encoding="utf-8",
        )

        report = self._report(usage.read_registry(self.dir))

        self.assertIn("version this script does not know", report)
        self.assertNotIn("Registered but not observed", report)

    def test_a_flag_that_named_no_tool_is_named(self):
        """The operator set a flag and believes a tool is off. It is not."""
        (self.dir / "registry.json").write_text(
            json.dumps(_registry(["create_task"], unmatchedFlags=["old_tool"])),
            encoding="utf-8",
        )

        report = self._report(usage.read_registry(self.dir))

        self.assertIn("Flags that named no tool", report)
        self.assertIn("old_tool", report)

    def test_without_a_registry_the_report_says_so(self):
        report = self._report(usage.read_registry(self.dir))

        self.assertIn("No registry snapshot found", report)

    def test_a_corrupt_registry_is_ignored_rather_than_fatal(self):
        (self.dir / "registry.json").write_text("{not json", encoding="utf-8")

        self.assertIsNone(usage.read_registry(self.dir))


if __name__ == "__main__":
    unittest.main()

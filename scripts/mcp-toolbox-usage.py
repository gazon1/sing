#!/usr/bin/env python3
"""mcp-toolbox-usage.py — what the agents actually called.

Why
---
The toolbox grew a description for every tool, a catalogue entry, and a place in the
documentation. None of that says whether a tool is *used*. A tool that nobody calls is
either dead weight or a tool whose name nobody understood — and the two need opposite
answers, so the difference is worth measuring.

The records are the MCP server's own: one JSONL line per completed tool call, written
best-effort, never containing an argument, a result or an exception message. This
script reads them and answers three questions:

1. Which tools are called, how often, and how often they fail.
2. Which registered tools were never observed in the window.
3. Which observed tools are no longer registered — a tool that was removed but is still
   being called is a client working from a stale idea of the toolbox.

Two sources, kept apart on purpose
----------------------------------
* **runtime telemetry** — the JSONL files. What happened.
* **registry snapshots** — `registry.json`, written when a server starts. What the
  toolbox was at that moment.

"Registered but never called" is only meaningful relative to both: a tool registered
yesterday and never called *today* says nothing. The report therefore states the window
it looked at, and it says "not observed in this window" rather than "unused" — the
absence of a record is not proof that no call happened, because a write can fail and a
process can die mid-line.

Records outlive the code that wrote them
--------------------------------------
Every record carries the `schemaVersion` of the event it came from. A record written by
a version this script does not know is **not** counted as a call: its fields may mean
something else, and a report that counted it would state a number it cannot support. The
records are counted separately and named in the output, so "the report does not understand
these" is visible rather than folded into the totals.

This is a **local diagnostic, not a gate**. CI has no local telemetry, so a gate over
this data could only check the age of a file CI never writes.

    python3 scripts/mcp-toolbox-usage.py
    python3 scripts/mcp-toolbox-usage.py --json
    python3 scripts/mcp-toolbox-usage.py --data-dir /path/to/tool-calls
"""

from __future__ import annotations

import json
import pathlib
import sys
import time
from collections import Counter, defaultdict

try:
    import click
except ImportError:  # pragma: no cover - click is a gate dependency
    click = None

DEFAULT_DATA_DIR = pathlib.Path.home() / ".singularity-todo" / "tool-calls"
RECORD_SUFFIX = ".jsonl"
REGISTRY_NAME = "registry.json"

#: The event schema this script understands. It matches
#: `ToolCallEvent.SCHEMA_VERSION` on the server side.
#:
#: A record of another version is counted, reported, and *not* read as a call: its
#: `outcome` may mean something else entirely, and a total that quietly included it
#: would be a number this script cannot stand behind.
KNOWN_SCHEMA_VERSION = 1

#: The registry snapshot schema this script understands.
#:
#: Version 1 writes the tool lists as objects — `{"name": "create_task"}` — the same
#: shape `describe_toolbox` reports. A snapshot written before the version existed
#: has no `schemaVersion` at all, and its lists are strings; that is version 0.
#:
#: The version decides how a list is read, not what the entries happen to look like.
#: A reader that accepts whatever it finds cannot say what it read, and the one time
#: this script was wrong about that, it read a snapshot as strings and said nothing
#: about the tools it had just failed to see.
KNOWN_REGISTRY_VERSION = 1
LEGACY_REGISTRY_VERSION = 0


def read_records(data_dir: pathlib.Path, since_days: int | None):
    """Every readable record in the window, plus the ones it could not use.

    Four kinds are kept apart: the records this script understands, the records whose
    schema version it does not, the lines that are not records at all, and the records
    that are older than the window. The last one used to disappear silently — the
    report said "N records" and the reader could not say how many it had not looked
    at, which is the number a reader needs when the total looks too small.
    """
    cutoff = None if since_days is None else time.time() - since_days * 86400
    records = []
    malformed = 0
    unknown_version = 0
    outside_window = 0
    files_read = 0
    for path in sorted(data_dir.glob(f"*{RECORD_SUFFIX}")):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            continue
        files_read += 1
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                # A process killed mid-write leaves a truncated line. It is counted, not
                # fatal: the rest of the file is still good.
                malformed += 1
                continue
            if not isinstance(record, dict):
                malformed += 1
                continue
            if record.get("schemaVersion") != KNOWN_SCHEMA_VERSION:
                # A record from another version of the event. It is counted, and it is
                # not read as a call: its fields may mean something else.
                unknown_version += 1
                continue
            if cutoff is not None:
                ts = record.get("ts")
                if isinstance(ts, (int, float)) and ts / 1000 < cutoff:
                    outside_window += 1
                    continue
            records.append(record)
    return records, malformed, files_read, unknown_version, outside_window


def read_registry(data_dir: pathlib.Path):
    """The last registry snapshot, if the server wrote one."""
    path = data_dir / REGISTRY_NAME
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return data if isinstance(data, dict) else None


def summarize(records):
    """Per tool: how often it was called, how often it failed, and how long it took.

    The error rate is a share of the calls, not a second count: "3 errors" beside
    "calls: 3" is two numbers a reader has to divide, and a reader who does not
    divide them reads a tool that always fails as a tool that failed three times.

    The outcome is compared case-insensitively, because the *writer* owns the
    vocabulary: `ToolCallOutcome.OK` is written as `OK`, and a reader that expected
    `ok` counted every real call as an error — a report that said every tool in the
    toolbox failed, every time, and said it confidently.
    """
    by_tool = defaultdict(lambda: {"calls": 0, "ok": 0, "errors": 0, "last": 0, "duration": []})
    for record in records:
        tool = record.get("tool") or "(session)"
        entry = by_tool[tool]
        entry["calls"] += 1
        outcome = record.get("outcome")
        normalized = outcome.strip().lower() if isinstance(outcome, str) else ""
        if normalized == "ok":
            entry["ok"] += 1
        elif normalized:
            entry["errors"] += 1
        ts = record.get("ts")
        if isinstance(ts, (int, float)):
            entry["last"] = max(entry["last"], ts)
        duration = record.get("durationMs")
        if isinstance(duration, (int, float)):
            entry["duration"].append(duration)
    for entry in by_tool.values():
        durations = entry.pop("duration")
        entry["avgMs"] = round(sum(durations) / len(durations)) if durations else None
        calls = entry["calls"]
        entry["errorRate"] = round(entry["errors"] / calls * 100, 1) if calls else None
    return dict(by_tool)


def registry_version(registry) -> int | None:
    """The version of a registry snapshot, or None when it is not one this knows.

    A snapshot without a `schemaVersion` was written before the version existed: its
    tool lists are strings, and that is version 0. A snapshot that *has* a version
    this script does not know is not read at all — its fields may mean something else,
    and a report that read them would state a number it cannot stand behind.
    """
    if not isinstance(registry, dict):
        return None
    if "schemaVersion" not in registry:
        return LEGACY_REGISTRY_VERSION
    version = registry.get("schemaVersion")
    if isinstance(version, bool) or not isinstance(version, int):
        return None
    return version


def tool_names(registry, key: str):
    """The tool names in a registry list, and what could not be read out of it.

    The shape is decided by the snapshot's version:

    * version 1 — the lists are objects, `{"name": "create_task"}`, the same shape
      `describe_toolbox` reports;
    * version 0 — a legacy snapshot, and its lists are strings;
    * anything else — not read, and the report says so.

    An entry that fits neither shape of the version it claims is counted rather than
    guessed at. The old reader accepted a string or an object and moved on, and the
    price was a report that said nothing at all while looking as if it had.
    """
    if not isinstance(registry, dict):
        return [], 0, False
    version = registry_version(registry)
    if version is None or version not in (LEGACY_REGISTRY_VERSION, KNOWN_REGISTRY_VERSION):
        return [], 0, True
    values = registry.get(key)
    if not isinstance(values, list):
        return [], 0, False
    names = []
    malformed = 0
    for value in values:
        if version >= KNOWN_REGISTRY_VERSION:
            if isinstance(value, dict) and isinstance(value.get("name"), str):
                names.append(value["name"])
            else:
                malformed += 1
        elif isinstance(value, str):
            names.append(value)
        else:
            malformed += 1
    return names, malformed, False


def render(summary, registry, malformed, files_read, record_count, since_days,
           unknown_version=0, outside_window=0) -> str:
    lines = []
    window = "all records" if since_days is None else f"last {since_days} day(s)"
    lines.append(f"Toolbox usage — {record_count} record(s) from {files_read} file(s), {window}")
    if malformed:
        lines.append(f"  {malformed} malformed line(s) skipped")
    if unknown_version:
        # Named, not folded into the totals: these records are from an event schema
        # this script does not know, so their fields may mean something else.
        lines.append(
            f"  {unknown_version} record(s) of an unknown schema version skipped "
            f"(this script reads version {KNOWN_SCHEMA_VERSION})"
        )
    if outside_window:
        # The records the window excluded. The total above is the total of the window,
        # and a reader who wants to know how much was not looked at needs this number.
        lines.append(f"  {outside_window} record(s) older than the window were not counted")
    lines.append("")
    if not summary:
        # No calls at all is the case where the registry matters most: "which tools
        # does nobody use?" is asked precisely when the list of calls is empty. An early
        # return here would answer the question nobody asked.
        lines.append("No tool calls recorded.")
    else:
        lines.append(
            f"{'TOOL':<34}{'CALLS':>7}{'OK':>7}{'ERR':>6}{'ERR%':>7}{'LAST USED':>22}{'AVG':>8}"
        )
        for tool in sorted(summary):
            entry = summary[tool]
            last = entry["last"]
            last_text = time.strftime("%Y-%m-%d %H:%M", time.localtime(last / 1000)) if last else "-"
            avg = f"{entry['avgMs']}ms" if entry["avgMs"] is not None else "-"
            rate = f"{entry['errorRate']}%" if entry.get("errorRate") is not None else "-"
            lines.append(
                f"{tool:<34}{entry['calls']:>7}{entry['ok']:>7}{entry['errors']:>6}{rate:>7}"
                f"{last_text:>22}{avg:>8}",
            )

    registered_names, registry_malformed, registry_unknown = tool_names(
        registry, "registeredTools"
    )
    if registry_unknown:
        # The snapshot's version is not one this script knows. Its fields may mean
        # something else, so the tool list is not read at all — and the report says so
        # rather than reporting an empty list as "nothing is registered".
        lines.append("")
        lines.append(
            "The registry snapshot is of a version this script does not know — its tool "
            "list was not read, so 'never called' cannot be told from 'never registered'."
        )
    else:
        if registry_malformed:
            lines.append("")
            lines.append(
                f"  {registry_malformed} registry entr(y/ies) did not match the shape the "
                f"snapshot's version declares and were not read"
            )
        registered = set(registered_names)
        if registered:
            observed = {t for t in summary if t != "(session)"}
            never = sorted(registered - observed)
            if never:
                lines.append("")
                lines.append("Registered but not observed in this window:")
                for tool in never:
                    lines.append(f"  {tool}")
            stale = sorted(observed - registered)
            if stale:
                lines.append("")
                lines.append("Observed but not in the last registry snapshot:")
                for tool in stale:
                    lines.append(f"  {tool}")
        elif not registry:
            lines.append("")
            lines.append("No registry snapshot found — the server writes one at startup;")
            lines.append("without it, 'never called' cannot be told from 'never registered'.")

    # The flags that named no tool. They switched nothing off, and the operator who set
    # one believes a tool is off — so the report says which flags found no tool.
    unmatched = list(registry.get("unmatchedFlags", [])) if registry else []
    if unmatched:
        lines.append("")
        lines.append("Flags that named no tool in this build:")
        for flag in unmatched:
            lines.append(f"  {flag}")

    return "\n".join(lines)


def main(argv: list[str]) -> int:
    if click is not None:
        return _click_main(argv)
    return _argparse_main(argv)


def _click_main(argv: list[str]) -> int:
    @click.command()
    @click.option("--data-dir", type=click.Path(path_type=pathlib.Path), default=None,
                  help="Where the records live (default: ~/.singularity-todo/tool-calls).")
    @click.option("--since-days", type=int, default=None,
                  help="Only consider records from the last N days.")
    @click.option("--json", "emit_json", is_flag=True, help="Machine-readable output.")
    def cli(data_dir, since_days, emit_json):
        directory = data_dir or DEFAULT_DATA_DIR
        records, malformed, files_read, unknown_version, outside_window = read_records(
            directory, since_days
        )
        registry = read_registry(directory)
        summary = summarize(records)
        _, registry_malformed, registry_unknown = tool_names(registry, "registeredTools")
        if emit_json:
            click.echo(json.dumps({
                "dataDir": str(directory),
                "records": len(records),
                "files": files_read,
                "malformed": malformed,
                "unknownVersion": unknown_version,
                "outsideWindow": outside_window,
                "registryMalformed": registry_malformed,
                "registryUnknownVersion": registry_unknown,
                "tools": summary,
                "registry": registry,
            }, indent=2, sort_keys=True))
            return 0
        click.echo(
            render(
                summary, registry, malformed, files_read, len(records), since_days,
                unknown_version, outside_window,
            )
        )
        return 0

    return cli.main(args=argv, standalone_mode=False)


def _argparse_main(argv: list[str]) -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--data-dir", type=pathlib.Path, default=None)
    parser.add_argument("--since-days", type=int, default=None)
    parser.add_argument("--json", dest="emit_json", action="store_true")
    args = parser.parse_args(argv)
    directory = args.data_dir or DEFAULT_DATA_DIR
    records, malformed, files_read, unknown_version, outside_window = read_records(
        directory, args.since_days
    )
    registry = read_registry(directory)
    summary = summarize(records)
    _, registry_malformed, registry_unknown = tool_names(registry, "registeredTools")
    if args.emit_json:
        print(json.dumps({
            "dataDir": str(directory),
            "records": len(records),
            "files": files_read,
            "malformed": malformed,
            "unknownVersion": unknown_version,
            "outsideWindow": outside_window,
            "registryMalformed": registry_malformed,
            "registryUnknownVersion": registry_unknown,
            "tools": summary,
            "registry": registry,
        }, indent=2, sort_keys=True))
        return 0
    print(
        render(
            summary, registry, malformed, files_read, len(records), args.since_days,
            unknown_version, outside_window,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

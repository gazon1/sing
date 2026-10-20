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


def read_records(data_dir: pathlib.Path, since_days: int | None):
    """Every well-formed record in the window, plus the malformed ones it skipped."""
    cutoff = None if since_days is None else time.time() - since_days * 86400
    records = []
    malformed = 0
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
            if cutoff is not None:
                ts = record.get("ts")
                if isinstance(ts, (int, float)) and ts / 1000 < cutoff:
                    continue
            records.append(record)
    return records, malformed, files_read


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
    by_tool = defaultdict(lambda: {"calls": 0, "ok": 0, "errors": 0, "last": 0, "duration": []})
    for record in records:
        tool = record.get("tool") or "(session)"
        entry = by_tool[tool]
        entry["calls"] += 1
        outcome = record.get("outcome")
        if outcome == "ok":
            entry["ok"] += 1
        elif outcome:
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
    return dict(by_tool)


def render(summary, registry, malformed, files_read, record_count, since_days) -> str:
    lines = []
    window = "all records" if since_days is None else f"last {since_days} day(s)"
    lines.append(f"Toolbox usage — {record_count} record(s) from {files_read} file(s), {window}")
    if malformed:
        lines.append(f"  {malformed} malformed line(s) skipped")
    lines.append("")
    if not summary:
        # No calls at all is the case where the registry matters most: "which tools
        # does nobody use?" is asked precisely when the list of calls is empty. An early
        # return here would answer the question nobody asked.
        lines.append("No tool calls recorded.")
    else:
        lines.append(f"{'TOOL':<34}{'CALLS':>7}{'OK':>7}{'ERR':>6}{'LAST USED':>22}{'AVG':>8}")
        for tool in sorted(summary):
            entry = summary[tool]
            last = entry["last"]
            last_text = time.strftime("%Y-%m-%d %H:%M", time.localtime(last / 1000)) if last else "-"
            avg = f"{entry['avgMs']}ms" if entry["avgMs"] is not None else "-"
            lines.append(
                f"{tool:<34}{entry['calls']:>7}{entry['ok']:>7}{entry['errors']:>6}"
                f"{last_text:>22}{avg:>8}",
            )

    registered = set(registry.get("registeredTools", [])) if registry else set()
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
    else:
        lines.append("")
        lines.append("No registry snapshot found — the server writes one at startup;")
        lines.append("without it, 'never called' cannot be told from 'never registered'.")

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
        records, malformed, files_read = read_records(directory, since_days)
        registry = read_registry(directory)
        summary = summarize(records)
        if emit_json:
            click.echo(json.dumps({
                "dataDir": str(directory),
                "records": len(records),
                "files": files_read,
                "malformed": malformed,
                "tools": summary,
                "registry": registry,
            }, indent=2, sort_keys=True))
            return 0
        click.echo(render(summary, registry, malformed, files_read, len(records), since_days))
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
    records, malformed, files_read = read_records(directory, args.since_days)
    registry = read_registry(directory)
    summary = summarize(records)
    if args.emit_json:
        print(json.dumps({
            "dataDir": str(directory),
            "records": len(records),
            "files": files_read,
            "malformed": malformed,
            "tools": summary,
            "registry": registry,
        }, indent=2, sort_keys=True))
        return 0
    print(render(summary, registry, malformed, files_read, len(records), args.since_days))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

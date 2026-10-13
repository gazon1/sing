"""sgate.commands._parsers — shared output-parser helpers for backlog gates.

The two backlog gates parse human-readable script output into structured JSON.
Both share the same structural patterns: a first-line header, optional count
lines, blank-line separators, and ERROR blocks.

This module provides the building blocks so each gate's parser stays focused
on the field-level details.
"""
from __future__ import annotations

import re
from typing import Iterator


def parse_header(
    lines: list[str],
    start: int,
    pattern: re.Pattern[str],
) -> tuple[dict[str, str | int], int] | tuple[None, int]:
    """Parse a first-line header that contains named integer fields.

    Args:
        lines: All output lines.
        start: Index of the first non-blank line to try matching.
        pattern: A compiled regex with named groups for each field.
            Example: ``r"gate:\\s*(?P<count>\\d+)\\s+entries"``

    Returns:
        (parsed_dict, next_index) on success, or (None, start) if no match.
        The dict values are ints for named groups containing only digits,
        strings for all others.
    """
    if start >= len(lines):
        return None, start
    m = pattern.match(lines[start].strip())
    if not m:
        return None, start
    result: dict[str, str | int] = {}
    for key, val in m.groupdict().items():
        if val is not None:
            result[key] = int(val) if val.isdigit() else val
    return result, start + 1


def read_until_blank(lines: list[str], start: int) -> tuple[list[str], int]:
    """Consume lines from ``start`` up to (but not including) the first blank line.

    Returns:
        (consumed_lines, index_of_blank_line_or_end)
    """
    end = start
    while end < len(lines) and lines[end].strip():
        end += 1
    return lines[start:end], end


def skip_blanks(lines: list[str], start: int) -> int:
    """Advance ``start`` past any consecutive blank lines."""
    while start < len(lines) and not lines[start].strip():
        start += 1
    return start


def parse_error_block(lines: list[str], start: int) -> Iterator[tuple[str, int, str]]:
    """Yield (file, line, message) triples from ERROR: lines.

    Handles two formats:
      1. "ERROR: {file}:{line}  {slug}  [{code}] {message}"
      2. "ERROR: {file}:{line}  {message}"  (no slug/code)

    Yields tuples of (file, line_or_none, remainder_message).
    Stops at the first non-ERROR, non-blank line.
    """
    i = start
    while i < len(lines):
        line = lines[i].rstrip()
        if not line:
            i += 1
            continue
        if not line.startswith("ERROR:"):
            break
        rest = line[6:].strip()
        # Split on double-space to separate location from message
        parts = rest.split("  ", 1)
        location = parts[0].strip()
        message = parts[1].strip() if len(parts) > 1 else ""
        # Parse "file:line"
        file: str | None = None
        line_num: int | None = None
        loc_match = re.match(r"(.+):(\d+)", location)
        if loc_match:
            file = loc_match.group(1)
            line_num = int(loc_match.group(2))
        else:
            file = location
        yield (file, line_num, message)
        i += 1


def iter_non_blank(lines: list[str], start: int) -> Iterator[str]:
    """Yield non-blank lines starting from ``start``, stopping at the first blank."""
    for i in range(start, len(lines)):
        if lines[i].strip():
            yield lines[i]
        else:
            break

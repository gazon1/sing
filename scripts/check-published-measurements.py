#!/usr/bin/env python3
"""check-published-measurements.py — a published coverage number matches the measurement it was read from.

What went wrong
---------------
The repository publishes its coverage measurements in prose: the ADRs, the
change's tasks file, the two Gradle scripts and the Kover reader's own docstring
say things like "4735 classes over 579,002 instructions". Those numbers were
read out of the Kover XML by hand, and NOTHING compared a published number with
the artifact it was read from — so a wrong number survived a real measurement
and looked plausible:

    "2.82M instructions"

was the SUM of every `INSTRUCTION` counter in the XML — the method, class,
source-file, package and root levels together — and so five times the report's
own root counter. The percentages were unaffected, because a ratio is the same
at every level: that is exactly how a wrong denominator lived through a whole
change. A reviewer found it by hand.

The rule
--------
Every coverage measurement the repository PUBLISHES is compared with the
number it MEASURED. The measurement lives in one file —
`config/docs/coverage-measurements.properties` — and the gate reads it and the
documents that publish the numbers.

A published measurement is a CLASS COUNT, an INSTRUCTION TOTAL or a triple of
measured PERCENTAGES that a document attributes to a population. The
attribution is read out of the CLAUSE the number stands in — a sentence, split
at the em dashes that separate two claims:

  - a POPULATION NAME next to one of the nouns a measurement is about ("the
    fast population's report holds 4735 classes") names the population;
  - a DAY the record holds a measurement of ("the report of 2026-10-23 holds
    4735 classes") names it too, because the record says which population was
    measured on which day.

When a clause names both and they disagree, the number belongs to NEITHER:
the gate does not guess between two claims that contradict each other. When a
clause names neither, the number is not attributed to a population at all —
and the gate says so as a warning, because a number nobody can attribute is a
number nobody checked.

What the gate compares
----------------------
  - the class count and the instruction total — the two measures the
    documents publish as counts, and the two the wrong denominator was;
  - the three measured percentages, in the only shape that names the unit of
    each number ("55.88 instruction / 40.44 branch / 57.23 line"), and only
    when the sentence states the day the measurement was taken and that day is
    the one the record holds for that population;
  - the day itself: a count published for a day the record does not hold for
    that population is a finding, because it claims a measurement that is not
    the recorded one.

What the gate does not do
-------------------------
It does not run Gradle and it measures nothing: the record IS the
measurement, and the gate compares the published numbers with it.

It does not compare the bare shape "X / Y / Z". That shape carries the floors
("27 / 23 / 31"), older measurements ("27.73 / 23.23 / 31.26") and the current
measurement in the same prose, and the record holds one measurement per
population — so a bare triple cannot be told apart from a floor or from a
measurement of another day. The labelled shape can, and that is the shape the
gate reads.

It does not compare an abbreviated figure ("2.82M instructions"): the record
holds exact numbers, and a rounded one is not comparable with them. That is
the one shape of wrong number the gate does not see, and it is named here
rather than left implicit.

The documents it reads are the ones that publish the measurements, and the
list is in this file: `PUBLISHERS`. A file that is on the list and is not
there is a finding — a check that silently switches itself off is a check
nobody can trust.

Usage:
    python3 scripts/check-published-measurements.py            # report
    python3 scripts/check-published-measurements.py --quiet
    python3 scripts/check-published-measurements.py --root DIR  # another tree
    python3 scripts/check-published-measurements.py --json

Exit codes:
    0 — every published number matches the recorded measurement
    1 — a finding
    3 — the tree could not be read (a configuration error)
    5 — an internal error
"""

from __future__ import annotations

import ast
import json
import pathlib
import re

import click
from sgate.commands.base import (  # noqa: PLC0415 — scripts are not a package
    CLEAN,
    CONFIG_ERROR,
    INTERNAL_ERROR,
    VALIDATION_FAILURE,
    run,
)

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The record of the measurements: what was measured, per population.
RECORD_FILE = "config/docs/coverage-measurements.properties"

#: The names of the populations — the repository's own vocabulary, read from the
#: file that defines them. The record is measured per population, so the gate
#: has to know the names of the populations a record can hold; and a population
#: the record says nothing about is a measurement nobody recorded.
POPULATION_FILE = "config/test-populations.properties"

#: The documents that publish the measurements. The list is the gate's own
#: configuration, and a file on it that is missing is a finding: a check that
#: switches itself off when its input is gone is a check that reports success
#: forever.
#:
#: An archived change still publishes its measurements — the document that
#: states them is read wherever it now lives. A change that moves out of
#: `openspec/changes/` moves this path with it, and the next archive says so
#: here rather than leaving the gate quietly reading nothing.
PUBLISHERS = (
    "build.gradle.kts",
    "docs/decisions/2026-10-22-the-app-coverage-floor-lives-in-the-settings.md",
    "docs/decisions/2026-10-23-a-population-is-a-name.md",
    "openspec/changes/archive/2026-10-24-ci-fast-only-pr-full-on-schedule/tasks.md",
    "scripts/kover-markdown-table.py",
    "scripts/tests/test_kover_markdown_table.py",
    "settings.gradle.kts",
)

#: The measures the record has to hold for every population.
REQUIRED_MEASURES = (
    "classes",
    "instructions",
    "branches",
    "lines",
    "instructionCoverage",
    "branchCoverage",
    "lineCoverage",
    "measuredAt",
)

#: What a measure is called in a sentence.
MEASURE_LABELS = {
    "classes": "class count",
    "instructions": "instruction total",
    "instructionCoverage": "instruction coverage",
    "branchCoverage": "branch coverage",
    "lineCoverage": "line coverage",
}

#: The measures a percentage is published as.
PERCENTAGE_MEASURES = ("instructionCoverage", "branchCoverage", "lineCoverage")

#: The nouns a population name may stand next to in prose. A population name
#: followed by anything else is not an attribution: "fast-only" is a mode of
#: run, not the fast population.
POPULATION_NOUNS = (
    "population",
    "report",
    "run",
    "set",
    "floor",
    "measurement",
)

#: A number: plain, or with thousands separators.
_NUMBER = r"\d{1,3}(?:,\d{3})+|\d+"

#: A number that may carry a fraction — a percentage.
_DECIMAL = r"\d+(?:\.\d+)?"

#: A day the record measures on: the shape `measuredAt` has to hold.
_DAY = r"\d{4}-\d{2}-\d{2}"

#: The counts a document publishes: "N classes over M instructions", "N classes",
#: "N over M" and "M instructions". The "N over M" shape is a shape anything can
#: have, so it is read only where the sentence talks about classes or
#: instructions at all.
_COUNT_RE = re.compile(
    rf"(?P<classes>{_NUMBER})\s+classes(?:\s+over\s+(?P<instructions>{_NUMBER}))?"
    rf"|(?P<pair_classes>{_NUMBER})\s+over\s+(?P<pair_instructions>{_NUMBER})"
    rf"|(?P<alone_instructions>{_NUMBER})\s+instructions"
)

#: The percentages a document publishes, in the one shape that names the unit of
#: every number: "X instruction / Y branch / Z line".
_PERCENTAGES_RE = re.compile(
    rf"(?P<instruction>{_DECIMAL})\s+instruction\s*/\s*"
    rf"(?P<branch>{_DECIMAL})\s+branch\s*/\s*"
    rf"(?P<line>{_DECIMAL})\s+line"
)

#: The word that says a sentence talks about the report's classes at all.
_REPORT_WORDS = re.compile(r"\bclass(?:es)?\b|\binstruction(?:s)?\b", re.IGNORECASE)

#: The day a sentence states.
_DAY_RE = re.compile(rf"\b(?P<day>{_DAY})\b")

#: The end of a sentence: a full stop, a question mark or an exclamation mark
#: followed by white space. A full stop between digits is a decimal point
#: ("27.73"), and one inside a word is an abbreviation ("e.g.") — neither ends a
#: sentence.
_SENTENCE_END = re.compile(r"[.!?](?=\s|$)")


def _properties(text: str) -> dict[str, tuple[str, int]]:
    """The properties of a `.properties` file, with the line each came from.

    A `#` starts a comment anywhere on the line. That is this file's own
    format, not `java.util.Properties`' — there a `#` starts a comment only at
    the beginning of a line — and it is deliberate: the record is read by
    people, and a note beside a value is worth keeping. Nothing but this gate
    reads the record; the build does not.
    """
    found: dict[str, tuple[str, int]] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            continue
        marker = line.find("#")
        if marker >= 0:
            line = line[:marker]
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        found[key.strip()] = (value.strip(), number)
    return found


def _strip_spans(line: str) -> str:
    """One line with its inline code spans blanked out.

    A blanked span keeps the length of the line, so every offset — and every
    line number — stays the one it has in the file.
    """
    out: list[str] = []
    in_span = False
    for char in line:
        if char == "`":
            in_span = not in_span
            out.append(" ")
            continue
        out.append(" " if in_span else char)
    return "".join(out)


def _strip_code(text: str) -> str:
    """The text with its code blanked out — fenced blocks and inline spans.

    The line structure is kept, so the offsets of the result are the offsets
    of the original text.
    """
    out: list[str] = []
    in_fence = False
    for line in text.split("\n"):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            out.append(" " * len(line))
            continue
        out.append(" " * len(line) if in_fence else _strip_spans(line))
    return "\n".join(out)


def _kotlin_comments(text: str) -> str:
    """A Kotlin file with everything but its line comments blanked out.

    Only the comments that stand on a line of their own are read. A trailing
    comment is code first and prose second, and the two cannot be told apart
    without a Kotlin parser: a URL in a string literal ("https://…") carries
    the same "//" a comment starts with. The prose this gate reads lives in
    whole-line comments, and those are what it reads.
    """
    out: list[str] = []
    for line in text.split("\n"):
        stripped = line.lstrip()
        if stripped.startswith("//"):
            marker = line.index("//")
            out.append(" " * marker + line[marker + 2 :])
            continue
        out.append(" " * len(line))
    return "\n".join(out)


class UnreadableDocument(Exception):
    """A document the gate cannot read — the prose of it is not extractable."""


def _python_docstrings(text: str) -> str:
    """A Python file with everything but its docstrings blanked out.

    The docstring is where a Python file publishes prose about itself, and the
    AST says exactly where it is — the code around it is not prose, and the
    numbers in it are arguments, not claims.
    """
    try:
        tree = ast.parse(text)
    except SyntaxError as exc:
        raise UnreadableDocument(str(exc)) from exc
    keep: set[int] = set()
    for node in ast.walk(tree):
        if not isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        body = getattr(node, "body", [])
        if not body:
            continue
        first = body[0]
        if not isinstance(first, ast.Expr) or not isinstance(first.value, ast.Constant):
            continue
        if not isinstance(first.value.value, str):
            continue
        keep.update(range(first.value.lineno, (first.value.end_lineno or first.value.lineno) + 1))
    out: list[str] = []
    for number, line in enumerate(text.split("\n"), start=1):
        out.append(line if number in keep else " " * len(line))
    return "\n".join(out)


def _prose(relative: str, text: str) -> str:
    """The prose of a file: what a reader reads, with the code blanked out.

    The line structure is kept, so every offset of the result is the offset of
    the original text and every finding names the line the number stands on.
    """
    if relative.endswith(".kts"):
        return _kotlin_comments(text)
    if relative.endswith(".py"):
        return _python_docstrings(text)
    return _strip_code(text)


#: A list item starts a block of its own: the items of a list are separate
#: paragraphs of prose even when no blank line stands between them.
_LIST_ITEM = re.compile(r"^\s*(?:[-*]\s+|\d+[.)]\s+)")


class Block:
    """A block of prose: its lines joined, and the line of an offset."""

    def __init__(self, first_line: int, lines: list[str]) -> None:
        self.first_line = first_line
        self.text = " ".join(lines)
        self._starts: list[int] = []
        offset = 0
        for index, line in enumerate(lines):
            if index:
                offset += 1  # the space the lines are joined with
            self._starts.append(offset)
            offset += len(line)

    def line_of(self, offset: int) -> int:
        """The 1-based line the given offset sits on."""
        line = 0
        for index, start in enumerate(self._starts):
            if start > offset:
                break
            line = index
        return self.first_line + line


def _blocks(text: str) -> list[Block]:
    """The blocks of a document: the prose between blank lines and list items.

    A blank line ends a block, and so does a list item: the items of a list
    are separate paragraphs of prose, and a measurement belongs to the item it
    stands in rather than to the whole list.
    """
    blocks: list[Block] = []
    current: list[str] = []
    first = 1
    for number, line in enumerate(text.split("\n"), start=1):
        if not line.strip():
            if current:
                blocks.append(Block(first, current))
                current = []
            continue
        if _LIST_ITEM.match(line) and current:
            blocks.append(Block(first, current))
            current = []
        if not current:
            first = number
        current.append(line)
    if current:
        blocks.append(Block(first, current))
    return blocks


def _sentences(text: str) -> list[tuple[int, int]]:
    """The sentences of a text: the (start, end) offsets of each of them.

    A sentence ends at a full stop, a question mark or an exclamation mark
    followed by white space. A full stop between digits is a decimal point
    ("27.73") and one inside a word is an abbreviation ("e.g."), and neither
    ends a sentence.
    """
    spans: list[tuple[int, int]] = []
    start = 0
    for match in _SENTENCE_END.finditer(text):
        end = match.end()
        spans.append((start, end))
        start = end
    if start < len(text):
        spans.append((start, len(text)))
    return spans


def _clauses(text: str) -> list[tuple[int, int]]:
    """The clauses of a text: the (start, end) offsets of each of them.

    A clause is a sentence split at the em dashes that separate two claims.
    The scope an attribution is read out of is the clause: a number belongs to
    the population its OWN clause names, not to the nearest mention anywhere
    in the block. That is the difference between reading a claim and guessing
    one — a sentence that says "the fast report holds 4735 classes … more
    classes than the full run held 4642" names one population in each of its
    clauses, and a block-level guess attributed one population's numbers to
    the other.
    """
    spans: list[tuple[int, int]] = []
    for sentence_start, sentence_end in _sentences(text):
        start = sentence_start
        for index in range(sentence_start, sentence_end):
            if text[index] == "—":
                spans.append((start, index))
                start = index + 1
        spans.append((start, sentence_end))
    return spans


def _mention_pattern(populations: list[str]) -> re.Pattern[str]:
    """The pattern of a population named in prose.

    The name has to stand next to one of the nouns a measurement is about
    ("the fast population's report", "the full run of 2026-10-22"), so that a
    population name in another sense — "fast-only", a tag list — is not read
    as an attribution.
    """
    names = "|".join(re.escape(name) for name in sorted(populations, key=len, reverse=True))
    nouns = "|".join(POPULATION_NOUNS)
    return re.compile(rf"\b({names})\s+(?:'s\s+)?(?:{nouns})\b", re.IGNORECASE)


class Mention:
    """A population named in a scope: a name, or a day the record holds."""

    def __init__(self, end: int, population: str | None, day: str | None) -> None:
        self.end = end
        self.population = population
        self.day = day


class Attribution:
    """What a number belongs to, and why the gate could not say."""

    def __init__(self, population: str | None, day: str | None, reason: str) -> None:
        self.population = population
        self.day = day
        self.reason = reason


def _attribution(
    offset: int,
    mentions: list[Mention],
    fallback: list[Mention],
) -> Attribution:
    """The population a number at the given offset belongs to.

    The mentions of the clause the number stands in decide it: the population
    named nearest before it, the day nearest before it, and — when the clause
    names no population at all — the population named nearest before it in the
    enclosing block. A clause that continues the previous one ("Its report
    holds 4642 classes…") is about the population the block was about. A day
    is never taken from the block, because a day belongs to the clause that
    states it.

    When the clause names a population AND a day, and the two disagree, the
    number belongs to neither: two claims that contradict each other are not
    evidence for either, and the gate says so rather than picking one.
    """
    names_before = [m for m in mentions if m.day is None and m.end <= offset]
    names_after = [m for m in mentions if m.day is None and m.end > offset]
    days_before = [m for m in mentions if m.day is not None and m.end <= offset]
    days_after = [m for m in mentions if m.day is not None and m.end > offset]

    if names_before:
        name = names_before[-1]
    elif names_after:
        name = names_after[0]
    else:
        inherited = [m for m in fallback if m.day is None and m.end <= offset]
        name = inherited[-1] if inherited else None

    if days_before:
        day = days_before[-1]
    elif days_after:
        day = days_after[0]
    else:
        day = None

    if name is None and day is None:
        return Attribution(None, None, "unattributed")
    if name is not None and day is not None:
        if day.population is None:
            # the day the record holds no measurement of: the population is the
            # one the clause names, and the day is checked against it.
            return Attribution(name.population, day.day, "named")
        if name.population != day.population:
            return Attribution(None, day.day, "contradiction")
        return Attribution(name.population, day.day, "named")
    if name is not None:
        return Attribution(name.population, None, "named")
    assert day is not None
    if day.population is None:
        return Attribution(None, day.day, "unattributed")
    return Attribution(day.population, day.day, "named")


def _is_counter_list(text: str, start: int, end: int) -> bool:
    """Whether a number is one entry of a list of counters.

    "shared:jvmTest 440 classes / 3689 tests" counts a module's test classes,
    and ":desktopApp:koverXmlReport reported 3 classes / 220 instructions / 0
    covered" counts one report's counters. Neither is the report's class
    count: a number in a slash-separated list is one of several counters of
    the same run, and the gate compares counts that stand alone.
    """
    before = text[:start].rstrip()
    after = text[end:].lstrip()
    return before.endswith("/") or after.startswith("/")


def _is_part_of_a_set(text: str, start: int) -> bool:
    """Whether a number counts a part of a set: "49 of 62 classes are slow".

    A count of a part is not the count of the whole, and the report's class
    count is the count of the whole.
    """
    return re.search(r"\d+\s+of\s+$", text[:start]) is not None


def _measurements(text: str, about_the_report: bool) -> list[tuple[str, float | int, int, int]]:
    """The measurements a text publishes: (measure, value, start, end).

    The counts come first and the percentages second, and both are read out of
    the text as it stands — a number that is one entry of a counter list, or a
    part of a set, is not a measurement of the report.
    """
    found: list[tuple[str, float | int, int, int]] = []

    for match in _COUNT_RE.finditer(text):
        groups = match.groupdict()
        match_start, match_end = match.span()
        if groups["classes"] is not None:
            numbers = [("classes", "classes")]
            if groups["instructions"] is not None:
                numbers.append(("instructions", "instructions"))
        elif groups["pair_classes"] is not None:
            if not about_the_report:
                continue
            numbers = [
                ("classes", "pair_classes"),
                ("instructions", "pair_instructions"),
            ]
        else:
            numbers = [("instructions", "alone_instructions")]

        for measure, group in numbers:
            start, end = match.span(group)
            if _is_counter_list(text, match_start, match_end):
                continue
            if _is_part_of_a_set(text, start):
                continue
            raw = groups[group]
            found.append((measure, int(raw.replace(",", "")), start, end))

    for match in _PERCENTAGES_RE.finditer(text):
        start, end = match.span()
        if _is_counter_list(text, start, end):
            continue
        for measure, group in (
            ("instructionCoverage", "instruction"),
            ("branchCoverage", "branch"),
            ("lineCoverage", "line"),
        ):
            found.append((measure, float(match.group(group)), *match.span(group)))

    return found


def _published_measurements(
    text: str,
    populations: list[str],
    days: dict[str, str | None],
) -> list[tuple[str, float | int, int, Attribution]]:
    """The measurements a document publishes.

    Every entry is (measure, value, line, attribution). The attribution says
    which population the number belongs to — and, when it belongs to none,
    why: the gate reports the difference rather than dropping the number.
    """
    mention = _mention_pattern(populations)
    names = {name.lower(): name for name in populations}
    found: list[tuple[str, float | int, int, Attribution]] = []

    for block in _blocks(text):
        block_mentions = [
            Mention(match.end(), names[match.group(1).lower()], None)
            for match in mention.finditer(block.text)
        ]
        block_mentions += [
            Mention(match.end(), days.get(match.group("day")), match.group("day"))
            for match in _DAY_RE.finditer(block.text)
        ]
        block_mentions.sort(key=lambda item: item.end)

        for start, end in _clauses(block.text):
            clause = block.text[start:end]
            about_the_report = _REPORT_WORDS.search(clause) is not None
            scope_mentions = [
                item for item in block_mentions if start <= item.end <= end
            ]
            for measure, value, number_start, _ in _measurements(
                clause, about_the_report
            ):
                attribution = _attribution(
                    start + number_start, scope_mentions, block_mentions
                )
                found.append(
                    (
                        measure,
                        value,
                        block.line_of(start + number_start),
                        attribution,
                    )
                )

    return found


def _known_populations(
    tree: pathlib.Path, record: dict[str, dict[str, tuple[str, int]]]
) -> list[str]:
    """The populations a record can hold: the repository's names, and the record's own.

    The names come from `config/test-populations.properties` — the one file
    that defines them — so that a population the record forgot is still a
    population the gate asks about. The record's own names are added, so a
    measurement of a population nobody named is still compared.
    """
    names = set(record)
    path = tree / POPULATION_FILE
    if path.is_file():
        for key in _properties(path.read_text(encoding="utf-8")):
            names.add(key)
    return sorted(names)


def _read_record(
    path: pathlib.Path,
) -> tuple[dict[str, dict[str, tuple[str, int]]], list[str]]:
    """The record: population → measure → (value, line), and what is wrong with it."""
    findings: list[str] = []
    record: dict[str, dict[str, tuple[str, int]]] = {}
    properties = _properties(path.read_text(encoding="utf-8"))

    for key, (value, number) in properties.items():
        population, _, measure = key.partition(".")
        if not measure:
            continue
        record.setdefault(population, {})[measure] = (value, number)

    for population, measures in sorted(record.items()):
        anchor = min(line for _, line in measures.values())
        for measure in REQUIRED_MEASURES:
            if measure not in measures:
                findings.append(
                    f"{RECORD_FILE}:{anchor}: the record holds no '{population}.{measure}'. "
                    f"Every population needs a class count, an instruction total, a branch "
                    f"total, a line total, the three measured percentages and the day it was "
                    f"measured — a measurement nobody recorded cannot be compared with a "
                    f"published number."
                )
                continue
            value, number = measures[measure]
            if measure == "measuredAt":
                if not re.fullmatch(_DAY, value):
                    findings.append(
                        f"{RECORD_FILE}:{number}: '{population}.{measure}={value}' is not a "
                        f"day. The published numbers are compared with this one, and a day the "
                        f"gate cannot read is a measurement it cannot check."
                    )
                continue
            try:
                float(value.replace(",", ""))
            except ValueError:
                findings.append(
                    f"{RECORD_FILE}:{number}: '{population}.{measure}={value}' is not a "
                    f"number. The published numbers are compared with this one, and a "
                    f"number the gate cannot read is a number the gate cannot check."
                )

    return record, findings


def _same_measurement(published: float, recorded: str) -> bool:
    """Whether a published percentage is the recorded one.

    The record holds the percentages with two decimals, and a published
    figure is compared at that precision: "55.88" and "55.880" are the same
    measurement, and "55.89" is not.
    """
    try:
        return round(published, 2) == round(float(recorded), 2)
    except ValueError:
        return False


def _days_of(record: dict[str, dict[str, tuple[str, int]]]) -> dict[str, str | None]:
    """The day each population was measured on.

    A day two populations share identifies neither of them: the gate reads a
    day as the name of the population that was measured on it, and a day that
    names two is not a name.
    """
    days: dict[str, str | None] = {}
    for population, measures in record.items():
        measured = measures.get("measuredAt")
        if measured is None:
            continue
        day = measured[0]
        days[day] = None if day in days else population
    return days


def _document_findings(
    relative: str,
    text: str,
    record: dict[str, dict[str, tuple[str, int]]],
    populations: list[str],
    days: dict[str, str | None],
) -> tuple[list[str], list[str]]:
    """The findings and the warnings about the numbers one document publishes."""
    findings: list[str] = []
    warnings: list[str] = []

    for measure, value, line, attribution in _published_measurements(text, populations, days):
        population = attribution.population
        if population is None:
            if measure in ("classes", "instructions"):
                warnings.append(
                    f"{relative}:{line}: the {MEASURE_LABELS[measure]} {value} is published "
                    f"with no population the gate can attribute it to — the sentence does not "
                    f"name a population and a day it was measured on, so this number is one "
                    f"the gate cannot check."
                )
            continue

        measures = record.get(population)
        if measures is None or measure not in measures:
            findings.append(
                f"{relative}:{line}: the {population} population's {MEASURE_LABELS[measure]} is "
                f"published as {value}, and {RECORD_FILE} holds no measurement of it — it is a "
                f"measurement nobody recorded."
            )
            continue

        recorded, _ = measures[measure]
        measured_at = measures.get("measuredAt", ("", 0))[0]

        if measure in PERCENTAGE_MEASURES:
            # A percentage is compared only when the day it was measured on is
            # the day the record holds for that population. The same prose
            # carries the floors and the measurements of other days, and only
            # the day tells them apart — a percentage published for another day
            # is a measurement the record does not hold, not a wrong one.
            if attribution.day is None or attribution.day != measured_at:
                continue
            if not _same_measurement(float(value), recorded):
                findings.append(
                    f"{relative}:{line}: the {population} population's "
                    f"{MEASURE_LABELS[measure]} is published as {value}, and the measurement "
                    f"recorded in {RECORD_FILE} is {recorded} ({population}.{measure}). A "
                    f"published number that differs from the measurement it was read from is "
                    f"a claim nobody checked."
                )
            continue

        if attribution.day is not None and attribution.day != measured_at:
            findings.append(
                f"{relative}:{line}: the {population} population's "
                f"{MEASURE_LABELS[measure]} is published as measured on "
                f"{attribution.day}, and {RECORD_FILE} holds that population measured on "
                f"{measured_at} ({population}.measuredAt) — the published number is "
                f"attributed to a day the record does not hold."
            )
            continue

        try:
            expected = int(recorded.replace(",", ""))
        except ValueError:
            continue  # the record's own finding says the value is not a number
        if int(value) == expected:
            continue
        findings.append(
            f"{relative}:{line}: the {population} population's {MEASURE_LABELS[measure]} is "
            f"published as {value}, and the measurement recorded in {RECORD_FILE} is "
            f"{recorded} ({population}.{measure}). A published number that differs from the "
            f"measurement it was read from is a claim nobody checked."
        )

    return findings, warnings


def check_all(root: pathlib.Path | None = None) -> tuple[list[str], list[str]]:
    """The findings and the warnings; both empty when the tree is correct."""
    tree = pathlib.Path(root).resolve() if root else ROOT
    findings: list[str] = []
    warnings: list[str] = []

    record_path = tree / RECORD_FILE
    if not record_path.is_file():
        return (
            [
                f"{RECORD_FILE}:1: the record of the measured coverage is missing. The published "
                f"numbers are compared with this file, and without it there is nothing to compare "
                f"them with."
            ],
            [],
        )

    record, record_findings = _read_record(record_path)
    findings.extend(record_findings)
    if not record:
        findings.append(
            f"{RECORD_FILE}:1: the record holds no measurement at all, so no published number "
            f"can be compared with a measurement."
        )
        return findings, warnings

    populations = _known_populations(tree, record)
    days = _days_of(record)
    for population in populations:
        if population in record:
            continue
        findings.append(
            f"{RECORD_FILE}:1: the record holds no measurement of the {population} population. "
            f"A population the repository measures and the record says nothing about is a "
            f"measurement nobody recorded."
        )

    for relative in PUBLISHERS:
        path = tree / relative
        if not path.is_file():
            findings.append(
                f"{relative}:1: the document that publishes the measurements is missing. The "
                f"gate compares the published numbers with {RECORD_FILE}, and a document that "
                f"is not there is a check that silently stopped running."
            )
            continue
        try:
            prose = _prose(relative, path.read_text(encoding="utf-8"))
        except UnreadableDocument as exc:
            findings.append(
                f"{relative}:1: the document that publishes the measurements could not be "
                f"read ({exc}). The gate compares the published numbers with "
                f"{RECORD_FILE}, and a document it cannot read is a check that stopped "
                f"running."
            )
            continue
        document_findings, document_warnings = _document_findings(
            relative, prose, record, populations, days
        )
        findings.extend(document_findings)
        warnings.extend(document_warnings)

    return findings, warnings


def check(root: pathlib.Path | None = None) -> list[str]:
    """The findings; empty when the tree is correct."""
    findings, _ = check_all(root)
    return findings


@click.command()
@click.option("--quiet", is_flag=True, help="Print nothing when the gate is clean.")
@click.option(
    "--root",
    default=None,
    help="The tree to check (default: the repository this script lives in).",
)
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON.")
@click.pass_context
def cli(ctx: click.Context, quiet: bool, root: str | None, emit_json: bool) -> None:
    """Check that every published coverage number matches the recorded measurement."""
    tree = pathlib.Path(root).resolve() if root else ROOT
    if not tree.is_dir():
        if emit_json:
            click.echo(
                json.dumps(
                    {
                        "passed": False,
                        "blocking_errors": [f"--root {root} is not a directory."],
                        "warnings": [],
                    },
                    ensure_ascii=False,
                )
            )
        else:
            click.echo(f"ERROR: --root {root} is not a directory.")
        raise ctx.exit(CONFIG_ERROR)

    findings, warnings = check_all(tree)

    if emit_json:
        click.echo(
            json.dumps(
                {
                    "passed": not findings,
                    "blocking_errors": findings,
                    "warnings": warnings,
                },
                ensure_ascii=False,
            )
        )
        raise ctx.exit(CLEAN if not findings else VALIDATION_FAILURE)

    for finding in findings:
        click.echo(f"ERROR: {finding}")
    for warning in warnings:
        click.echo(f"WARNING: {warning}")
    if findings:
        click.echo("")
        click.echo(
            "check-published-measurements: every coverage number the repository publishes "
            f"has to be the number {RECORD_FILE} records — a published number that differs "
            f"from the measurement it was read from is a claim nobody checked."
        )
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        click.echo(
            "check-published-measurements: OK — every published class count, instruction "
            f"total and measured percentage is the one {RECORD_FILE} records."
        )
    raise ctx.exit(CLEAN)


def main(argv: list[str] | None = None) -> int:
    """The gate's entry point; the exit code the registry reads."""
    try:
        return run(cli.main, argv)
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001 — an internal error is an exit code, not a traceback
        click.echo(f"check-published-measurements: internal error: {exc}", err=True)
        return INTERNAL_ERROR


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""check-published-measurements.py — a published coverage number matches the measurement it was read from.

What went wrong
---------------
The repository publishes its coverage measurements in prose: the ADRs and the
change's tasks file say things like "4735 classes over 579,002 instructions".
Those numbers were read out of the Kover XML by hand, and NOTHING compared a
published number with the artifact it was read from — so a wrong number
survived a real measurement and looked plausible:

    "2.82M instructions"

was the SUM of every `INSTRUCTION` counter in the XML — the method, class,
source-file, package and root levels together — and so five times the report's
own root counter. The percentages were unaffected, because a ratio is the same
at every level: that is exactly how a wrong denominator lived through a whole
change. A reviewer found it by hand.

The rule
--------
Every coverage number the repository PUBLISHES is compared with the number it
MEASURED. The measurement lives in one file —
`config/docs/coverage-measurements.properties` — and the gate reads it and the
documents that publish the numbers.

A published measurement is a CLASS COUNT or an INSTRUCTION TOTAL that a
document attributes to a population: the population's name stands next to it
in the prose ("the fast population's report of 2026-10-23 holds 4735 classes
over 579,002 instructions"). The attribution is the population named nearest
to the number in the block it stands in. A number inside a code span is
not a prose attribution — a population name in a code span is a tag or a
property value, not the population a sentence is about — and a number nobody
attributes to a population is not a population's measurement: "49 of 62
classes are slow" counts a module's slow test classes, not the report's
classes.

Every such number must equal the number recorded for that population and that
measure. A number that differs is a finding, and so is a number published for
a population the record holds no measurement of — that one is a measurement
nobody recorded.

What this does not do
---------------------
It does not run Gradle and it measures nothing: the record IS the
measurement, and the gate compares the published numbers with it.

It compares the two measures the documents publish as counts — the class count
and the instruction total. The branch and line totals and the percentages are
recorded as well, and the documents publish percentages in several shapes (a
floor, an older measurement, a class-coverage figure), so comparing them
would report floors as measurements.

An abbreviated figure ("2.82M instructions") is not a measurement this gate
can read: the record holds exact numbers, and a rounded one is not
comparable with them. That is the one shape of wrong number the gate does not
see, and it is named here rather than left implicit.

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

#: The documents that publish the measurements.
PUBLISHERS = (
    "docs/decisions/2026-10-22-the-app-coverage-floor-lives-in-the-settings.md",
    "docs/decisions/2026-10-23-a-population-is-a-name.md",
    "openspec/changes/ci-fast-only-pr-full-on-schedule/tasks.md",
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

#: The measures a published number is compared with.
COMPARED_MEASURES = ("classes", "instructions")

#: What a measure is called in a sentence.
MEASURE_LABELS = {
    "classes": "class count",
    "instructions": "instruction total",
}

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

#: A published measurement, in the three shapes the documents write it:
#: "N classes over M", "N classes" and "M instructions".
_MEASUREMENT_RE = re.compile(
    rf"(?P<classes>{_NUMBER})\s+classes(?:\s+over\s+(?P<instructions>{_NUMBER}))?"
    rf"|(?P<pair_classes>{_NUMBER})\s+over\s+(?P<pair_instructions>{_NUMBER})"
    rf"|(?P<alone_instructions>{_NUMBER})\s+instructions"
)

#: The word that says a block talks about the report's classes at all — the
#: guard on the "N over M" shape, which is otherwise a shape anything can have.
_REPORT_WORDS = re.compile(r"\bclass(?:es)?\b|\binstruction(?:s)?\b", re.IGNORECASE)


def _properties(text: str) -> dict[str, tuple[str, int]]:
    """The properties of a `.properties` file, with the line each came from.

    A `#` starts a comment anywhere on the line — the same rule
    `java.util.Properties` applies, and the same rule the build reads the
    file with.
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


def _population_of(mentions: list[re.Match[str]], offset: int, names: dict[str, str]) -> str | None:
    """The population a number at the given offset belongs to.

    The nearest population named BEFORE the number wins; when the number
    stands before any of them, the nearest one after it is used. A number no
    population is near belongs to no population.
    """
    before = [match for match in mentions if match.start() < offset]
    if before:
        return names[before[-1].group(1).lower()]
    after = [match for match in mentions if match.start() > offset]
    if after:
        return names[after[0].group(1).lower()]
    return None


def _published_measurements(
    text: str, populations: list[str]
) -> list[tuple[str, int, int, str]]:
    """The measurements a document publishes: (measure, value, line, population)."""
    mention = _mention_pattern(populations)
    names = {name.lower(): name for name in populations}
    found: list[tuple[str, int, int, str]] = []

    for block in _blocks(_strip_code(text)):
        mentions = list(mention.finditer(block.text))
        about_the_report = _REPORT_WORDS.search(block.text) is not None
        for match in _MEASUREMENT_RE.finditer(block.text):
            groups = match.groupdict()
            if groups["classes"] is not None:
                numbers = [("classes", groups["classes"])]
                if groups["instructions"] is not None:
                    numbers.append(("instructions", groups["instructions"]))
            elif groups["pair_classes"] is not None:
                if not about_the_report:
                    continue
                numbers = [
                    ("classes", groups["pair_classes"]),
                    ("instructions", groups["pair_instructions"]),
                ]
            else:
                numbers = [("instructions", groups["alone_instructions"])]

            for measure, raw in numbers:
                population = _population_of(mentions, match.start(), names)
                if population is None:
                    continue  # not a population's measurement
                value = int(raw.replace(",", ""))
                found.append((measure, value, block.line_of(match.start()), population))

    return found


def _known_populations(tree: pathlib.Path, record: dict[str, dict[str, tuple[str, int]]]) -> list[str]:
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


def _read_record(path: pathlib.Path) -> tuple[dict[str, dict[str, tuple[str, int]]], list[str]]:
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


def _document_findings(
    relative: str,
    text: str,
    record: dict[str, dict[str, tuple[str, int]]],
    populations: list[str],
) -> list[str]:
    """The findings about the numbers one document publishes."""
    findings: list[str] = []
    for measure, value, line, population in _published_measurements(text, populations):
        if measure not in COMPARED_MEASURES:
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
        try:
            expected = int(recorded.replace(",", ""))
        except ValueError:
            continue  # the record's own finding says the value is not a number
        if value == expected:
            continue
        findings.append(
            f"{relative}:{line}: the {population} population's {MEASURE_LABELS[measure]} is "
            f"published as {value}, and the measurement recorded in {RECORD_FILE} is "
            f"{recorded} ({population}.{measure}). A published number that differs from the "
            f"measurement it was read from is a claim nobody checked."
        )
    return findings


def check(root: pathlib.Path | None = None) -> list[str]:
    """The findings; empty when the tree is correct."""
    tree = pathlib.Path(root).resolve() if root else ROOT
    findings: list[str] = []

    record_path = tree / RECORD_FILE
    if not record_path.is_file():
        return [
            f"{RECORD_FILE}:1: the record of the measured coverage is missing. The published "
            f"numbers are compared with this file, and without it there is nothing to compare "
            f"them with."
        ]

    record, record_findings = _read_record(record_path)
    findings.extend(record_findings)
    if not record:
        findings.append(
            f"{RECORD_FILE}:1: the record holds no measurement at all, so no published number "
            f"can be compared with a measurement."
        )
        return findings

    populations = _known_populations(tree, record)
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
            continue  # a document that publishes no number is not a finding
        findings.extend(
            _document_findings(relative, path.read_text(encoding="utf-8"), record, populations)
        )

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

    findings = check(tree)

    if emit_json:
        click.echo(
            json.dumps(
                {
                    "passed": not findings,
                    "blocking_errors": findings,
                    "warnings": [],
                },
                ensure_ascii=False,
            )
        )
        raise ctx.exit(CLEAN if not findings else VALIDATION_FAILURE)

    for finding in findings:
        click.echo(f"ERROR: {finding}")
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
            "check-published-measurements: OK — every published class count and instruction "
            f"total is the one {RECORD_FILE} records."
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

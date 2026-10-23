"""sgate.gatekit.scenarios — the semantics of a migration, as data.

The round-trip test proves the *schema*: a database of version N survives the
chain and lands on the head schema. It says nothing about the *meaning* of a
step — that `notes.task_id` is filled from the links, that `ai_proposal.task_id`
is backfilled into `target_id` before the column is dropped, that a row that
cannot satisfy a NOT NULL constraint is deleted rather than kept.

That meaning was checked by hand-written SQL, one test class per migration.
Each of those classes carried its own copy of the fixture, its own copy of the
"open the file, run the chain, read it back" dance, and a wall of SQL in which
the *setup* and the *claim* looked the same. A reader could not tell which
lines were the arrangement and which were the point.

So the meaning is written down as data — rows to plant, queries to run, values
to expect — and the test is generated from it. The scenario file is the
sentence; the generated test is the sentence, checked.

## The format

```yaml
from: 31
to: 32
scenarios:
  - name: task_id is backfilled into target_id
    seed:
      - table: ai_proposal
        row:
          id: p1
          user_id: u1
          target_id: null
      - sql: "UPDATE ai_proposal SET task_id = 't-1' WHERE id = 'p1'"
    expect:
      - sql: "SELECT target_id FROM ai_proposal WHERE id = 'p1'"
        equals: t-1
```

A `seed` entry is a row to insert (`table` + `row`), a task row (`task`), or a
statement to run (`sql`). An `expect` entry is a query and the value its first
column must hold afterwards.

## What the format deliberately cannot say

The version pair. Room applies the whole chain from the fixture's version, so a
scenario written for 31 → 32 is checked against the head schema, not against
32. `from` and `to` are the *provenance* of the scenario — which step it was
written for — and the test name says so.

ADR `docs/decisions/2026-10-22-the-gate-suite-runs-this-checkout.md`.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import yaml

#: The line length detekt enforces (`MaximumLineLength`).
MAX_LINE_LENGTH = 120

#: The number of functions detekt allows in one class (`TooManyFunctions`).
MAX_FUNCTIONS_PER_CLASS = 16


@dataclass(frozen=True)
class SqlSeed:
    """A statement, run as it is written."""

    sql: str


@dataclass(frozen=True)
class RowSeed:
    """A row to insert, written as data rather than as SQL."""

    table: str
    values: dict[str, object] = field(default_factory=dict)


@dataclass(frozen=True)
class TaskSeed:
    """A task row, planted by the fixture that knows the older schema.

    A task carries a dozen columns the schema demands and the scenario does
    not care about, and the list changes with every schema change. The fixture
    asks the database what it wants, so a scenario says which task it needs and
    which one column of it is the point.
    """

    id: str
    columns: dict[str, str] = field(default_factory=dict)


#: One thing a scenario plants.
Seed = SqlSeed | RowSeed | TaskSeed


@dataclass(frozen=True)
class Expect:
    """A query and the value its first column must hold afterwards."""

    sql: str
    equals: str


@dataclass(frozen=True)
class Scenario:
    """One claim about what a migration does to the data."""

    name: str
    seed: tuple[Seed, ...]
    expect: tuple[Expect, ...]


@dataclass(frozen=True)
class ScenarioFile:
    """The scenarios of one step, and the step they were written for."""

    from_version: int
    to_version: int
    scenarios: tuple[Scenario, ...]
    source: str = ""


def sql_value(value: object) -> str:
    """The SQL literal for a value written in YAML.

    A string is quoted, a number is written as it is, a boolean is 1 or 0, and
    null is NULL. Everything else is refused: a scenario that plants a value
    nobody can read back is a scenario that fails for a reason the reader
    cannot see.
    """
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return repr(value)
    if isinstance(value, str):
        return "'" + value.replace("'", "''") + "'"
    raise ValueError(f"a scenario value must be a scalar, got {type(value).__name__}")


def _expected_text(value: object) -> str:
    """The text the query's first column is compared with."""
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "1" if value else "0"
    return str(value)


def _seed_sql(seed: Seed) -> str:
    """The statement that plants one thing."""
    if isinstance(seed, SqlSeed):
        return seed.sql
    if isinstance(seed, RowSeed):
        columns = ", ".join(seed.values)
        values = ", ".join(sql_value(value) for value in seed.values.values())
        return f"INSERT INTO {seed.table} ({columns}) VALUES ({values})"
    raise TypeError(f"a task row is planted by the fixture, not by a statement: {seed!r}")


def _chunks(text: str, budget: int) -> list[str]:
    """The text in pieces no longer than `budget`.

    Split on spaces, so a piece is a word or a run of words. A single word
    longer than the budget stays long — a URL in a query is longer than a
    hundred characters — and the convention check catches it rather than
    silently cutting it in half.
    """
    pieces: list[str] = []
    current = ""
    for word in text.split(" "):
        candidate = f"{current} {word}" if current else word
        if len(candidate) > budget and current:
            pieces.append(current)
            current = word
        else:
            current = candidate
    if current:
        pieces.append(current)
    return pieces


def kotlin_escape(text: str) -> str:
    """The text as a Kotlin string literal holds it.

    A JSON array in a query — `["task://t1"]` — carries double quotes, and a
    double quote in a Kotlin string literal ends the literal. The backslash
    goes first, or it would escape the backslashes the other two introduce.
    """
    return text.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def _kotlin_lines(text: str, indent: int) -> list[str]:
    """The lines of a Kotlin string literal, wrapped so no line is too long.

    The first piece sits at `indent`, the continuations one level deeper —
    the shape ktlint's `indent` rule asks for when a binary expression spans
    several lines.
    """
    # The continuations sit four columns deeper than the first piece, and each
    # one pays for its quotes and the ` +` that joins it to the next.
    budget = MAX_LINE_LENGTH - indent - 10
    pieces = _chunks(kotlin_escape(text), budget)
    lines: list[str] = []
    for index, piece in enumerate(pieces):
        pad = " " * (indent if index == 0 else indent + 4)
        suffix = " +" if index < len(pieces) - 1 else ""
        lines.append(f'{pad}"{piece}"{suffix}')
    return lines


def _render_seed(seed: Seed, indent: int) -> list[str]:
    """The Kotlin that plants one thing.

    A task row is planted by the fixture — the one that knows what the older
    schema demands — and everything else is a statement.
    """
    pad = " " * indent
    if isinstance(seed, TaskSeed):
        lines = [f"{pad}MigrationSeed.LegacyTask("]
        lines.append(f'{pad}    id = "{seed.id}",')
        if not seed.columns:
            lines.append(f"{pad}    columns = mapOf(),")
        else:
            lines.append(f"{pad}    columns = mapOf(")
            for name, value in seed.columns.items():
                lines.append(
                    f'{pad}        "{name}" to "{kotlin_escape(value)}",'
                )
            lines.append(f"{pad}    ),")
        lines.append(f"{pad}),")
        return lines
    lines = [f"{pad}MigrationSeed.Sql("]
    sql_lines = _kotlin_lines(_seed_sql(seed), indent + 4)
    sql_lines[-1] = sql_lines[-1] + ","
    lines += sql_lines
    lines.append(f"{pad}),")
    return lines


def _render_expect(expect: Expect, indent: int) -> list[str]:
    """The Kotlin that checks one value."""
    pad = " " * indent
    lines = [f"{pad}MigrationExpect("]
    for line in _kotlin_lines(expect.sql, indent + 8):
        stripped = line.strip()
        lines.append(f"{pad}    sql = {stripped}" + ("," if stripped.endswith('"') else ""))
    lines.append(f'{pad}    equals = "{kotlin_escape(expect.equals)}",')
    lines.append(f"{pad}),")
    return lines


def _render_entry(scenario_file: ScenarioFile, scenario: Scenario) -> list[str]:
    """The Kotlin that declares one scenario."""
    lines = [
        "            Scenario(",
        f"                from = {scenario_file.from_version},",
        f"                to = {scenario_file.to_version},",
        f'                name = "{scenario.name}",',
        "                seed = listOf(",
    ]
    for seed in scenario.seed:
        lines += _render_seed(seed, 20)
    lines.append("                ),")
    lines.append("                expect = listOf(")
    for expect in scenario.expect:
        lines += _render_expect(expect, 20)
    lines.append("                ),")
    lines.append("            )")
    return lines


def render_scenario_test(files: list[ScenarioFile]) -> str:
    """The generated test file: one case per scenario, whatever their number.

    The cases are a parameterized test rather than one function each, because
    detekt's `TooManyFunctions` allows sixteen functions in a class and a
    repository with sixty migrations would have sixty scenarios. The round-trip
    test made the same move for the same reason.
    """
    entries: list[str] = []
    for scenario_file in files:
        for scenario in scenario_file.scenarios:
            entries.append("\n".join(_render_entry(scenario_file, scenario)))
    body = ",\n".join(entries)
    return (
        _HEADER
        + _SCENARIO_TYPE
        + _TEST.replace("__SCENARIOS__", body)
    )


def load_scenario_file(path: Path) -> ScenarioFile:
    """The scenarios declared in one YAML file."""
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    scenarios: list[Scenario] = []
    for raw in data.get("scenarios") or []:
        seed: list[Seed] = []
        for entry in raw.get("seed") or []:
            if "row" in entry:
                seed.append(RowSeed(table=entry["table"], values=dict(entry["row"])))
            elif "task" in entry:
                task = entry["task"]
                if isinstance(task, str):
                    # `task: t3` with the columns beside it — the shape a
                    # reader writes first, and the one the fixture wants.
                    seed.append(
                        TaskSeed(
                            id=task,
                            columns=dict(entry.get("columns") or {}),
                        ),
                    )
                else:
                    seed.append(
                        TaskSeed(
                            id=task["id"],
                            columns=dict(task.get("columns") or {}),
                        ),
                    )
            else:
                seed.append(SqlSeed(sql=entry["sql"]))
        expect = tuple(
            Expect(sql=entry["sql"], equals=_expected_text(entry["equals"]))
            for entry in raw.get("expect") or []
        )
        scenarios.append(Scenario(name=raw["name"], seed=tuple(seed), expect=expect))
    return ScenarioFile(
        from_version=int(data["from"]),
        to_version=int(data["to"]),
        scenarios=tuple(scenarios),
        source=path.name,
    )


def load_scenarios(directory: Path) -> list[ScenarioFile]:
    """Every scenario file in the directory, in a stable order."""
    if not directory.is_dir():
        return []
    return [load_scenario_file(path) for path in sorted(directory.glob("*.yaml"))]


def kotlin_convention_findings(source: str) -> list[str]:
    """What detekt would say about a generated Kotlin file — without Gradle.

    The thresholds are detekt's own, read from `config/detekt/detekt.yml`, and
    the check is deliberately narrow: lines that are too long, and classes with
    too many functions. Those are the two that a generator can break by
    accident, and both were found by running Gradle twice on a busy host — the
    generator wrote forty methods into one class, and detekt said
    `TooManyFunctions`; then it wrote a `@ValueSource` on one line, and ktlint
    said `MaximumLineLength`.
    """
    findings: list[str] = []
    for number, line in enumerate(source.split("\n"), start=1):
        if len(line) > MAX_LINE_LENGTH:
            findings.append(
                f"line {number} is {len(line)} characters long — the limit is {MAX_LINE_LENGTH}"
            )
    functions = sum(1 for line in source.split("\n") if line.lstrip().startswith("fun "))
    if functions > MAX_FUNCTIONS_PER_CLASS:
        findings.append(
            f"the file declares {functions} functions — the limit is "
            f"{MAX_FUNCTIONS_PER_CLASS} per class"
        )
    return findings


_HEADER = '''package com.singularity.todo.core.database

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The semantics of the migrations, generated from the scenario files.
 *
 * GENERATED FILE — do not edit. The scenarios live in the YAML files under
 * `shared/src/jvmTest/resources/migrations`, and this file is what
 * `python3 scripts/check-room-schema-integrity.py --write` writes from them.
 * Edit the scenario, not this.
 *
 * The round-trip test proves the schema. This proves the meaning: a row planted
 * before the migration, and a value that must hold after it.
 */
@Tag("slow")
@Tag("slow:migration")
class GeneratedMigrationScenarioTest {

'''

_SCENARIO_TYPE = """    private data class Scenario(
        val from: Int,
        val to: Int,
        val name: String,
        val seed: List<MigrationSeed>,
        val expect: List<MigrationExpect>,
    )

"""

_TEST = '''    @ParameterizedTest(name = "v{0} to v{1} - {2}")
    @MethodSource("scenarios")
    fun `the migration keeps its promise`(
        from: Int,
        to: Int,
        name: String,
        seed: List<MigrationSeed>,
        expect: List<MigrationExpect>,
    ) = runTest {
        runMigrationScenario(from, to, name, seed, expect)
    }

    companion object {
        private val SCENARIOS: List<Scenario> = listOf(
__SCENARIOS__,
        )

        @JvmStatic
        fun scenarios(): List<Arguments> = SCENARIOS.map {
            Arguments.of(it.from, it.to, it.name, it.seed, it.expect)
        }
    }
}
'''

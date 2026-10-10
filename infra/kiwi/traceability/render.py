"""Markdown rendering for both matrices.

Pure functions of their input. Both files carry a "generated — do not edit"
banner, because a generated artefact that a reader might reasonably hand-edit is
a generated artefact that will be hand-edited and then silently reverted by the
next CI run.

The coverage table is committed and CI-checked byte-for-byte, so the rendering is
deterministic: rows sorted by id, targets in a fixed order, no timestamps. A
timestamp would make every regeneration a diff.
"""

from __future__ import annotations

from traceability.coverage import ALL_TARGETS, Coverage, Outcome, ResultMatrix
from traceability.spec import ScenarioSpec

__all__ = ["render_coverage_matrix", "render_result_matrix", "GENERATED_BANNER"]

GENERATED_BANNER = (
    "<!-- GENERATED — do not edit by hand. -->\n"
    "<!-- Source: infra/kiwi/scenarios/** + @DisplayName / scenario: tags in code. -->\n"
    "<!-- Regenerate: just trace-coverage   Verify: just trace-coverage-check -->"
)

_HEADER = "| Scenario | Title | " + " | ".join(t.value for t in ALL_TARGETS) + " |"
_DIVIDER = "|---|---|" + "---|" * len(ALL_TARGETS)

#: The coverage table carries one more column than the result table, because a
#: coverage question is "what is unverified" and a result question is "what
#: happened" — the same glyphs answer different questions in each. Built by
#: concatenation rather than by slicing `_HEADER`, because slicing a Markdown
#: header silently eats the column separator and produces a header with one
#: cell fewer than the rows below it, which renders as data with no error.
_COVERAGE_HEADER = (
    "| Scenario | Title | " + " | ".join(t.value for t in ALL_TARGETS) + " | What we verify |"
)
_COVERAGE_DIVIDER = "|---|---|" + "---|" * (len(ALL_TARGETS) + 1)

#: How much of `expected` fits a table cell before it stops being scannable.
#: The full text is a click away in the details section; the point of the column
#: is to make a row meaningful in a diff, not to replace reading the spec.
_EXPECTED_CELL_LIMIT = 120


def _cell(text: str, limit: int = _EXPECTED_CELL_LIMIT) -> str:
    """A one-line table cell, truncated with an ellipsis that says so."""
    inline = _inline(text)
    if len(inline) <= limit:
        return inline
    cut = inline[: limit - 1].rsplit(" ", 1)[0]
    return f"{cut}…"


def _legend_coverage() -> list[str]:
    return [
        "| Glyph | Meaning |",
        "|---|---|",
        "| — | target not claimed for this scenario |",
        "| ○ | claimed, but no automated test exists — **a hole** |",
        "| ◇ | claimed, no test, and **no automated carrier can reach that tier** |",
        "| ● | automated |",
        "| ⊘ | scenario is deprecated — retired deliberately, not an obligation |",
    ]


def _legend_results() -> list[str]:
    return [
        "| Glyph | Meaning |",
        "|---|---|",
        "| ✅ | passed |",
        "| ❌ | failed |",
        "| ⏭ | skipped |",
        "| ⌛ | claimed, but no result at this commit |",
        "| — | target not claimed |",
    ]


#: Why an Android column can be empty on a run that did ask for it. Stated in
#: the artefact itself rather than left to a reader who assumes the flows are
#: un-automated.
_ANDROID_CAVEAT = (
    "The Android flows run in `e2e.yml`, a different workflow from the "
    "one that builds this matrix, so their JUnit XML never reaches it. A `⌛` in "
    "that column means \"not filled here\", not \"not automated\" — the flows are "
    "tagged and run, and `Maestro/flows/**` is the source for the coverage matrix."
)


def _ran_targets(matrix: ResultMatrix) -> list[str]:
    """Targets this run produced a result for.

    A target that reported nothing is indistinguishable, from the matrix alone,
    between "ran and produced nothing" (an error elsewhere) and "was not part of
    this run". Listing the ones that did report makes the remaining column cells
    read correctly.
    """
    reported = {
        target
        for row in matrix.cells.values()
        for target, cell in row.items()
        if cell.outcome is not Outcome.NOT_RUN
    }
    return [t.value for t in ALL_TARGETS if t in reported]


def _scope_line(ran: list[str]) -> str:
    missing = [t.value for t in ALL_TARGETS if t.value not in ran]
    if not ran:
        return (
            "**No target reported a result in this run** — every cell below is "
            "`⌛` or `—` by construction, not by outcome."
        )
    scope = f"**Reported here:** {', '.join(ran)}."
    if "android" in missing:
        return f"{scope} android did not report. {_ANDROID_CAVEAT}"
    if missing:
        return f"{scope} {', '.join(missing)} did not report in this run."
    return f"{scope} Every target reported."


def render_coverage_matrix(coverage: Coverage) -> str:
    """The committed coverage view, grouped by area.

    Grouped because ``feature.tasks`` is the taxonomy a reader navigates by;
    a flat alphabetical list of ids would hide the structure the specs encode.

    Carries the *expected result* of every scenario, not just its title. A
    table of ``id | title | ● | ○`` answers "how much", which was never the
    question; #156's acceptance criterion is that a reader uses this to decide
    what to test next, and nobody can decide that from four glyphs and a title.
    With 32 holes the reader's first question is "what is unverified, exactly",
    and that is prose — which the specs already carry and this file was not
    printing.
    """
    lines: list[str] = [
        GENERATED_BANNER,
        "",
        "# Coverage matrix",
        "",
        "Derived from `infra/kiwi/scenarios/**` and the `@DisplayName` / `scenario:`",
        "tags in code. It changes only when code or specs change.",
        "",
        "Full steps and preconditions are in the [scenario details](#scenario-details)",
        "below; the table carries the one-line version so a row is readable in a diff.",
        "",
        *_legend_coverage(),
        "",
    ]

    # Deprecated cells are excluded from the ratio, not just from the hole
    # count. Leaving them in produced "0/2 claimed cells automated · 0 holes",
    # which is a contradiction a reader has to resolve by guessing: the only way
    # to read it is "half the obligations are unmet, and none of them is a
    # hole". A retired scenario is not an obligation, so it is not a denominator.
    live = [
        c
        for row in coverage.cells.values()
        for c in row.values()
        if c.state.is_obligation
    ]
    total_cells = len(live)
    automated_cells = sum(1 for c in live if c.state.is_automated)
    holes = coverage.holes()
    lines += [
        f"**{len(coverage.cells)} scenarios · {automated_cells}/{total_cells} claimed cells automated "
        f"· {len(holes)} holes**",
        "",
    ]

    for area, scenario_ids in coverage.areas.items():
        lines += [f"## {area}", "", _COVERAGE_HEADER, _COVERAGE_DIVIDER]
        for scenario_id in scenario_ids:
            spec: ScenarioSpec = coverage.specs[scenario_id]
            glyphs = " | ".join(coverage.glyph(scenario_id, target) for target in ALL_TARGETS)
            lines.append(
                f"| `{scenario_id}` | {spec.title} | {glyphs} | {_cell(spec.expected)} |"
            )
        lines.append("")

    if holes:
        lines += ["## Holes", "", "Claimed but not automated:", ""]
        lines += [f"- `{scenario_id}` / {target.value}" for scenario_id, target in holes]
        lines.append("")

    lines += _render_details(coverage)

    return "\n".join(lines).rstrip() + "\n"


def _render_details(coverage: Coverage) -> list[str]:
    """Preconditions, steps and expected result, per scenario.

    Deterministic, and the reason it is safe to commit: the matrix is compared
    byte-for-byte in CI, so anything time-, order- or environment-derived here
    would be a diff on every run. Everything here comes from the spec files.
    """
    lines = ["## Scenario details", ""]
    for area, scenario_ids in coverage.areas.items():
        lines += [f"### {area}", ""]
        for scenario_id in scenario_ids:
            spec: ScenarioSpec = coverage.specs[scenario_id]
            status = f"**{spec.status.value}** · {spec.priority} · `#{spec.id_prefix}`"
            lines.append(f"#### `{scenario_id}` — {spec.title}")
            lines.append("")
            lines.append(f"{status}")
            lines.append("")
            if spec.preconditions:
                lines += [_paragraph("Given", spec.preconditions), ""]
            if spec.steps:
                lines.append("Steps:")
                lines.append("")
                lines += [f"{i}. {_inline(step)}" for i, step in enumerate(spec.steps, 1)]
                lines.append("")
            lines += [_paragraph("Expected", spec.expected), ""]
    return lines


def _paragraph(label: str, text: str) -> str:
    return f"**{label}:** {_inline(text)}"


def _inline(text: str) -> str:
    """One line, no pipes — safe in a table cell and stable in a diff.

    A `|` in a table cell silently starts a new column, and the specs are
    prose written by hand: a scenario about a filter by project would otherwise
    render a table with one column too many and no error anywhere.
    """
    collapsed = " ".join(text.split())
    return collapsed.replace("|", "\\|")


def render_result_matrix(matrix: ResultMatrix) -> str:
    """The commit-scoped result view — a CI artifact, never committed.

    Names the commit in its header because it describes exactly one identified
    commit, and a result matrix that does not say which one is a riddle.

    Also names the targets that actually ran. A permanent ``⌛`` in the Android
    column otherwise reads as "not automated", which is a different and wrong
    claim: the flows are automated and tagged, they are executed in a different
    workflow from the one that builds this matrix, and their JUnit XML is
    discarded. Saying so in the header is the honest minimum — the alternative
    was chosen over adding an emulator to the main pipeline.
    """
    coverage = matrix.coverage
    ran = _ran_targets(matrix)
    lines: list[str] = [
        f"<!-- GENERATED — CI artifact for commit {matrix.commit}. Do not commit. -->",
        "",
        "# Result matrix",
        "",
        f"**Commit `{matrix.commit}`** — this table describes this commit only.",
        f"Testcases kept: {matrix.kept}, dropped as unlinked: {matrix.dropped}, "
        f"flow results that matched no scenario: {matrix.unmapped}.",
        "",
        _scope_line(ran),
        "",
        *_legend_results(),
        "",
    ]

    for area, scenario_ids in coverage.areas.items():
        lines += [f"## {area}", "", _HEADER, _DIVIDER]
        for scenario_id in scenario_ids:
            spec = coverage.specs[scenario_id]
            glyphs = " | ".join(matrix.glyph(scenario_id, target) for target in ALL_TARGETS)
            lines.append(f"| `{scenario_id}` | {spec.title} | {glyphs} |")
        lines.append("")

    failures = [
        (scenario_id, target)
        for scenario_id, row in matrix.cells.items()
        for target, cell in row.items()
        if cell.outcome in (Outcome.FAILED, Outcome.MISSING)
    ]
    if failures:
        lines += ["## Failures", ""]
        lines += [f"- `{scenario_id}` / {target.value}" for scenario_id, target in sorted(failures)]
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"

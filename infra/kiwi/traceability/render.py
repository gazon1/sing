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

from traceability.coverage import ALL_TARGETS, Coverage, ResultMatrix
from traceability.spec import ScenarioSpec

__all__ = ["render_coverage_matrix", "render_result_matrix", "GENERATED_BANNER"]

GENERATED_BANNER = (
    "<!-- GENERATED — do not edit by hand. -->\n"
    "<!-- Source: infra/kiwi/scenarios/** + @DisplayName / scenario: tags in code. -->\n"
    "<!-- Regenerate: just trace-coverage   Verify: just trace-coverage-check -->"
)

_HEADER = "| Scenario | Title | " + " | ".join(t.value for t in ALL_TARGETS) + " |"
_DIVIDER = "|---|---|" + "---|" * len(ALL_TARGETS)


def _legend_coverage() -> list[str]:
    return [
        "| Glyph | Meaning |",
        "|---|---|",
        "| — | target not claimed for this scenario |",
        "| ○ | claimed, but no automated test exists — **a hole** |",
        "| ● | automated |",
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


def render_coverage_matrix(coverage: Coverage) -> str:
    """The committed coverage view, grouped by area.

    Grouped because ``feature.tasks`` is the taxonomy a reader navigates by;
    a flat alphabetical list of ids would hide the structure the specs encode.
    """
    lines: list[str] = [
        GENERATED_BANNER,
        "",
        "# Coverage matrix",
        "",
        "Derived from `infra/kiwi/scenarios/**` and the `@DisplayName` / `scenario:`",
        "tags in code. It changes only when code or specs change.",
        "",
        *_legend_coverage(),
        "",
    ]

    total_cells = sum(1 for row in coverage.cells.values() for c in row.values() if c.claimed)
    automated_cells = sum(1 for row in coverage.cells.values() for c in row.values() if c.claimed and c.automated)
    holes = coverage.holes()
    lines += [
        f"**{len(coverage.cells)} scenarios · {automated_cells}/{total_cells} claimed cells automated "
        f"· {len(holes)} holes**",
        "",
    ]

    for area, scenario_ids in coverage.areas.items():
        lines += [f"## {area}", "", _HEADER, _DIVIDER]
        for scenario_id in scenario_ids:
            spec: ScenarioSpec = coverage.specs[scenario_id]
            glyphs = " | ".join(coverage.glyph(scenario_id, target) for target in ALL_TARGETS)
            lines.append(f"| `{scenario_id}` | {spec.title} | {glyphs} |")
        lines.append("")

    if holes:
        lines += ["## Holes", "", "Claimed but not automated:", ""]
        lines += [f"- `{scenario_id}` / {target.value}" for scenario_id, target in holes]
        lines.append("")
    else:
        lines += ["No holes: every claimed target has automation.", ""]

    return "\n".join(lines).rstrip() + "\n"


def render_result_matrix(matrix: ResultMatrix) -> str:
    """The commit-scoped result view — a CI artifact, never committed.

    Names the commit in its header because it describes exactly one identified
    commit, and a result matrix that does not say which one is a riddle.
    """
    coverage = matrix.coverage
    lines: list[str] = [
        f"<!-- GENERATED — CI artifact for commit {matrix.commit}. Do not commit. -->",
        "",
        "# Result matrix",
        "",
        f"**Commit `{matrix.commit}`** — this table describes this commit only.",
        f"Testcases kept: {matrix.kept}, dropped as unlinked: {matrix.dropped}, "
        f"flow results that matched no scenario: {matrix.unmapped}.",
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
        if cell.outcome.value in ("failed",)
    ]
    if failures:
        lines += ["## Failures", ""]
        lines += [f"- `{scenario_id}` / {target.value}" for scenario_id, target in sorted(failures)]
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"

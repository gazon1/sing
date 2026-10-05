"""The two matrices, both derived — neither is a hand-maintained table.

Coverage is **committed**: it is a deterministic function of the specs and the
links, it changes only when code or specs change, and CI regenerates it and
fails if the committed copy differs. That makes it branch-mergeable by
construction.

The result matrix is the opposite and deliberately so: it describes *one
identified commit*, it is written under ``build/``, uploaded as an artifact and
rendered into the job summary, and it is never committed. A committed file
mixing "who claimed this" with "what passed on abc1234" goes stale at merge
time and conflicts across branches. Per-run history (``PASS FAIL PASS``) stays
queryable in Kiwi, which is where history belongs; the committed artefact is a
current snapshot or it is nothing.

Coverage is three-valued because "claimed" and "automated" are different facts,
and collapsing them into a boolean hides precisely the hole worth seeing.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum

from traceability import ValidationError
from traceability.keys import TestKey
from traceability.links import Link
from traceability.spec import ScenarioSpec, Target

__all__ = ["Coverage", "CoverageCell", "ResultCell", "ResultMatrix", "build_coverage", "build_results", "Outcome"]

#: Every target the matrices iterate over, in a fixed order so a regenerated
#: file is byte-identical when nothing changed.
ALL_TARGETS: tuple[Target, ...] = (Target.ANDROID, Target.DESKTOP)


class Outcome(StrEnum):
    """What happened to a scenario on a target at a named commit.

    ``not-run`` is a first-class outcome, not an absence: a claimed target with
    no result at this commit is a fact the matrix must state, because a target
    that silently produced nothing is exactly the "quietly green" failure this
    system exists to catch.
    """

    PASSED = "passed"
    FAILED = "failed"
    SKIPPED = "skipped"
    NOT_RUN = "not-run"


@dataclass(frozen=True, slots=True)
class CoverageCell:
    """Coverage of one (scenario, target) pair.

    ``automated`` is ``None`` when the target is not claimed, which is
    different from "claimed and nothing exists": the first is not an obligation,
    the second is a hole.
    """

    claimed: bool
    automated: bool

    @property
    def glyph(self) -> str:
        if not self.claimed:
            return "—"
        return "●" if self.automated else "○"


@dataclass(frozen=True, slots=True)
class Coverage:
    """Coverage for every scenario, grouped by area for a readable table."""

    cells: dict[str, dict[Target, CoverageCell]]
    specs: dict[str, ScenarioSpec]
    areas: dict[str, list[str]] = field(default_factory=dict)

    def glyph(self, scenario: str, target: Target) -> str:
        return self.cells[scenario][target].glyph

    def holes(self) -> list[tuple[str, Target]]:
        """Claimed-but-not-automated pairs — the actionable output."""
        return [
            (scenario, target)
            for scenario, row in sorted(self.cells.items())
            for target, cell in row.items()
            if cell.claimed and not cell.automated
        ]


def build_coverage(specs: dict[str, ScenarioSpec], links: list[Link]) -> Coverage:
    """Deterministic coverage: claimed targets × whether automation exists.

    Pure. Depends on the specs and the links and on nothing else — no clock, no
    filesystem, no stand — which is what makes the committed matrix checkable.
    """
    automated: set[tuple[str, Target]] = {(link.scenario, link.target) for link in links}
    cells: dict[str, dict[Target, CoverageCell]] = {}
    areas: dict[str, list[str]] = {}
    for scenario_id, spec in sorted(specs.items()):
        cells[scenario_id] = {
            target: CoverageCell(
                claimed=target in spec.targets,
                automated=(scenario_id, target) in automated,
            )
            for target in ALL_TARGETS
        }
        areas.setdefault(spec.area, []).append(scenario_id)
    return Coverage(cells=cells, specs=specs, areas={k: sorted(v) for k, v in sorted(areas.items())})


@dataclass(frozen=True, slots=True)
class ResultCell:
    """Outcome of one (scenario, target) pair at a named commit."""

    outcome: Outcome
    detail: str = ""

    @property
    def glyph(self) -> str:
        return {
            Outcome.PASSED: "✅",
            Outcome.FAILED: "❌",
            Outcome.SKIPPED: "⏭",
            Outcome.NOT_RUN: "⌛",
        }[self.outcome]


@dataclass(frozen=True, slots=True)
class ResultMatrix:
    """The commit-scoped result view."""

    cells: dict[str, dict[Target, ResultCell]]
    coverage: Coverage
    commit: str
    #: Normalisation counters, carried into the rendered header. They exist so
    #: a surprise in Kiwi has a cheap first question: how many testcases were
    #: kept, how many dropped as unlinked, how many flow results matched nothing.
    kept: int = 0
    dropped: int = 0
    unmapped: int = 0

    def glyph(self, scenario: str, target: Target) -> str:
        return self.cells.get(scenario, {}).get(target, ResultCell(Outcome.NOT_RUN)).glyph


def build_results(
    coverage: Coverage,
    results: list[tuple[Link, str, str]],
    commit: str,
    *,
    kept: int = 0,
    dropped: int = 0,
    unmapped: int = 0,
) -> ResultMatrix:
    """Turn normalised (link, outcome, detail) triples into the matrix.

    Pure. Coverage supplies the shape — a scenario absent from the results is
    ``not-run`` for its claimed targets, and not claimed for the rest, so the
    matrix can always render a complete grid.

    Two results for the same (scenario, target) is an error, raised by the
    normaliser before it gets here; the scan-wins shape below is a belt-and-
    braces guard so a future caller cannot silently keep the last one.
    """
    cells: dict[str, dict[Target, ResultCell]] = {}
    for scenario_id, row in coverage.cells.items():
        cells[scenario_id] = {}
        for target, cell in row.items():
            if not cell.claimed:
                continue
            cells[scenario_id][target] = ResultCell(Outcome.NOT_RUN)
    for link, outcome, detail in results:
        cells.setdefault(link.scenario, {})[link.target] = ResultCell(Outcome(outcome), detail)
    return ResultMatrix(
        cells=cells,
        coverage=coverage,
        commit=commit,
        kept=kept,
        dropped=dropped,
        unmapped=unmapped,
    )


def link_index(links: list[Link]) -> dict[TestKey | Path, Link]:
    """Both join keys for a link set, for the normaliser's lookup.

    Kotlin joins on the normalised ``TestKey`` — the display name first, the
    method name second. A Maestro flow joins on its path, because its JUnit
    ``classname`` is reporter-derived and not stable enough to rely on.
    """
    index: dict[TestKey | Path, Link] = {}
    for link in links:
        if link.key is not None:
            index[link.key] = link
        # Second entry for the method-name form. The primary key is the display
        # name because that is what JUnit writes; this one covers a build that
        # reports method names, and it costs nothing to carry.
        if link.fallback_key is not None and link.fallback_key not in index:
            index[link.fallback_key] = link
        # A source file indexed twice is ambiguous, and assigning over the first
        # entry is how a scenario silently loses its result: the row joins to
        # whichever link was indexed last, the other renders as not-run, and no
        # counter moves. The scanner already rejects a flow claiming two
        # scenarios; this is the backstop for a caller that builds links itself.
        resolved = link.source.resolve()
        previous = index.get(resolved)
        if previous is not None and previous is not link:
            raise ValidationError(
                f"два сценария заявлены одним файлом {link.source}: "
                f"'{previous.scenario}' и '{link.scenario}' — результат прогона "
                f"невозможно отнести к одному из них"
            )
        index[resolved] = link
    return index

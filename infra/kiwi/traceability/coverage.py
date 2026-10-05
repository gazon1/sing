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


class CellState(StrEnum):
    """The five things a (scenario, target) cell can be.

    These were four independent booleans, which is a shape that lets a caller
    say something the matrix has no rendering for: `automated and not claimed`
    is unsatisfiable, and `not claimed and not automated and deprecated` was
    reachable and meant "never claimed, but retired" — a row nobody had ever
    declared. Nothing rejected it. The glyph then fell through its own cascade
    and printed `—`, so the impossible cell was indistinguishable from an
    ordinary unclaimed one.

    One value with a fixed precedence makes the invalid combinations
    unrepresentable rather than merely discouraged, and puts the precedence
    where it can be read: `classify` below.
    """

    #: The scenario never named this target. Not an obligation.
    UNCLAIMED = "unclaimed"
    #: The scenario was retired. Not an obligation, but a decision — it must not
    #: read as "nobody ever thought about this platform".
    RETIRED = "retired"
    #: Claimed and supplied by a carrier.
    AUTOMATED = "automated"
    #: Claimed, unsupplied, and reachable by an automated carrier if one existed.
    HOLE = "hole"
    #: Claimed, unsupplied, and *not reachable by any automated carrier on this
    #: tier* — a second device, a network fault injector, a device farm, a human.
    #:
    #: A fifth state rather than a flavour of `HOLE` because a hole was
    #: ambiguous and both readings got acted on. `TASK-TIME-01` was narrowed to
    #: android because a reachability probe failed; the feature was in
    #: `commonMain` and the desktop screen had silently stopped rendering it.
    #: `SYNC-OFFLINE-01` claims both targets and needs a second device and a
    #: flapping network. Both drew as `○`, so each mistake looked like the other
    #: one's remedy.
    #:
    #: It is still a hole and still counted as one — otherwise the cheap move is
    #: to reclassify every unsupplied claim as unreachable. What changes is that
    #: the supply is *named*: writing a Compose test is not what this cell needs,
    #: and the report says so.
    UNREACHABLE = "unreachable"

    @property
    def glyph(self) -> str:
        return _GLYPHS[self]

    @property
    def is_claimed(self) -> bool:
        """The scenario named this target, whether or not it still should.

        ``RETIRED`` counts: a retired scenario keeps the targets it *had*, so the
        row still shows which platforms it used to cover. What retirement removes
        is the obligation, not the record.
        """
        return self is not CellState.UNCLAIMED

    @property
    def is_obligation(self) -> bool:
        """Claimed, and still owed. A retired scenario is owed nothing."""
        return self in (CellState.AUTOMATED, CellState.HOLE, CellState.UNREACHABLE)

    @property
    def is_automated(self) -> bool:
        return self is CellState.AUTOMATED

    @property
    def is_hole(self) -> bool:
        return self in (CellState.HOLE, CellState.UNREACHABLE)


_GLYPHS: dict[CellState, str] = {
    CellState.UNCLAIMED: "—",
    CellState.RETIRED: "⊘",
    CellState.AUTOMATED: "●",
    CellState.HOLE: "○",
    CellState.UNREACHABLE: "◇",
}


def classify(*, claimed: bool, automated: bool, deprecated: bool, reachable: bool) -> CellState:
    """Fold the four facts a cell is built from into one state.

    The precedence is the whole point of this function, so it is written once and
    tested rather than re-derived at each call site:

    1. Retirement outranks everything. A retired scenario that still had a
       carrier keeps showing the carrier's reach on the platforms it used, but it
       is reported as retired — otherwise a scenario retired *after* being
       automated would keep drawing `●` forever and never leave the matrix.
    2. An unclaimed target is never automated and never a hole; the claim is the
       whole question.
    3. Reachable-versus-not only distinguishes two kinds of hole. A claim that is
       unsupplied and reachable is still an obligation; the question is whether
       an automated carrier could discharge it.
    """
    if deprecated:
        return CellState.RETIRED
    if not claimed:
        return CellState.UNCLAIMED
    if automated:
        return CellState.AUTOMATED
    return CellState.HOLE if reachable else CellState.UNREACHABLE


@dataclass(frozen=True, slots=True)
class CoverageCell:
    """Coverage of one (scenario, target) pair.

    Carries one state rather than a set of facts, because every question asked
    of a cell — is this owed, is this a gap, what does it draw as — is a property
    of the cell as a whole. The facts it is built from are consumed once, by
    `classify`.
    """

    state: CellState

    @property
    def glyph(self) -> str:
        return self.state.glyph


@dataclass(frozen=True, slots=True)
class Coverage:
    """Coverage for every scenario, grouped by area for a readable table."""

    cells: dict[str, dict[Target, CoverageCell]]
    specs: dict[str, ScenarioSpec]
    areas: dict[str, list[str]] = field(default_factory=dict)

    def glyph(self, scenario: str, target: Target) -> str:
        return self.cells[scenario][target].glyph

    def holes(self) -> list[tuple[str, Target]]:
        """Claimed-but-not-automated pairs — the actionable output.

        Unreachable cells are included on purpose. They are unsupplied claims and
        the ratchet must see them; what `unreachable_holes` adds is the
        classification, not a smaller total.
        """
        return [
            (scenario, target)
            for scenario, row in sorted(self.cells.items())
            for target, cell in row.items()
            if cell.state.is_hole
        ]

    def unreachable_holes(self) -> list[tuple[str, Target]]:
        """The subset of `holes` no automated carrier can reach on that tier."""
        return [
            (scenario, target)
            for scenario, row in sorted(self.cells.items())
            for target, cell in row.items()
            if cell.state is CellState.UNREACHABLE
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
                # A deprecated scenario keeps the targets it *had*, so the row
                # still shows which platforms it used to cover. It is the
                # `RETIRED` state that stops it being read as an obligation.
                state=classify(
                    claimed=target in spec.targets,
                    automated=(scenario_id, target) in automated,
                    deprecated=not spec.is_claimed,
                    # Read from the spec, never inferred from a failed probe: the
                    # probe measures the code in front of it, and a node missing
                    # from one screen is a hole in the code until proven
                    # otherwise.
                    reachable=target not in spec.unreachable,
                )
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
            if not cell.state.is_claimed:
                # `is_claimed`, not `is_obligation`: a retired scenario still
                # produced a row here, showing the targets it used to cover as
                # not-run. Dropping it would have changed the result matrix.
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

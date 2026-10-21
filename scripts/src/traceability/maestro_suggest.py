"""Propose ``scenario:`` tags for Maestro flows that carry none.

Why
---
This repository has 77 Maestro flows and 3 scenario tags. The matrix is built
from tags, so it reports 74 flows of existing automation as coverage holes —
which is not just an under-report, it is a false statement: a reader of
``docs/testing/coverage-matrix.md`` concludes that behaviour nobody wrote is
untested when in fact a runnable flow sits in ``Maestro/flows`` the whole time.

Nothing in the repo detected this. ``scan_maestro`` reports the links it finds;
it has no opinion about the flows it did not find, because a flow with no tag is
indistinguishable from a flow that belongs to no scenario.

Why this module only proposes, and never writes
------------------------------------------------
A tag is an assertion about behaviour: "this flow verifies that scenario". It
cannot be derived from a filename. ``pomodoro/03-pause-resume.yaml`` *sounds*
like it would carry a pause/resume scenario, but whether it asserts the timer
holds its remaining time across a configuration change is a question about what
the flow does, and only a reader of the flow can answer it.

An auto-tagger that guesses would turn a false hole into a false green, which is
strictly worse than the hole: the hole is visible and arguable, the green is
invisible and trusted. So this module prints candidates with the evidence next
to them and stops. Applying a tag stays a human decision.

The output is a worklist, not a report — it is regenerated on demand and never
committed, because a committed proposal file is a second thing to keep in sync
and would itself go stale the moment a flow changed.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import yaml

from scanners.maestro_flow import flow_tags_text
from traceability import REPO_ROOT, ValidationError
from traceability.links import SCENARIO_TAG_PREFIX
from traceability.spec import ID_PATTERN, ScenarioSpec

__all__ = ["FlowProposal", "load_opt_outs", "propose", "render_report"]

#: Where a human records "this flow is not a scenario". A plain text file rather
#: than a field in the flow, because a flow is executed by Maestro and read by
#: tests; adding a comment key that only one tool reads invites the next tool to
#: ignore it. One file, one format, edited with the rest of the configuration.
OPT_OUT_PATH = REPO_ROOT / "config" / "docs" / "maestro-not-scenarios.txt"

#: Words that carry no signal about which scenario a flow belongs to. Kept
#: small on purpose — an aggressive stopword list turns a real signal into noise
#: and the point of the module is to be readable at a glance.
#:
#: "flow" and "tab" are here because Maestro paths and `name:` values are full of
#: them (`01-open-tab`, `bottom-nav-tabs`), so they discriminate nothing between
#: scenarios. "view" is here for the same reason, and it is a real cost: it
#: means `agenda/02-saved-views-open` cannot match on the word "view" alone, so
#: that row lands in the low band and asks for a human rather than guessing.
_STOPWORDS = frozenset(
    {
        "a", "an", "and", "the", "to", "of", "in", "on", "for", "with", "is",
        "flow", "tab", "screen", "view", "detail", "open", "ok",
    }
)

_SPLIT = re.compile(r"[^a-z0-9]+")
_LEADING_NUMBER = re.compile(r"^\d+[-_]")


def load_opt_outs(path: Path = OPT_OUT_PATH) -> dict[str, str]:
    """Flows recorded as deliberately not being scenarios, with their reasons.

    The reason is required, not optional. An entry with a bare path reads as
    "somebody decided something once" and gets swept away by the next cleanup
    that wonders what the file is for; a reason is a claim, and a claim can be
    argued with. Requiring it here rather than in review is the only place it
    cannot be forgotten.

    A path that no longer exists is an error rather than a silent drop. A
    renamed flow that keeps its opt-out entry means the next reader is being
    told a question was settled about a file that is not there — and if the
    answer still holds, the entry is one edit away from being right.
    """
    if not path.is_file():
        return {}
    opt_outs: dict[str, str] = {}
    errors: list[str] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "—" not in stripped and " - " not in stripped:
            errors.append(f"{path}:{number}: нет причины — формат 'путь  —  причина'")
            continue
        separator = "—" if "—" in stripped else " - "
        flow, _, reason = stripped.partition(separator)
        flow, reason = flow.strip(), reason.strip()
        if not reason:
            errors.append(f"{path}:{number}: причина пустая")
            continue
        if flow in opt_outs:
            errors.append(f"{path}:{number}: '{flow}' уже исключён выше")
            continue
        opt_outs[flow] = reason
    if errors:
        raise ValidationError(
            f"некорректные записи в списке исключений ({len(errors)}):\n  - "
            + "\n  - ".join(errors)
        )
    return opt_outs


@dataclass(frozen=True, slots=True)
class FlowProposal:
    """One untagged flow and the scenario it most plausibly carries."""

    path: str
    area: str
    #: The id the flow's own name matches, when the file is literally named
    #: after a scenario. These are the highest-confidence rows and the ones to
    #: tag first.
    from_name: str
    #: The nearest existing scenario by token overlap, if any.
    nearest_id: str
    nearest_score: float
    #: A new id in this area, when nothing existing matches closely.
    proposed_id: str
    #: What the candidate scenario claims to verify — empty for a new id.
    nearest_expected: str
    #: True when the proposal reuses an id that already exists on disk.
    #: Matters because an existing id is already validated against *its own*
    #: directory depth (`auth/signin/AUTH-SIGNIN-01` is two segments against two
    #: directories), while a new id must fit the area's single directory. A
    #: consumer checking "does this id match the flow's path depth" gets the
    #: wrong answer for exactly the confident rows unless told which is which.
    existing: bool
    #: The flow's `name:` value and its interactive command count, the reader's
    #: first evidence that the flow is about something.
    flow_name: str
    commands: int

    @property
    def confidence(self) -> str:
        """High / Medium / Low, from how the candidate was arrived at."""
        if self.from_name:
            return "high"
        if self.nearest_score >= _MEDIUM_SCORE:
            return "medium"
        return "low"


#: Jaccard at or above which an existing scenario is offered as more than a weak
#: hint. Set from the one match that is not a guess: `auth/02-signin-wrong-password`
#: against `AUTH-SIGNIN-01` ("wrong password is rejected and the form stays
#: usable") scores 0.44, while the next-best row in this repo is 0.31. A
#: threshold below 0.44 would start including rows whose nearest scenario shares
#: only the word "task"; a threshold above it would hide the row that is right.
_MEDIUM_SCORE = 0.4


def _tokens(text: str) -> set[str]:
    """Words of a name, minus numbers and generic words."""
    text = text.replace("_", "-")
    words = {w for w in _SPLIT.split(text.lower()) if w}
    return {w for w in words if w not in _STOPWORDS and not w.isdigit()}


def _flow_tokens(path: Path, flows_root: Path) -> set[str]:
    rel = path.relative_to(flows_root).with_suffix("")
    parts = [p for p in rel.parts if p != "flows"]
    words: set[str] = set()
    for part in parts:
        cleaned = _LEADING_NUMBER.sub("", part)
        words |= _tokens(cleaned)
    return words


def _area_of(path: Path, flows_root: Path) -> str:
    rel = path.relative_to(flows_root)
    first = rel.parts[0]
    return f"feature.{first}"


def _declared_area(spec: ScenarioSpec) -> str:
    return spec.area


def _header_tags(text: str) -> list[str]:
    """The ``tags:`` block of a flow header.

    Read by `scanners.maestro_flow` — the same reader `run-maestro` filters
    on and the same reader the traceability layer reads. This function used
    to stop at the first ``---`` and read the rest of the file on its own
    rules, so a flow whose tags block sits in a later document looked to the
    suggester like a flow with no tags at all — and to the runner like a
    flow with tags.
    """
    return flow_tags_text(text)


def _count_commands(text: str) -> int:
    """How many interactive steps the flow performs — a size proxy only."""
    body = text.split("---", 1)[1] if "---" in text else ""
    return sum(
        1
        for line in body.splitlines()
        if re.match(r"^\s*-\s+(tapOn|inputText|swipe|assertVisible|extendedWaitUntil|runFlow|back|openLink|pressKey|hideKeyboard|waitForAnimationToEnd)\b", line)
    )


def _area_prefix(area: str) -> str:
    """The id segment an area's directory accepts.

    ``spec._check_prefix_matches_path`` requires every id segment to be a *prefix*
    of the directory it sits in, and every segment to be hyphen-free — so a
    directory named ``calendar_sync`` cannot be spelled ``CALENDAR_SYNC-01`, or
    the id would read as two segments against one directory and validation
    would reject it. The first underscore-delimited chunk is the shortest form
    that satisfies the rule: ``calendar_sync`` -> ``CALENDAR``, matching the
    abbreviation style ``TASK-REC-01`` already sets for ``tasks/recurrence``.
    """
    directory = area.split(".")[-1]
    return directory.split("_", 1)[0].upper()


def _next_free_number(area: str, specs: dict[str, ScenarioSpec], taken: set[str]) -> str:
    """The next free ordinal for a new id in this area.

    ``taken`` accumulates the ids proposed earlier in the same run. Without it
    every flow in an area gets the same number, which made the first version of
    this report propose ``AGENDA-01`` for all eight agenda flows — a worklist
    whose entries all collide is not a worklist.
    """
    prefix = _area_prefix(area)
    used = {sid for sid in specs if sid.startswith(f"{prefix}-")}
    number = 1
    while f"{prefix}-{number:02d}" in used or f"{prefix}-{number:02d}" in taken:
        number += 1
    return f"{prefix}-{number:02d}"


def _score(flow_tokens: set[str], spec: ScenarioSpec, scenario_id: str) -> float:
    """Overlap between a flow's words and a scenario's own words.

    The id prefix, title and steps are all considered, because the id is
    abbreviation (``TASK-REC-01``) while the title and steps are prose
    ("create a daily recurring task / tap Recurrence in the editor") — scoring
    on the id alone misses most flows, scoring on the title alone misses flows
    whose name is technical.

    Jaccard, not ``overlap / len(candidate)``: the denominator penalises long
    scenarios, so a three-step scenario would need to match three words to reach
    the same score a one-word scenario needs to match one. That would make the
    threshold measure verbosity instead of agreement.
    """
    prefix = scenario_id.rsplit("-", 1)[0]
    candidate = _tokens(prefix) | _tokens(spec.title)
    for step in spec.steps:
        candidate |= _tokens(step)
    if not candidate:
        return 0.0
    return len(flow_tokens & candidate) / len(flow_tokens | candidate)


def propose(
    specs: dict[str, ScenarioSpec],
    root: Path = REPO_ROOT,
    opt_outs: dict[str, str] | None = None,
) -> list[FlowProposal]:
    """Every untagged flow, with the scenario it most plausibly carries.

    Flows recorded in the opt-out file are excluded: they have already been read
    and answered, and re-proposing them each run is what makes a worklist
    permanent rather than shrinking.

    Sorted most-confident first: the high-confidence rows are the ones worth
    doing this hour, and burying them under 40 low-confidence guesses would make
    the whole worklist feel like noise.
    """
    flows_root = root / "Maestro" / "flows"
    if not flows_root.is_dir():
        return []
    excluded = load_opt_outs() if opt_outs is None else opt_outs

    by_area: dict[str, list[str]] = {}
    for scenario_id, spec in specs.items():
        by_area.setdefault(_declared_area(spec), []).append(scenario_id)

    order = {"high": 0, "medium": 1, "low": 2}
    proposals: list[FlowProposal] = []
    #: Ids handed out during this run, so two flows in one area never collide.
    taken: set[str] = set()

    for path in sorted(flows_root.rglob("*.yaml")):
        text = path.read_text(encoding="utf-8")
        if any(tag.startswith(SCENARIO_TAG_PREFIX) for tag in _header_tags(text)):
            continue

        rel = path.relative_to(root).as_posix()
        if rel in excluded:
            continue

        stem = path.stem.upper()
        # A flow named after a scenario already answers the question. This is
        # not a heuristic — the author chose the name.
        from_name = stem if ID_PATTERN.match(stem) and stem in specs else ""

        area = _area_of(path, flows_root)
        flow_tokens = _flow_tokens(path, flows_root)

        best_id, best_score = "", 0.0
        for scenario_id in sorted(by_area.get(area, [])):
            candidate_score = _score(flow_tokens, specs[scenario_id], scenario_id)
            if candidate_score > best_score:
                best_id, best_score = scenario_id, candidate_score

        proposed_id = (
            best_id if best_score >= _MEDIUM_SCORE else _next_free_number(area, specs, taken)
        )
        taken.add(proposed_id)

        proposals.append(
            FlowProposal(
                path=rel,
                area=area,
                from_name=from_name,
                nearest_id=best_id,
                nearest_score=round(best_score, 2),
                proposed_id=proposed_id or from_name,
                nearest_expected=specs[best_id].expected if best_id else "",
                existing=bool(proposed_id) and proposed_id in specs,
                flow_name=_flow_name(text),
                commands=_count_commands(text),
            )
        )

    proposals.sort(key=lambda p: (order[p.confidence], -p.nearest_score, p.path))
    return proposals


def _flow_name(text: str) -> str:
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("name:"):
            return stripped.split(":", 1)[1].strip()
    return ""


def render_report(
    proposals: list[FlowProposal],
    total_flows: int,
    tagged: int,
    opt_outs: dict[str, str] | None = None,
) -> str:
    """The worklist, as markdown.

    Grouped by confidence with a stated reason for each, because "73 of 74 are
    low confidence" reads as an admission of failure and "6 flows are named
    after their scenario, 9 share the scenario's own words, the rest are
    navigation coverage nobody has decided whether is a scenario" reads as a
    task.

    Excluded flows are printed too. A silent exclusion would be the mirror image
    of the false hole this module exists to stop: the worklist would shrink and
    nobody could see that the shrinkage came from a decision rather than from
    progress.
    """
    high = [p for p in proposals if p.confidence == "high"]
    medium = [p for p in proposals if p.confidence == "medium"]
    low = [p for p in proposals if p.confidence == "low"]
    by_area: dict[str, int] = {}
    for proposal in proposals:
        by_area[proposal.area] = by_area.get(proposal.area, 0) + 1
    excluded = opt_outs or {}

    lines: list[str] = []
    lines.append("## Maestro flows without a `scenario:` tag\n")
    lines.append(
        f"{total_flows} flows total, {tagged} tagged, **{len(proposals)} untagged**, "
        f"{len(excluded)} excluded by decision. Each row is a proposal, not a "
        f"decision — a tag asserts that the flow verifies the scenario, which "
        f"only someone who read the flow can say.\n"
    )

    if excluded:
        lines.append("### Excluded by decision\n")
        lines.append(
            "Recorded in `config/docs/maestro-not-scenarios.txt`, so the question "
            "is answered rather than re-asked. Removing an entry re-opens it.\n"
        )
        lines.append("| Flow | Why it is not a scenario |")
        lines.append("|---|---|")
        for flow, reason in sorted(excluded.items()):
            lines.append(f"| `{flow}` | {reason} |")
        lines.append("")

    lines.append("### Untouched areas\n")
    lines.append(
        "These have flows but no scenario specs at all, so they cannot appear "
        "in the matrix in any state. Writing the spec is the work; tagging is "
        "the last step of it.\n"
    )
    lines.append("| Area | Untagged flows |")
    lines.append("|---|---|")
    for area, count in sorted(by_area.items()):
        lines.append(f"| `{area}` | {count} |")
    lines.append("")

    for title, group, note in (
        (
            "High confidence — the flow is named after the scenario",
            high,
            "The author chose the filename to be the id. Confirm the flow body "
            "matches the scenario's `expected`, then tag.",
        ),
        (
            "Medium — the flow's words match an existing scenario's",
            medium,
            "Read the flow and the scenario side by side. Tag if the flow "
            "genuinely asserts the scenario; a new scenario id is the other "
            "honest answer.",
        ),
        (
            "Low — no match; a new scenario id is proposed",
            low,
            "Mostly navigation, smoke and negative-path flows. A flow that only "
            "proves a screen renders is not a scenario; leave it untagged, and "
            "let it stay invisible to the matrix.",
        ),
    ):
        lines.append(f"### {title} ({len(group)})\n")
        if not group:
            lines.append("_none_\n")
            continue
        lines.append(f"{note}\n")
        lines.append("| Flow | Proposed id | Nearest scenario | Score | Steps |")
        lines.append("|---|---|---|---|---|")
        for proposal in group:
            nearest = proposal.nearest_id or "—"
            if proposal.nearest_id and proposal.nearest_score:
                nearest += f" ({proposal.nearest_score})"
            lines.append(
                f"| `{proposal.path}` | `{proposal.proposed_id}` | {nearest} | "
                f"{proposal.commands} |"
            )
        lines.append("")

    return "\n".join(lines)
"""maestro._flow — flow file discovery, tag parsing, and filtering."""
from __future__ import annotations

from pathlib import Path

from emulator.maestro._types import ROOT
from scanners.maestro_flow import flow_tags_text

# The tag a flow declares to belong to the smoke suite.
SMOKE_TAG = "smoke"

# The suites the runner knows about.
SUITE_SMOKE = "smoke"
SUITE_FULL = "full"


def flow_tags(path: Path) -> list[str]:
    """Return the list of tags declared in a flow's tags: block, trimmed.

    The tags block may appear in the YAML frontmatter (before the first ---) or
    in any YAML document. Scanning proceeds document-by-document, bounded by ---
    separators, and stops at the first non-blank, non-comment, non-list-item
    line outside a tags block.

    The reading itself is `scanners.maestro_flow` — the same reader the
    traceability layer uses. This function used to carry its own copy of the
    rule, and a flow's tags are a fact three modules acted on.
    """
    return flow_tags_text(path.read_text())


def flow_has_tag(path: Path, tag: str) -> bool:
    return tag in flow_tags(path)


def collect_flows(target: Path) -> list[Path]:
    """Return sorted list of *.yaml files under target."""
    target = target.resolve()
    if target.is_dir():
        return sorted(target.rglob("*.yaml"), key=lambda p: p.name.lower())
    elif target.is_file():
        return [target]
    else:
        # A glob pattern, e.g. "Maestro/flows/notes/*.yaml". `Path.glob` refuses an
        # absolute pattern, and `target` is absolute by now — so the pattern has to
        # be made relative to the repository root again before it is handed over.
        # A pattern that points outside the root matches nothing, on purpose: the
        # flows are the repository's, and a path that escapes it is a mistake.
        try:
            relative = target.relative_to(ROOT)
        except ValueError:
            return []
        matches = sorted(m for m in ROOT.glob(str(relative)) if m.suffix == ".yaml")
        return matches


def filter_flows_by_tag(flows: list[Path], tags: list[str]) -> list[Path]:
    """Return flows that declare any of the given tags."""
    if not tags:
        return flows
    result = []
    for f in flows:
        for t in tags:
            if flow_has_tag(f, t):
                result.append(f)
                break
    return result


def suite_selection(flows_root: Path, suite: str) -> tuple[Path, list[str]]:
    """Return the flow target and the tag filter a suite selects.

    The smoke suite is selected by **tag**, not by directory. A directory
    selection says "the smoke suite is whatever happens to live under
    `Maestro/flows/smoke/`", so a flow that declares `smoke` and lives
    anywhere else — `Maestro/flows/notes/create-note.yaml` — runs in the
    nightly full suite and nowhere else, and the tag on it is a lie. The tag
    selection says what the tag means: every flow that declares `smoke` is
    part of the smoke suite, wherever it lives.

    The smoke *directory* keeps its contents: every flow in it declares the
    `smoke` tag, and `scripts/tests/test_maestro_suite_selection.py` fails
    when one stops doing so — a flow that lost the tag would otherwise drop
    out of the smoke suite without a word.

    The full suite is every flow under the root, with no tag filter, which is
    what it always was.
    """
    if suite == SUITE_SMOKE:
        return flows_root, [SMOKE_TAG]
    return flows_root, []


def smoke_flows(flows_root: Path) -> list[Path]:
    """Return the flows the smoke suite runs: every flow tagged `smoke`.

    The directory `Maestro/flows/smoke/` is not consulted on purpose — the
    tag is the selection, and a flow in that directory that lost its tag is
    a defect the tag test reports, not a flow to keep by accident.
    """
    return filter_flows_by_tag(collect_flows(flows_root), [SMOKE_TAG])

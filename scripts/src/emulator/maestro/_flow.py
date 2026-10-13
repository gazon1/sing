"""maestro._flow — flow file discovery, tag parsing, and filtering."""
from __future__ import annotations

from pathlib import Path

from emulator.maestro._types import ROOT


def flow_tags(path: Path) -> list[str]:
    """Return the list of tags declared in a flow's tags: block, trimmed.

    The tags block may appear in the YAML frontmatter (before the first ---) or
    in any YAML document. Scanning proceeds document-by-document, bounded by ---
    separators, and stops at the first non-blank, non-comment, non-list-item
    line outside a tags block.
    """
    text = path.read_text()

    in_tags = False
    tags: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("---"):
            in_tags = False
            continue
        if stripped.startswith("tags:"):
            in_tags = True
            continue
        if in_tags:
            if stripped.startswith("- "):
                value = stripped[1:].strip().strip("'\"").split("#")[0].strip()
                if value:
                    tags.append(value)
            elif stripped == "" or stripped.startswith("#"):
                continue
            else:
                in_tags = False
    return tags


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
        matches = sorted(m for m in ROOT.glob(str(target)) if m.suffix == ".yaml")
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

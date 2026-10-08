#!/usr/bin/env python3
"""check-req-id-uniqueness.py — one requirement, one identifier.

Why this exists (2026-10-07)
----------------------------
`a-cycle-drains-the-feed-or-says-it-could-not` declared `REQ-OS-026` and `REQ-OS-027`.
Both identifiers were already in use, for requirements that have nothing to do with it:
`REQ-OS-026` was the lost-race rule archived from `2026-10-06-a-lost-race-resolves-to-the-server`,
and `REQ-OS-027` in the spec described auto-sync settings taking effect without a restart.

So a change about draining a sync feed claimed to *be* the rule about losing a race. Nothing
complained. `openspec validate --strict` reported the first collision as an `[INFO]` line while
exiting 0, and the only hard stop appeared at `openspec archive` time — after the work was
done, when the delta is folded into the spec and cannot be applied.

Worse, the collision was invisible in both directions. Every code reference to `REQ-OS-026`
meant the lost-race rule (`SyncProtocol.kt`, `SyncEngine.kt`, `SyncEngineLostRaceTest.kt`,
ADR `2026-10-06-the-sync-engine-needs-the-repositories…`), so a reader following the number from
the code lands on a requirement about something else. A traceability matrix built on these
identifiers reports the wrong thing with total confidence.

The identifiers are load-bearing: they are what a KDoc `@see` points at, what a spec cites, and
what a reader uses to check that the code does what it claims. They are worth more than a
comment, so they are worth a gate.

## What counts as a conflict

The delta language has a second use for an identifier, and this gate must not call it a
conflict:

  - **`## ADDED Requirements`** — the change defines a new requirement. Its identifier must not
    exist anywhere else. This is the collision above.
  - **`## MODIFIED Requirements` / `## REMOVED Requirements`** — the change edits or deletes a
    requirement that is already in the spec, and restating its identifier is the point. Naming
    one that does not exist is an error, because it is a typo that silently does nothing.

`openspec/changes/archive/` is excluded: an archived change's delta has already been folded into
`openspec/specs/`, so its identifiers appear in both places by design.

## Known instances, measured on the corpus the day it landed

The check is registered **advisory**, because the corpus it reads is not yet clean. Turning a
gate blocking on a red tree teaches its reader to ignore it, and one of the two shapes it found
is still open:

  - **`REQ-LE-001` … `REQ-LE-004` are each defined twice** — `log-export-surface` and
    `add-log-export` both ADD the same four log-export requirements, in different words. The
    second is the implementation of the first, so the pair is one change wearing two folders,
    and the earlier folder's `REQ-LE-002`/`REQ-LE-003` (share at the failure point, redact user
    content) are still unimplemented in either. Reconciling them is its own piece of work.
  - **`a-cycle-drains-the-feed-or-says-it-could-not` claimed `REQ-OS-026`/`REQ-OS-027`** — fixed
    in the same commit that added this check, renumbered to `REQ-OS-015`/`REQ-OS-016`.

It becomes blocking when that corpus is clean, and not before.

## What this does not check

That the requirement *texts* are compatible, that a MODIFIED delta preserves the scenarios it
means to preserve, or that a change's identifiers are referenced anywhere. It is a name check,
and it is deliberately only that.

Usage:
    python3 scripts/check-req-id-uniqueness.py [--quiet]

Exit codes:
    0 — every identifier names one requirement
    1 — an identifier is reused for different requirements, or a delta edits one that is absent
"""

from __future__ import annotations

import argparse
import collections
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: `## ADDED Requirements` and its siblings. A change only defines or edits; the marker is what
#: tells the two apart.
DELTA_HEADER = re.compile(r"^##\s+(ADDED|MODIFIED|REMOVED|RENAMED)\s+Requirements\s*$")

#: `### Requirement: REQ-OS-015`. The identifier is optional in the delta language, so a
#: requirement without one is reported rather than passed.
REQUIREMENT_HEADER = re.compile(r"^###\s+Requirement:\s*(?P<id>\S*)\s*$")

#: `REQ-<CAPABILITY>-<NNN>` is the convention this repository uses, with one measured exception:
#: `genui-catalog-contract` adds `REQ-GC-001a` alongside `REQ-GC-001` to state a second half of
#: the same rule rather than renumber the first. The suffix is deliberate, so the check accepts
#: it — a gate that rejects the corpus's own numbering is a gate that gets switched off.
IDENTIFIER = re.compile(r"^REQ-[A-Z0-9]+-[0-9]{3}[a-z]?$")


def spec_files(root: pathlib.Path = ROOT) -> list[pathlib.Path]:
    """Every spec that currently defines a requirement: `openspec/specs/` and live deltas."""
    openspec = root / "openspec"
    found = [p for p in (openspec / "specs").rglob("spec.md")]
    changes = openspec / "changes"
    if changes.is_dir():
        for change in sorted(changes.iterdir()):
            if not change.is_dir() or change.name == "archive":
                continue
            found.extend(change.glob("specs/*/spec.md"))
    return sorted(found)


class Definition:
    """One `### Requirement:` header: what it names, where, and whether it adds or edits."""

    def __init__(self, identifier: str, kind: str, path: pathlib.Path, line: int) -> None:
        self.identifier = identifier
        self.kind = kind
        self.path = path
        self.line = line

    @property
    def location(self) -> str:
        try:
            shown = self.path.relative_to(ROOT)
        except ValueError:
            shown = self.path
        return f"{shown}:{self.line}"

    def __repr__(self) -> str:  # pragma: no cover - diagnostics only
        return f"<{self.identifier} {self.kind} {self.location}>"


def definitions_in(path: pathlib.Path) -> list[Definition]:
    """
    The requirement headers in one file, each tagged with the delta section it sits in.

    Delta headers count only under `openspec/changes/`. A `## ADDED Requirements` marker in
    `openspec/specs/` is malformed — the spec is the folded result and has no sections — and
    honouring it there would make a definition look like a delta that reuses an identifier.
    """
    out: list[Definition] = []
    is_delta = "changes" in path.parts
    kind = "ADDED" if is_delta else "SPEC"
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        header = DELTA_HEADER.match(line)
        if header:
            kind = header.group(1) if is_delta else "SPEC"
            continue
        found = REQUIREMENT_HEADER.match(line)
        if found:
            out.append(Definition(found.group("id"), kind, path, number))
    return out


def conflicts(definitions: list[Definition]) -> list[str]:
    """Every reason the corpus is not one-identifier-per-requirement, as text."""
    problems: list[str] = []
    unnamed = [d for d in definitions if not d.identifier]
    for definition in unnamed:
        problems.append(f"{definition.location}: a requirement header with no identifier")

    malformed = [d for d in definitions if d.identifier and not IDENTIFIER.match(d.identifier)]
    for definition in malformed:
        problems.append(
            f"{definition.location}: {definition.identifier} is not REQ-<CAPABILITY>-<NNN>"
        )

    named = [d for d in definitions if IDENTIFIER.match(d.identifier or "")]
    by_id: dict[str, list[Definition]] = collections.defaultdict(list)
    for definition in named:
        by_id[definition.identifier].append(definition)

    for identifier, group in sorted(by_id.items()):
        problems.extend(_problems_for(identifier, group))
    return problems


def _problems_for(identifier: str, group: list[Definition]) -> list[str]:
    if len(group) == 1:
        definition = group[0]
        if definition.kind in {"MODIFIED", "REMOVED", "RENAMED"}:
            return [
                f"{identifier}: {definition.kind} at {definition.location} names a requirement "
                f"that no spec defines"
            ]
        return []

    # A delta that edits an existing requirement may restate it, and that is the whole point.
    # Everything else is a second definition of one name.
    in_spec = [d for d in group if d.kind == "SPEC"]
    added = [d for d in group if d.kind == "ADDED"]
    edits = [d for d in group if d.kind in {"MODIFIED", "REMOVED", "RENAMED"}]
    if len(in_spec) == 1 and not added and len(edits) == len(group) - 1:
        return []

    where = ", ".join(f"{d.location} ({d.kind})" for d in group)
    if in_spec and added:
        return [
            f"{identifier}: a change ADDs a requirement that the spec already defines — "
                f"a new requirement needs a new identifier. {where}"
        ]
    return [f"{identifier}: defined {len(group)} times. {where}"]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--quiet", action="store_true", help="print nothing when the check passes")
    parser.add_argument(
        "--root",
        default=str(ROOT),
        help="repository root to read (default: this script's parent). The controls need to run "
             "against a corpus of their own, including the empty one.",
    )
    args = parser.parse_args(argv)

    root = pathlib.Path(args.root)
    definitions = [d for path in spec_files(root) for d in definitions_in(path)]
    if not definitions:
        print(
            "ERROR: no requirement headers were found under openspec/, so this gate can only "
            "pass vacuously. A checker that stopped matching looks exactly like a corpus that "
            "stopped having requirements.",
            file=sys.stderr,
        )
        return 1

    problems = conflicts(definitions)
    if problems:
        for line in problems:
            print(f"check-req-id-uniqueness: {line}")
        print(
            "\nEach identifier must name exactly one requirement. A change that edits an "
            "existing one may restate its identifier under MODIFIED or REMOVED; a change that "
            "defines a new one needs an identifier nothing else uses.",
            file=sys.stderr,
        )
        return 1

    if not args.quiet:
        print(
            f"check-req-id-uniqueness: OK — {len(definitions)} requirement headers, "
            f"{len({d.identifier for d in definitions})} distinct identifiers"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
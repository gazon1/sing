#!/usr/bin/env python3
"""check-provenance.py — a derivation marker in production code must be registered.

Why this exists (2026-10-05): the project is preparing to publish its core under
Apache-2.0 and a `pro/` catalogue under FSL-1.1-Apache. GPL contamination does not
wash off in proportion to how little code it touches, so before publication every
file that is not wholly original has to be nameable. The audit that established
the list is written down in `docs/legal/PROVENANCE.md`; this check is what keeps
that list true afterwards.

The failure this prevents is specific and it is not hypothetical. `core/billing/`
carried Tasks.org field names (`isTasksSubscription`, `isGitHubSponsor`,
`hasPro`) and `feature/search/query/` was marked *"lifted from Orgzly"* in its own
KDoc — both for weeks, in a repository with no licence file at all. Nobody had
recorded that a GPL source existed in the tree. A note in a KDoc is not a control.

## What is checked

1. **Marker → registry.** Every production source file containing a derivation
   marker is listed in `config/legal/provenance-registry.tsv`. A new marker can
   never again be added without a registry row, which is the one guarantee the
   registry can actually make.
2. **Registry → file.** Every registered path exists. A row pointing at a deleted
   file is a claim about code that is not there.
3. **Registry → annotation.** Every `PORTED` and `ADAPTED` row carries a
   `// Provenance: <CLASS>` line in the file, with a class matching the registry.
   `SPEC-COMPATIBLE` and `ORIGINAL` do not need one: a spec implementation is
   ordinary work, and marking every original file would train the annotation to
   mean nothing.
4. **Class well-formedness.** The class is one of the four known values.
5. **The scan is not vacuous.** The marker scan must find at least one hit in the
   real tree. See "The positive control" below.

## The positive control

Rule 1 is a regex over source text. A regex that quietly stops matching reports
success having checked nothing — the exact vacuous-gate failure this project has
already paid for twice, which is why the rule carries a control in two places:

- `--self-test` runs the detector over a synthetic corpus that *does* contain a
  marked file and asserts it is caught, and over a negative corpus and asserts it
  is not. This is runnable from a shell and from CI.
- `scripts/tests/test_check_provenance.py` exercises the same properties as unit
  tests, alongside the other gate self-tests.

Rule 5 is the second half of the control, and it is the half that catches the case
unit tests cannot: if the real tree somehow stopped containing a single marker —
the package was deleted, the regex was narrowed, a glob stopped matching — rule 1
would have nothing to check and would pass having verified nothing. So the check
insists that it found something. A registry that no longer matches reality is a
finding, not a pass.

## Usage

    python3 scripts/check-provenance.py            # check the repository
    python3 scripts/check-provenance.py --self-test  # prove the rule still fires

Exit codes:
    0 — every derivation marker is registered, and the rule demonstrably works
    1 — a violation, or the scan is vacuous
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REGISTRY = ROOT / "config" / "legal" / "provenance-registry.tsv"
DOC = ROOT / "docs" / "legal" / "PROVENANCE.md"

# Production source roots. Test source sets are excluded on purpose: a test that
# quotes a marker in a fixture is not a derivative work, and requiring registry
# rows for them would push authors to stop writing honest test fixtures.
SOURCE_ROOTS = ("shared/src", "androidApp/src", "desktopApp/src", "mcp-server/src")

# A directory named `pro` is licensed under FSL-1.1-Apache, not Apache-2.0, and
# is expected to contain derived code. It is skipped rather than registered.
PRO_DIR = "pro"

# Only `*Main` source sets are production. `commonTest`, `jvmTest`, `androidTest`
# and friends are covered by the SOURCE_ROOTS comment above.
_PROD_SOURCE_SET = re.compile(r"src/(?:\w+/)?(\w*[Mm]ain)/")

# Derivation markers.
#
# These name an **external upstream** or an explicit licence tag, and nothing
# else. The first draft of this rule also matched `derived from`, `taken from`
# and `mirrors <Thing>'s`, which produced 30 false positives on first run against
# the real tree — "a fixed name instead of one derived from wall-clock time",
# "lifted from domainTask to avoid reference", "Re-exported from core/llm/". A
# gate that reports English prose is a gate that gets switched off, and this
# project has a standing rule that a noisy gate is worse than no gate.
#
# The cost of narrowing is stated rather than hidden: this rule detects a file
# that *names* an upstream, not a file that was derived from one. A derivation
# nobody wrote down is not detectable by any scan. What this guarantees is
# narrower and still worth having — a note acknowledging Orgzly or Tasks.org can
# no longer be added to production code without a registry row, and an SPDX or
# GPL tag can never appear without one either.
_UPSTREAMS = (
    r"orgzly",
    r"tasks\.org",
    r"astrid",
    r"orgmode\.org",
)
_MARKERS = (
    *(rf"\b{u}\b" for u in _UPSTREAMS),
    r"SPDX-License-Identifier",
    r"\b(?:A?GPL|LGPL|MPL|EPL|CDDL)[- ]?[123]?\.?\d?\b",
)
MARKER_RE = re.compile("|".join(f"(?:{m})" for m in _MARKERS), re.IGNORECASE)

ANNOTATION_RE = re.compile(
    r"//\s*Provenance:\s*(ORIGINAL|SPEC-COMPATIBLE|ADAPTED|PORTED|REWRITTEN)\b")

CLASSES = ("ORIGINAL", "SPEC-COMPATIBLE", "ADAPTED", "PORTED", "REWRITTEN")
ANNOTATED_CLASSES = ("ADAPTED", "PORTED", "REWRITTEN")


class Violation(Exception):
    """A check that did not hold. One instance per distinct finding."""


# --------------------------------------------------------------------------
# Registry
# --------------------------------------------------------------------------


def read_registry(path: Path = REGISTRY) -> dict[str, dict[str, str]]:
    """Parse the TSV into `{path: {class, source, licence, note}}`.

    Blank lines and `#` comments are skipped. A malformed row raises rather than
    being dropped: a registry row the parser cannot read is a claim the check
    cannot keep, and silently ignoring it would turn a typo into a false pass.
    """
    if not path.exists():
        raise Violation(f"registry not found: {path.relative_to(ROOT)}")

    registry: dict[str, dict[str, str]] = {}
    for lineno, row in enumerate(csv.reader(path.read_text().splitlines(), delimiter="\t"), 1):
        if not row or not row[0].strip() or row[0].lstrip().startswith("#"):
            continue
        if len(row) < 2:
            raise Violation(f"{path.name}:{lineno}: expected at least path and class, got {row!r}")
        key, cls = row[0].strip(), row[1].strip()
        if cls not in CLASSES:
            raise Violation(
                f"{path.name}:{lineno}: unknown class {cls!r} for {key} "
                f"(expected one of {', '.join(CLASSES)})"
            )
        if key in registry:
            raise Violation(f"{path.name}:{lineno}: duplicate entry for {key}")
        registry[key] = {
            "class": cls,
            "source": row[2].strip() if len(row) > 2 else "-",
            "licence": row[3].strip() if len(row) > 3 else "-",
            "note": row[4].strip() if len(row) > 4 else "-",
        }
    if not registry:
        raise Violation(f"{path.name}: registry is empty — a check over nothing")
    return registry


# --------------------------------------------------------------------------
# Scanning
# --------------------------------------------------------------------------


def production_files(root: Path = ROOT) -> list[Path]:
    """Every production source file under the scanned roots.

    Test source sets are skipped. `pro/` is skipped: it is under FSL and is
    *supposed* to hold derived code.
    """
    found: list[Path] = []
    for rel_root in SOURCE_ROOTS:
        base = root / rel_root
        if not base.is_dir():
            continue
        for path in base.rglob("*.kt"):
            parts = path.relative_to(root).parts
            if PRO_DIR in parts:
                continue
            if not _PROD_SOURCE_SET.search(path.as_posix()):
                continue
            found.append(path)
    return sorted(found)


def marker_hits(path: Path) -> list[int]:
    """Line numbers in `path` that carry a derivation marker."""
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    return [n for n, line in enumerate(text.splitlines(), 1) if MARKER_RE.search(line)]


def annotated_class(text: str) -> str | None:
    """The class in the file's `// Provenance:` annotation, or None."""
    m = ANNOTATION_RE.search(text)
    return m.group(1) if m else None


# --------------------------------------------------------------------------
# Checks
# --------------------------------------------------------------------------


def check_markers_registered(root: Path, registry: dict[str, dict[str, str]]) -> list[Violation]:
    """Rule 1 — a marker with no registry row is the failure this gate exists for."""
    out: list[Violation] = []
    for path in production_files(root):
        hits = marker_hits(path)
        if not hits:
            continue
        rel = path.relative_to(root).as_posix()
        if rel not in registry:
            preview = path.read_text(encoding="utf-8", errors="replace").splitlines()[hits[0] - 1]
            out.append(
                Violation(
                    f"{rel}:{hits[0]} carries a derivation marker but is not in the registry\n"
                    f"      {preview.strip()[:100]}\n"
                    f"      Add a row to config/legal/provenance-registry.tsv and a section to "
                    f"docs/legal/PROVENANCE.md, or rewrite the note if it does not describe "
                    f"derivation."
                )
            )
    return out


def check_registry_targets_exist(root: Path, registry: dict[str, dict[str, str]]) -> list[Violation]:
    """Rule 2 — a row about a file that is gone is a claim about nothing."""
    return [
        Violation(f"registry lists {rel}, which does not exist")
        for rel in sorted(registry)
        if not (root / rel).is_file()
    ]


def check_annotations(root: Path, registry: dict[str, dict[str, str]]) -> list[Violation]:
    """Rule 3 — PORTED and ADAPTED files must say so, and agree with the registry."""
    out: list[Violation] = []
    for rel in sorted(registry):
        entry = registry[rel]
        if entry["class"] not in ANNOTATED_CLASSES:
            continue
        path = root / rel
        if not path.is_file():
            continue  # reported by rule 2
        found = annotated_class(path.read_text(encoding="utf-8", errors="replace"))
        if found is None:
            out.append(
                Violation(
                    f"{rel}: registry class is {entry['class']} but the file has no "
                    f"`// Provenance: {entry['class']}` annotation"
                )
            )
        elif found != entry["class"]:
            out.append(
                Violation(
                    f"{rel}: annotation says {found}, registry says {entry['class']} — "
                    f"they must agree"
                )
            )
    return out


def check_not_vacuous(root: Path) -> list[Violation]:
    """Rule 5 — the marker scan must have found something to check.

    If it found nothing, either the derived code was deleted without updating the
    registry, or the pattern/glob stopped matching. Both look identical from here
    and both would turn rule 1 into a pass that verified nothing.
    """
    hits = sum(1 for path in production_files(root) if marker_hits(path))
    if hits == 0:
        return [
            Violation(
                "no derivation marker found anywhere in production code — the scan is "
                "vacuous, so rule 1 checked nothing.\n"
                "      Either the derived packages were removed without updating the "
                "registry, or the marker pattern or source glob no longer matches.\n"
                "      Run `python3 scripts/check-provenance.py --self-test` to confirm the "
                "rule still fires."
            )
        ]
    return []


def run_checks(root: Path = ROOT) -> tuple[list[Violation], dict[str, int]]:
    registry = read_registry(root / REGISTRY.relative_to(ROOT))
    violations: list[Violation] = []
    violations += check_markers_registered(root, registry)
    violations += check_registry_targets_exist(root, registry)
    violations += check_annotations(root, registry)
    violations += check_not_vacuous(root)
    stats = {
        "registered": len(registry),
        "ported": sum(1 for e in registry.values() if e["class"] == "PORTED"),
        "adapted": sum(1 for e in registry.values() if e["class"] == "ADAPTED"),
        "spec_compatible": sum(1 for e in registry.values() if e["class"] == "SPEC-COMPATIBLE"),
        "scanned_files": len(production_files(root)),
        "files_with_markers": sum(1 for p in production_files(root) if marker_hits(p)),
    }
    return violations, stats


# --------------------------------------------------------------------------
# Positive control
# --------------------------------------------------------------------------

_POSITIVE_FIXTURE = """package com.example

/**
 * Design notes (lifted from Orgzly): two-phase token consumption.
 */
class Thing
"""

_NEGATIVE_FIXTURE = """package com.example

/**
 * Reports the failure to the crash reporter instead of swallowing it.
 */
class Thing
"""


def self_test() -> int:
    """Prove the marker rule fires on a file that is marked, and stays quiet otherwise.

    Run with `--self-test`. A rule that cannot be shown to catch a violation is not
    a rule, and this is the check that says so out loud.
    """
    import tempfile

    failures: list[str] = []

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        pos = root / "shared/src/commonMain/kotlin/com/example/Positive.kt"
        pos.parent.mkdir(parents=True)
        pos.write_text(_POSITIVE_FIXTURE, encoding="utf-8")
        reg = root / "config/legal/provenance-registry.tsv"
        reg.parent.mkdir(parents=True)
        reg.write_text(
            "# path\tclass\tsource\tlicence\tnote\n"
            "shared/src/commonMain/kotlin/com/example/Negative.kt\tORIGINAL\t-\t-\t-\n",
            encoding="utf-8",
        )

        hits = marker_hits(pos)
        if not hits:
            failures.append(
                "a file whose KDoc says 'lifted from Orgzly' produced no marker hit — "
                "the pattern is broken, so rule 1 cannot fire"
            )
        else:
            violations = check_markers_registered(root, read_registry(reg))
            if not violations:
                failures.append(
                    "an unregistered marked file produced no violation — the check is "
                    "broken even though the pattern matched"
                )
            else:
                print(f"  positive control: caught {violations[0].args[0].splitlines()[0]}")

        # The positive fixture must be gone before the negative assertion, or the
        # check finds *it* and the negative control passes for the wrong reason.
        pos.unlink()
        neg = root / "shared/src/commonMain/kotlin/com/example/Negative.kt"
        neg.write_text(_NEGATIVE_FIXTURE, encoding="utf-8")
        violations = check_markers_registered(root, read_registry(reg))
        if violations:
            failures.append(
                f"an ordinary file was reported: {violations[0].args[0].splitlines()[0]} — "
                f"the pattern is too broad and would make the gate noise"
            )
        else:
            print("  negative control: ordinary file not reported")

        test_dir = root / "shared/src/commonTest/kotlin/com/example/MarkedInTest.kt"
        test_dir.parent.mkdir(parents=True)
        test_dir.write_text(_POSITIVE_FIXTURE, encoding="utf-8")
        if test_dir in production_files(root):
            failures.append("a commonTest file was treated as production")
        else:
            print("  scope control: commonTest excluded from the scan")

        pro = root / "shared/src/commonMain/kotlin/com/example/pro/MarkedInPro.kt"
        pro.parent.mkdir(parents=True)
        pro.write_text(_POSITIVE_FIXTURE, encoding="utf-8")
        if pro in production_files(root):
            failures.append("a pro/ file was treated as Apache-2.0 production")
        else:
            print("  scope control: pro/ excluded (FSL, not Apache-2.0)")

    if failures:
        print("\ncheck-provenance.py self-test FAILED:\n", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print("\nself-test passed: the rule demonstrably fires, and is not vacuous.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="prove the marker rule fires on a marked file and stays quiet otherwise",
    )
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    try:
        violations, stats = run_checks()
    except Violation as exc:
        print(f"check-provenance: {exc}", file=sys.stderr)
        return 1

    print(
        f"provenance: {stats['registered']} registered "
        f"({stats['ported']} PORTED, {stats['adapted']} ADAPTED, "
        f"{stats['spec_compatible']} SPEC-COMPATIBLE); "
        f"{stats['files_with_markers']} of {stats['scanned_files']} production files "
        f"carry a marker"
    )
    if not DOC.exists():
        violations.append(Violation(f"registry has no prose: {DOC.relative_to(ROOT)} is missing"))

    if violations:
        print(f"\ncheck-provenance: {len(violations)} violation(s)\n", file=sys.stderr)
        for v in violations:
            print(f"  - {v}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

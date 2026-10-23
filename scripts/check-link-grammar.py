#!/usr/bin/env python3
"""check-link-grammar.py — the link grammar has one home.

The defect this catches
-----------------------
A link format that lives in two places is a link format that drifts. The
format of a deep link (`singularity://task/{id}`) has its home in
`TaskLinkSpec`; the format of an internal link (`task://`, `note://`) has its
home in `core/links/InternalLinks.kt`. Both were once written out by hand in
three places, and a change to one left the others in the old format: a link
written by the old producer stopped opening in the new matcher.

Nothing stops the second copy from being written again — the compiler has no
opinion about a string literal, and the writer does not know there is a home
for it. So the home is declared here, and the gate says when something
outside it names the scheme.

What counts as naming the scheme
--------------------------------
A **string literal** in production code. A comment that mentions `task://` is
prose about the format, not a second copy of it, and the gate reads the code
without the comments.

The grammar's own homes are the files that declare the prefixes, and they are
found rather than listed: the file that declares the constants is the home.

ADR `docs/decisions/2026-10-22-the-gate-suite-runs-this-checkout.md`.

Usage:
    python3 scripts/check-link-grammar.py --check    # fail when a copy exists
    python3 scripts/check-link-grammar.py --json     # JSON instead of text

Exit codes:
  0 = the grammar has one home
  1 = a file outside the home names the scheme
"""
from __future__ import annotations

import pathlib
import re
import sys

_SCRIPTS_SRC = str(pathlib.Path(__file__).resolve().parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from sgate.commands.base import run  # noqa: E402
from sgate.gatekit.cli import gate_command  # noqa: E402
from sgate.gatekit.findings import BLOCKING, Finding  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The source sets that hold production code.
SOURCE_SETS = ("commonMain", "androidMain", "jvmMain")

#: The schemes a link can carry.
SCHEMES = ("singularity://", "task://", "note://")

#: A file that declares the prefixes is a home. The pattern is the
#: declaration, not a path: a home that moves does not have to be listed
#: again.
HOME_MARKERS = (
    re.compile(r"\bconst\s+val\s+(SCHEME|HOST|MARKER|URI|PREFIX|TASK_PREFIX|NOTE_PREFIX)\b"),
    re.compile(r"\bconst\s+val\s+\w*(SCHEME|HOST|MARKER|PREFIX|URI)\w*\s*="),
)

#: A string literal that names a scheme.
LITERAL = re.compile(r'"([^"\\\n]*)"')

def _code_only(text: str) -> str:
    """The text without its comments.

    The comments are removed by reading the text, not by a regular
    expression: a link literal contains `//`, and a regular expression that
    does not know about strings reads `singularity://task/abc` as a comment
    and throws the scheme away — which is exactly the thing this gate looks
    for.
    """
    out: list[str] = []
    index = 0
    length = len(text)
    while index < length:
        char = text[index]
        if char == '"':
            out.append(char)
            index += 1
            while index < length:
                current = text[index]
                out.append(current)
                if current == "\\" and index + 1 < length:
                    out.append(text[index + 1])
                    index += 2
                    continue
                index += 1
                if current == '"' or current == "\n":
                    break
            continue
        if text.startswith("//", index):
            end = text.find("\n", index)
            index = length if end < 0 else end
            continue
        if text.startswith("/*", index):
            end = text.find("*/", index + 2)
            index = length if end < 0 else end + 2
            continue
        out.append(char)
        index += 1
    return "".join(out)


def _is_home(path: pathlib.Path, text: str) -> bool:
    """Whether a file declares the grammar."""
    if path.name.endswith("LinkSpec.kt"):
        return True
    if path.name == "InternalLinks.kt":
        return True
    return any(marker.search(text) for marker in HOME_MARKERS)


def _production_files(root: pathlib.Path) -> list[pathlib.Path]:
    """Every production Kotlin file of the tree."""
    shared = root / "shared/src"
    if not shared.is_dir():
        return []
    return sorted(
        path
        for path in shared.rglob("*.kt")
        if any(part in SOURCE_SETS for part in path.parts)
    )


def check(root: Path) -> list[Finding]:
    """The places that name a scheme outside the grammar's home."""
    findings: list[Finding] = []
    for path in _production_files(root):
        try:
            raw = path.read_text(encoding="utf-8")
        except OSError:
            continue
        if _is_home(path, raw):
            continue
        code = _code_only(raw)
        for literal in LITERAL.findall(code):
            for scheme in SCHEMES:
                if scheme in literal:
                    relative = path.relative_to(root).as_posix()
                    findings.append(
                        Finding(
                            BLOCKING,
                            f"{relative}: the string literal \"{literal}\" names the "
                            f"`{scheme}` scheme — the grammar has a home "
                            f"(`TaskLinkSpec`, `core/links/InternalLinks.kt`), and a second "
                            f"copy of it is a link that drifts",
                        )
                    )
                    break
    return findings


cli = gate_command(
    check,
    name="check-link-grammar",
    title="the link grammar has one home",
    default_root=ROOT,
    note=(
        "A link format that lives in two places is a link format that drifts: the "
        "producer writes the old format and the matcher reads the new one, and the "
        "link opens nothing."
    ),
)


if __name__ == "__main__":
    raise SystemExit(run(cli))

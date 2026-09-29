#!/usr/bin/env python3
"""Find surfaces that are fully implemented but wired to nothing.

The recurring defect this catches is not a broken feature but an *unreachable*
one: code that compiles, has tests, and is never invoked. Every instance found
during the Maestro suite expansion had the same shape and none of them was
visible to a test, because the test exercised the code that *was* wired.

Shapes detected:

  1. screen         — a public @Composable named *Screen with no call site
  2. default-noop   — a callback parameter defaulting to `{}` where the
                      consumer writes `param ?: fallback`, which the empty
                      lambda defeats
  3. di-binding      — a Room DAO accessor with no `get<AppDatabase>()…`
                      binding in either PlatformModule
  4. navigation     — a route type that no NavGraph entry or menu item reaches

Shape 4 is not checked here: reachable routes are a data question the Maestro
suite answers better than a static scan.

Usage:
    scripts/find-unwired-surfaces.py           # human-readable report
    scripts/find-unwired-surfaces.py --quiet   # findings only, for CI

Exit code is 1 when anything is reported, so it can gate a check. Baseline the
known-good set in config/ if the project grows a deliberate exception.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

SOURCE_ROOTS = ["shared/src", "androidApp/src", "desktopApp/src"]

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")
SCREEN_DECL = re.compile(r"^fun ([A-Z]\w*Screen)\s*\(", re.M)
# Must also match a parameter declared inside a `data class Foo(val …, val onClick: … = {}, …)`
# list, where the declaration follows a comma rather than starting a line.
# The type is written both as `() -> Unit` and as `(() -> Unit)`, so both forms match —
# a regex that only accepts the parenthesised one silently misses half the codebase.
_UNIT_FN_TYPE = r"(?:\(\s*\(\s*\)\s*->\s*Unit\s*\)|\(\s*\)\s*->\s*Unit)"
NOOP_DEFAULT = re.compile(
    r"(?:^|[(,]\s*)val\s+(\w*[Cc]lick\w*|\w*[Tt]oggle\w*|onOpen\w*)\s*:\s*"
    + _UNIT_FN_TYPE
    + r"\s*=\s*\{\s*\}",
    re.M,
)
# The consumer that the empty default defeats: `param ?: fallback`.
NOOP_FALLBACK_CONSUMER = re.compile(r"\b(\w*[Cc]lick\w*|\w*[Tt]oggle\w*|onOpen\w*)\s*\?:", re.M)
DAO_ACCESSOR = re.compile(r"abstract fun (\w+)\(\)\s*:\s*(\w*Dao)\b")
DAO_BINDING = re.compile(r"get<AppDatabase>\(\)\.(\w+)\(\)")


def strip_comments(text: str) -> str:
    """Drop comments.

    This is the whole point. A KDoc mention like ``[SyncConfigScreen]`` is a
    text match for the symbol but not a call, and counting it is exactly what
    lets an unwired screen look wired to a naive grep.
    """
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", text))


def kotlin_files() -> list[pathlib.Path]:
    files: list[pathlib.Path] = []
    for root in SOURCE_ROOTS:
        base = ROOT / root
        if base.exists():
            files.extend(p for p in base.rglob("*.kt") if p.is_file())
    return files


def rel(path: pathlib.Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="print findings only")
    args = parser.parse_args()

    files = kotlin_files()
    code = {p: strip_comments(p.read_text()) for p in files}
    corpus = "\n".join(code.values())

    findings: list[tuple[str, str]] = []

    # 1. Screens composed by nothing.
    for path, text in code.items():
        for m in SCREEN_DECL.finditer(text):
            name = m.group(1)
            references = len(re.findall(r"\b" + re.escape(name) + r"\b", corpus)) - 1
            if references <= 0:
                findings.append(("screen", f"{rel(path)}: {name}() has no call site"))

    # 2. Callbacks defaulting to an empty lambda where a `?:` fallback exists.
    # Only reported when the *same* parameter name is consumed with `?:` — an
    # empty default is harmless where nothing offers a fallback.
    fallback_names = {m.group(1) for m in NOOP_FALLBACK_CONSUMER.finditer(corpus)}
    for path, text in code.items():
        for m in NOOP_DEFAULT.finditer(text):
            param = m.group(1)
            if param in fallback_names:
                findings.append(
                    (
                        "default-noop",
                        f"{rel(path)}: {param} defaults to {{}} — a `{param} ?: fallback` "
                        "consumer reads the empty lambda as 'supplied' and never falls back",
                    )
                )

    # 3. Room DAO accessors with no Koin binding on either platform.
    bound: set[str] = set()
    for path, text in code.items():
        if "PlatformModule" in path.name:
            bound.update(DAO_BINDING.findall(text))
    for path, text in code.items():
        if "AppDatabase" not in path.name:
            continue
        for m in DAO_ACCESSOR.finditer(text):
            accessor, dao = m.group(1), m.group(2)
            if accessor not in bound:
                findings.append(
                    (
                        "di-binding",
                        f"{rel(path)}: {dao} via {accessor}() is not bound in any PlatformModule",
                    )
                )

    if not findings:
        if not args.quiet:
            print("No unwired surfaces found.")
        return 0

    for kind, message in sorted(findings):
        print(f"[{kind}] {message}")
    print(f"\n{len(findings)} finding(s).", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())

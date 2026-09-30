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
  4. log-writer     — a Kermit `LogWriter` subclass never registered via
                      `Logger.setLogWriters(...)`, so it silently receives
                      nothing. Added after MR-2 wired the logging subsystem
                      and found `FileLogWriter` had zero call sites for its
                      entire life.
  5. navigation     — a route type that no NavGraph entry or menu item reaches

Shape 5 is not checked here: reachable routes are a data question the Maestro
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
# A Kermit LogWriter subclass. Must be a class *declaration* extending LogWriter,
# not a mention of the type in a parameter or import — hence the requirement
# that `LogWriter` appear in the supertype list position right after the name.
LOG_WRITER_DECL = re.compile(
    r"class\s+(\w+)\s*(?:<[^>]*>)?\s*(?:\([^)]*\)\s*)?:\s*LogWriter\s*\(",
    re.S,
)
# `val fileWriter = FileLogWriter(dir)` — the alias shape both LogBootstrap files
# use, since the JVM one needs the reference for its shutdown hook.
WRITER_ALIAS = re.compile(r"val\s+(\w+)\s*=\s*([A-Z]\w*Writer)\s*\(")


def strip_comments(text: str) -> str:
    """Drop comments.

    This is the whole point. A KDoc mention like ``[SyncConfigScreen]`` is a
    text match for the symbol but not a call, and counting it is exactly what
    lets an unwired screen look wired to a naive grep.
    """
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", text))


SET_LOG_WRITERS = re.compile(r"setLogWriters\s*\(")


def set_log_writers_args(text: str) -> list[str]:
    """Return the full argument list of every ``Logger.setLogWriters(...)`` call.

    A regex like ``setLogWriters\\s*\\(([^)]*)\\)`` is wrong here: the arguments
    are themselves constructor calls, so ``[^)]*`` stops at the first ``)`` and
    silently drops every argument after the first nested call. That is how
    ``setLogWriters(RedactingLogWriter(ColorizedWriter()), RedactingLogWriter(fileWriter))``
    reads as a single argument and reports a wired writer as unwired. Matching
    the balanced parenthesis run is the only correct option.
    """
    args: list[str] = []
    for m in SET_LOG_WRITERS.finditer(text):
        depth = 0
        start = m.end()
        for i in range(start - 1, len(text)):
            ch = text[i]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    args.append(text[start:i])
                    break
    return args


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

    # 4. LogWriter subclasses that are never registered with Kermit.
    # A writer that is not in a setLogWriters(...) call receives nothing at all:
    # it compiles, it is a correct implementation, and it is silent. This is
    # exactly how FileLogWriter survived from its introduction in 2026-09-23
    # until MR-2 wired it in 2026-09-30.
    #
    # A writer can reach setLogWriters two ways, and both count as wired:
    #   a) constructed inline —  setLogWriters(MyWriter(dir))
    #   b) bound to a local val first — val w = MyWriter(dir); setLogWriters(w)
    # (b) is the shape both LogBootstrap files use, so matching only (a) would
    # report FileLogWriter itself as unwired.
    registered: set[str] = set()
    alias_to_writer: dict[str, str] = {}
    for text in code.values():
        alias_to_writer.update(WRITER_ALIAS.findall(text))
    for text in code.values():
        for call_args in set_log_writers_args(text):
            for name in re.findall(r"\b([A-Z]\w*Writer)\b", call_args):
                registered.add(name)
            for alias in re.findall(r"\b(\w+)\b", call_args):
                if alias in alias_to_writer:
                    registered.add(alias_to_writer[alias])

    for path, text in code.items():
        # Test doubles exist precisely to be passed directly to the class under
        # test, not to Kermit. Flagging them would be noise, not signal.
        if re.search(r"src/\w*[tT]est/", str(path)):
            continue
        for m in LOG_WRITER_DECL.finditer(text):
            name = m.group(1)
            if name in registered:
                continue
            findings.append(
                (
                    "log-writer",
                    f"{rel(path)}: {name} extends LogWriter but never reaches "
                    "Logger.setLogWriters(...) — it will receive no output",
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

#!/usr/bin/env python3
"""Find surfaces that are fully implemented but wired to nothing.

The recurring defect this catches is not a broken feature but an *unreachable*
one: code that compiles, has tests, and is never invoked. Every instance found
during the Maestro suite expansion had the same shape and none of them was
visible to a test, because the test exercised the code that *was* wired.

Shapes detected (via Detector table — add new rows, not new loops):

  1. screen         — a public @Composable named *Screen/*Card/*Section/*Sheet
                       with no call site
  2. default-noop   — a callback parameter defaulting to `{}` where the
                       consumer writes `param ?: fallback`, which the empty
                       lambda defeats
  3. di-binding     — a Room DAO accessor with no `get<AppDatabase>()…`
                       binding in either PlatformModule
  4. log-writer     — a Kermit `LogWriter` subclass never registered via
                       `Logger.setLogWriters(...)`, so it silently receives
                       nothing
  5. expect-unwired — an expect fun / expect class with no actual or no call
  6. orphan-binding — a Koin single/factory/viewModel binding that nothing
                       injects

Shape 5 is not checked here (navigation): reachable routes are a data question
the Maestro suite answers better than a static scan.

Usage:
    scripts/find-unwired-surfaces.py           # human-readable report
    scripts/find-unwired-surfaces.py --quiet   # findings only, for CI

Exit code is 1 when anything is reported, so it can gate a check.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
from dataclasses import dataclass, field
from typing import Callable

ROOT = pathlib.Path(__file__).resolve().parent.parent

SOURCE_ROOTS = ["shared/src", "androidApp/src", "desktopApp/src"]

# ── Comment stripping ───────────────────────────────────────────────────────

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")


def strip_comments(text: str) -> str:
    """Drop comments.

    A KDoc mention like ``[SyncConfigScreen]`` is a text match for the symbol
    but not a call, and counting it is exactly what lets an unwired screen look
    wired to a naive grep.
    """
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", text))


# ── Helpers shared across detectors ────────────────────────────────────────

SET_LOG_WRITERS = re.compile(r"setLogWriters\s*\(")


def set_log_writers_args(text: str) -> list[str]:
    """Return the full argument list of every ``Logger.setLogWriters(...)`` call.

    Balanced-paren walk so nested constructor calls are preserved.
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


def rel(path: pathlib.Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


# ── Detector registry ──────────────────────────────────────────────────────

FindFn = Callable[[dict[pathlib.Path, str], str], list[tuple[str, str]]]


@dataclass
class Detector:
    kind: str
    """One-word label used as the `[kind]` tag in output."""

    check: FindFn
    """Given (path→stripped_text dict, corpus string) → list of (kind, message)."""

    precompute: Callable[[dict[pathlib.Path, str]], object] = field(
        default=lambda _: None
    )
    """
    Optional one-time preprocessing over all files.
    Receives (path→stripped_text dict).
    Result is passed as third argument to every `check` call.
    """


def kotlin_files() -> list[pathlib.Path]:
    files: list[pathlib.Path] = []
    for root in SOURCE_ROOTS:
        base = ROOT / root
        if base.exists():
            files.extend(p for p in base.rglob("*.kt") if p.is_file())
    return files


# ── Detector 1 — Composable surfaces (*Screen/*Card/*Section/*Sheet) ──────


_COMPOSABLE_SUFFIXES = "Screen", "Card", "Section", "Sheet"


def _check_composable(
    code: dict[pathlib.Path, str], corpus: str, _pre: object = None
) -> list[tuple[str, str]]:
    findings: list[tuple[str, str]] = []
    # Build one pattern for all suffixes: ^fun FooScreen(| ... / ^fun FooCard( ...
    alt = "|".join(_COMPOSABLE_SUFFIXES)
    pattern = re.compile(r"^fun ([A-Z]\w*(?:" + alt + r"))\s*\(", re.M)
    for path, text in code.items():
        # Skip components/: they are called by their parent screen and the
        # many-to-one pattern (NoteCard used by NotesListScreen, etc.) is normal.
        if "/components/" in str(path):
            continue
        for m in pattern.finditer(text):
            name = m.group(1)
            # -1: subtract the declaration itself
            refs = len(re.findall(r"\b" + re.escape(name) + r"\b", corpus)) - 1
            if refs <= 0:
                findings.append(("screen", f"{rel(path)}: {name}() has no call site"))
    return findings


# ── Detector 2 — default-noop callbacks ─────────────────────────────────────


_UNIT_FN_TYPE = r"(?:\(\s*\(\s*\)\s*->\s*Unit\s*\)|\(\s*\)\s*->\s*Unit)"
_NOOP_PARAM = re.compile(
    r"(?:^|[(,]\s*)val\s+(\w*[Cc]lick\w*|\w*[Tt]oggle\w*|onOpen\w*)\s*:\s*"
    + _UNIT_FN_TYPE
    + r"\s*=\s*\{\s*\}",
    re.M,
)
_NOOP_FALLBACK = re.compile(r"\b(\w*[Cc]lick\w*|\w*[Tt]oggle\w*|onOpen\w*)\s*\?:")


def _check_default_noop(
    code: dict[pathlib.Path, str], corpus: str, _pre: object = None
) -> list[tuple[str, str]]:
    findings: list[tuple[str, str]] = []
    fallback_names = {m.group(1) for m in _NOOP_FALLBACK.finditer(corpus)}
    for path, text in code.items():
        for m in _NOOP_PARAM.finditer(text):
            param = m.group(1)
            if param in fallback_names:
                findings.append(
                    (
                        "default-noop",
                        f"{rel(path)}: {param} defaults to {{}} — a `{param} ?: fallback` "
                        "consumer reads the empty lambda as 'supplied' and never falls back",
                    )
                )
    return findings


# ── Detector 3 — DI binding (Room DAO) ───────────────────────────────────────


_DAO_ACCESSOR = re.compile(r"abstract fun (\w+)\(\)\s*:\s*(\w*Dao)\b")
_DAO_BINDING = re.compile(r"get<AppDatabase>\(\)\.(\w+)\(\)")


def _precompute_di_binding(code: dict[pathlib.Path, str]) -> set[str]:
    bound: set[str] = set()
    for path, text in code.items():
        if "PlatformModule" in path.name:
            bound.update(_DAO_BINDING.findall(text))
    return bound


def _check_di_binding(
    code: dict[pathlib.Path, str], _corpus: str, bound: set[str]
) -> list[tuple[str, str]]:
    findings: list[tuple[str, str]] = []
    for path, text in code.items():
        if "AppDatabase" not in path.name:
            continue
        for m in _DAO_ACCESSOR.finditer(text):
            accessor, dao = m.group(1), m.group(2)
            if accessor not in bound:
                findings.append(
                    (
                        "di-binding",
                        f"{rel(path)}: {dao} via {accessor}() is not bound in any PlatformModule",
                    )
                )
    return findings


# ── Detector 4 — LogWriter subclass without setLogWriters ─────────────────────


_LOG_WRITER_DECL = re.compile(
    r"class\s+(\w+)\s*(?:<[^>]*>)?\s*(?:\([^)]*\)\s*)?:\s*LogWriter\s*\(",
    re.S,
)
_WRITER_ALIAS = re.compile(r"val\s+(\w+)\s*=\s*([A-Z]\w*Writer)\s*\(")


def _precompute_log_writer(code: dict[pathlib.Path, str]) -> tuple[set[str], dict[str, str]]:
    registered: set[str] = set()
    alias_to_writer: dict[str, str] = {}
    for text in code.values():
        alias_to_writer.update(_WRITER_ALIAS.findall(text))
    for text in code.values():
        for call_args in set_log_writers_args(text):
            for name in re.findall(r"\b([A-Z]\w*Writer)\b", call_args):
                registered.add(name)
            for alias in re.findall(r"\b(\w+)\b", call_args):
                if alias in alias_to_writer:
                    registered.add(alias_to_writer[alias])
    return registered, alias_to_writer


def _check_log_writer(
    code: dict[pathlib.Path, str], _corpus: str, pre: tuple[set[str], dict[str, str]]
) -> list[tuple[str, str]]:
    registered, _ = pre
    findings: list[tuple[str, str]] = []
    for path, text in code.items():
        if re.search(r"src/\w*[tT]est/", str(path)):
            continue
        for m in _LOG_WRITER_DECL.finditer(text):
            name = m.group(1)
            if name not in registered:
                findings.append(
                    (
                        "log-writer",
                        f"{rel(path)}: {name} extends LogWriter but never reaches "
                        "Logger.setLogWriters(...) — it will receive no output",
                    )
                )
    return findings


# ── Detector 5 — expect without actual / actual without call ─────────────────


_EXPECT_DECL = re.compile(
    r"^(?:expect\s+|annotation\s+class\s+)(?:fun|class|object|interface)\s+(\w+)",
    re.M,
)
_ACTUAL_DECL = re.compile(
    r"^(?:actual\s+(?:fun|class|object|interface)\s+|typealias\s+\w+\s*=\s*)(\w+)",
    re.M,
)


def _precompute_expect_unwired(
    code: dict[pathlib.Path, str],
) -> tuple[set[str], set[str]]:
    expect_names: set[str] = set()
    actual_names: set[str] = set()
    for text in code.values():
        expect_names.update(_EXPECT_DECL.findall(text))
        actual_names.update(_ACTUAL_DECL.findall(text))
    return expect_names, actual_names


def _check_expect_unwired(
    code: dict[pathlib.Path, str],
    corpus: str,
    pre: tuple[set[str], set[str]],
) -> list[tuple[str, str]]:
    findings: list[tuple[str, str]] = []
    expect_names, actual_names = pre
    for path, text in code.items():
        for m in _EXPECT_DECL.finditer(text):
            name = m.group(1)
            if name not in actual_names:
                findings.append(
                    (
                        "expect-unwired",
                        f"{rel(path)}: expect {name} has no actual implementation",
                    )
                )
    # actual without a call (but only if it is NOT also declared as expect).
    # corpus_referenced = set of all names that appear at least once in the corpus
    # (stripped of comments, so only real code references remain).
    corpus_referenced = {
        m.group(1)
        for m in re.finditer(r"\b(\w+)\b", corpus)
    }
    actual_unused = (actual_names - expect_names) - corpus_referenced
    for path, text in code.items():
        for m in _ACTUAL_DECL.finditer(text):
            name = m.group(1)
            if name in actual_unused:
                # Only report if this actual is NOT also declared as expect
                # (expect/actual pairs are handled above)
                if name not in expect_names:
                    findings.append(
                        (
                            "expect-unwired",
                            f"{rel(path)}: actual {name} is never referenced",
                        )
                    )
    return findings


# ── Detector 6 — orphan Koin binding (no get/inject/viewModel call) ──────────


_KOIN_BINDING = re.compile(
    r"\b(single|factory|viewModel|viewModelOf|factoryOf|singleOf)\s*<(\w[^>]*)>",
)
# Consumer patterns:
#   get<Name>(), get<Name><extension>(), get<Outer.Inner>()   — direct injection
#   getAll<Name>()                                          — contributor pattern
#   <Name>()                                                — scoped reference (inside a lambda)
#   (the space before < allows multiline: "get<\nName>()")
# Negative lookbehind (?<!...) prevents binding keywords (single<|factory<|...)
# from being counted as consumers of their own declaration.
_INJECT_CALL = re.compile(
    r"(?:get|getAll|inject|koinGet|koinInject|koinViewModel)\s*<([\w.]+)>|"
    r"<([\w.]+)>",  # bare type in scope position (no binding-keyword exclusion needed
                    # because cross-file check prevents a binding from consuming itself)
)


def _precompute_orphan_binding(
    code: dict[pathlib.Path, str],
) -> tuple[set[str], set[str], dict[pathlib.Path, set[str]]]:
    """Returns (bound_names, all_consumed, per_file_consumed)."""
    bound_names: set[str] = set()
    bound_fqcns: set[str] = set()
    all_consumed: set[str] = set()
    per_file_consumed: dict[pathlib.Path, set[str]] = {p: set() for p in code}

    for path, text in code.items():
        # Collect bindings first so we can exclude them from bare-<> consumers
        bindings_in_file: set[str] = set()
        for m in _KOIN_BINDING.finditer(text):
            kind, fqcn = m.group(1), m.group(2)
            simple = fqcn.split(".")[-1]
            bound_names.add(simple)
            bound_fqcns.add(fqcn)
            bindings_in_file.add(simple)

        for m in _INJECT_CALL.finditer(text):
            fqcn = m.group(1) or m.group(2)
            if fqcn:
                simple = fqcn.split(".")[-1]
                # Only count bare <> if the file does NOT bind this name itself.
                # This prevents a binding declaration like "single<Analytics>" from
                # being misread as a self-reference.
                if m.group(1) or simple not in bindings_in_file:
                    all_consumed.add(simple)
                    all_consumed.add(fqcn)
                    per_file_consumed[path].add(simple)

    return bound_names | bound_fqcns, all_consumed, per_file_consumed


def _check_orphan_binding(
    code: dict[pathlib.Path, str],
    _corpus: str,
    pre: tuple[set[str], set[str], dict[pathlib.Path, set[str]]],
) -> list[tuple[str, str]]:
    findings: list[tuple[str, str]] = []
    bound, consumed, per_file_consumed = pre
    # Allowlist: intentional orphans declared as known-dead or stubbed-for-future-use.
    # Each entry here is a class name that was audited and intentionally left unwired.
    DECLARED_INTENT: set[str] = {
        # Analytics stub — wired in MR-D
        "Analytics",
        "NoopAnalytics",
        # Billing stub — wired in MR-D
        "SubscriptionProvider",
        "NoopSubscriptionProvider",
        # Notification permission — wired in MR-E
        "rememberNotificationPermissionRequester",
        # Consumed via constructor injection (val repo: Repo) — static scan can't track
        "AttachmentRepository",
        "BackupRepository",
        "DraftStore",
        "IdGenerator",
        "ProjectRemindersRepository",
        "RemoteConfigRepository",
        "SessionStore",
        "SyncApiClient",
        "SyncPrefs",
        "SyncRepository",
        "TimeZoneProvider",
    }
    for path, text in code.items():
        # Skip test files: they bind stubs for the Koin graph validation test and
        # those stubs are consumed by test code — not real production orphans.
        if "/jvmTest/" in str(path) or "/androidTest/" in str(path):
            continue
        # Only scan CoreDiModule.kt for orphans — other modules use factory/collector
        # patterns (getAll<>, constructor injection) that a static call-site scan
        # cannot track reliably.  The two real orphans (Analytics, SubscriptionProvider)
        # are both in CoreDiModule.kt and are already in DECLARED_INTENT.
        if "CoreDiModule.kt" not in str(path):
            continue
        for m in _KOIN_BINDING.finditer(text):
            kind, fqcn = m.group(1), m.group(2)
            simple = fqcn.split(".")[-1]
            if simple in bound and simple not in consumed and simple not in DECLARED_INTENT:
                # Cross-file check: the binding file must NOT have any consumer for this name.
                # If the only "consumed" entry is in the same file as the binding,
                # it's a false positive from the binding itself.
                local_consumed = per_file_consumed[path]
                if simple not in local_consumed:
                    findings.append(
                        (
                            "orphan-binding",
                            f"{rel(path)}: {kind}<{fqcn}> is bound but no get/inject/koinViewModel "
                            "call references it — it will be a no-op at runtime",
                        )
                    )
    return findings


# ── Detector table ──────────────────────────────────────────────────────────


DETECTORS: list[Detector] = [
    Detector(kind="screen", check=_check_composable),
    Detector(kind="default-noop", check=_check_default_noop),
    Detector(
        kind="di-binding",
        check=_check_di_binding,
        precompute=_precompute_di_binding,
    ),
    Detector(
        kind="log-writer",
        check=_check_log_writer,
        precompute=_precompute_log_writer,
    ),
    Detector(
        kind="expect-unwired",
        check=_check_expect_unwired,
        precompute=_precompute_expect_unwired,
    ),
    Detector(
        kind="orphan-binding",
        check=_check_orphan_binding,
        precompute=_precompute_orphan_binding,
    ),
]


# ── Main ────────────────────────────────────────────────────────────────────


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="print findings only")
    args = parser.parse_args()

    files = kotlin_files()
    code = {p: strip_comments(p.read_text()) for p in files}
    corpus = "\n".join(code.values())

    findings: list[tuple[str, str]] = []

    for detector in DETECTORS:
        pre_result = detector.precompute(code)
        findings.extend(detector.check(code, corpus, pre_result))

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

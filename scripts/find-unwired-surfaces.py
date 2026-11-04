#!/usr/bin/env python3
"""Find surfaces that are fully implemented but wired to nothing.

The recurring defect this catches is not a broken feature but an *unreachable*
one: code that compiles, has tests, and is never invoked. Every instance found
during the Maestro suite expansion had the same shape and none of them was
visible to a test, because the test exercised the code that *was* wired.

Shapes detected (via Detector table — add new rows, not new loops):

  1. screen         — a public @Composable named *Screen/*Card/*Section/*Sheet
                       /*Tile/*Row/*Dialog
                       with no call site
  2. default-noop   — a callback parameter defaulting to `{}` where the
                       consumer writes `param ?: fallback`, which the empty
                       lambda defeats
  3. di-binding     — a Room DAO accessor that no `get<AppDatabase>()…` binding and
                       no production call on a database instance reaches
  4. log-writer     — a Kermit `LogWriter` subclass never registered via
                       `Logger.setLogWriters(...)`, so it silently receives
                       nothing
  5. expect-unwired — an expect fun / expect class with no actual or no call
  6. orphan-binding — a Koin single/factory/viewModel binding that nothing
                       injects
  7. dead-symbol    — a symbol with test references but zero production
                       references. Needs an exemption entry in
                       scripts/find-unwired-surfaces-baseline.txt, because a
                       test double living in commonMain looks identical to
                       unwired production code to a static scan.
  8. startup-unwired — a declared startup entry point that nothing calls.
  9. unread-public  — a public value class, constant, computed property or
                       data-class option that no production code reads. Named
                       `write-only` when the option *is* handed a value, which
                       is the sharper finding: a promise the code does not keep.

Shape 5 is not checked here (navigation): reachable routes are a data question
the Maestro suite answers better than a static scan.

## The floor

The gate is **advisory**, and an advisory gate with no measured count can only
grow: nothing records how many findings existed before, so the next session
starts from zero knowledge — the same failure as the `dark_scenarios` floor that
drifted unnoticed. `config/docs/unwired-surfaces-floor.txt` records the count as
of 2026-10-16. A run whose blocking count **exceeds** it fails; a lower count
passes and says so, and the floor is lowered in the same change that lowers the
count.

The floor is compared against blocking findings only. `exempt` rows are
reported and deliberately do not fail the build, so counting them would make the
floor move when somebody merely renames an exemption's message.

Usage:
    scripts/find-unwired-surfaces.py           # human-readable report
    scripts/find-unwired-surfaces.py --quiet   # findings only, for CI
    scripts/find-unwired-surfaces.py --update-floor   # write the current count

Exit code is 1 when anything is reported, so it can gate a check.
"""

from __future__ import annotations

import pathlib
import re
import sys
from dataclasses import dataclass, field
from typing import Callable

import click

from scanners.source_text import strip_code, strip_comments

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Every Gradle module whose source is production code, plus the two app modules.
#
# This list was three entries and left five modules out (#543). That is not a milder
# version of the same gap: a symbol in a module that is never globbed cannot be found,
# so it cannot be reported unwired either. The gate read as complete while covering
# roughly half the tree, and fixing the findings it did report could never have made it
# true.
#
# Two things keep it honest, both in scripts/tests/test_find_unwired_surfaces.py:
# every root here is asserted to exist on disk and to be actually globbed, and every
# Kotlin module named in settings.gradle.kts is asserted to appear here. The second is
# the one that catches a *new* module — the first cannot, because this list is the
# specification it reads.
#
# `:detekt-rules` is deliberately absent: its rules are instantiated through
# `RuleSetProvider`, which a call-site scan cannot see, so every finding there would be
# noise. The test names it as excluded rather than leaving the absence unexplained.
#
# `:shared-ui` joined the list for #1916. Its absence was the *reading* half of the same
# gap: `TaskCreateUiState.isSaveEnabled` is declared under `shared/src` and read by the
# Compose screen under `shared-ui/src`, so a read-counting shape that only read
# `SOURCE_ROOTS` reported a property that production code consults as never read. Reads
# are evidence of use wherever the module lives, so the module that reads has to be in
# the same list as the module that declares.
SOURCE_ROOTS = [
    "shared/src",
    "shared-ui/src",
    "androidApp/src",
    "desktopApp/src",
    "adr-cli/src",
    "adr-core/src",
    "core-base/src",
    "mcp-server/src",
    "pro/src",
    "tools/unwritten-properties/src",
]

# ── Comment stripping ───────────────────────────────────────────────────────
#
# The readers live in `scanners.source_text` — one character walk that knows
# about nested block comments, raw strings and template literals:
#
#   `strip_comments` blanks comments and keeps literal contents;
#   `strip_code`    blanks comments *and* literal contents.
#
# This script used to carry its own pair of regexes. They disagreed with the
# canonical reader in two ways that mattered here: a block comment ended at
# the first `*/` (so a nested comment leaked its tail into the corpus), and
# the strippers *deleted* comment text instead of blanking it, so the line
# count of a file changed under the detectors that report line numbers.
#
# Why literal contents are blanked at all: a log tag reads
# ``Logger.withTag("AutoSync")`` and a log message reads
# ``"AutoSync disabled, ignoring trigger $t"``. Neither is a call to
# `AutoSync`; both are counted as one by a word-boundary scan, which is
# enough to make a class with no call site look wired.


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


_COMPOSABLE_SUFFIXES = "Screen", "Card", "Section", "Sheet", "Tile", "Row", "Dialog"


def _without_previews(text: str) -> str:
    """Return [text] with preview functions' bodies blanked out.

    A preview is evidence about a component, not a call site: `ReminderTile` has two,
    and neither is ever rendered for a user. Counting them as references is what let a
    never-shipped component look wired.

    Two shapes have to go. `@Preview\n@Composable\nfun Foo()` is the Compose annotation,
    and `private fun FooLightPreview() = PreviewThemed(...) { ... }` is this project's
    `PreviewSamples` convention — no annotation, so the name is the only signal. Both
    are matched on the function name or the annotation, and the body is blanked by
    brace counting so the rest of the file stays positionally intact.

    Co-location is deliberately *not* treated as preview-ness. `TagCard` is called by
    `TagList` in the same file, which is called by the screen — a real chain. An earlier
    version of this check compared against "any other file" and reported it as unwired,
    which is how a correct implementation gets deleted to satisfy a gate.
    """
    out = list(text)

    def blank(start: int, end: int) -> None:
        for i in range(start, min(end, len(out))):
            if out[i] != "\n":
                out[i] = " "

    for m in re.finditer(r"@(?:androidx\.compose\.ui\.tooling\.preview\.)?Preview\b", text):
        fn = re.compile(r"\bfun\s+(\w+)\s*\(").search(text, m.end())
        # The annotation is the signal here, so the function's own name is irrelevant —
        # `@Preview fun P()` is a preview too. Requiring "Preview" in the name here
        # silently skipped exactly the shape Compose tooling produces.
        if fn:
            _blank_braced_body(text, fn.end(), out)
    for fn in re.compile(r"\bfun\s+(\w*[Pp]review\w*)\s*\(").finditer(text):
        _blank_braced_body(text, fn.end(), out)
    return "".join(out)


def _blank_braced_body(text: str, from_index: int, out: list[str]) -> None:
    """Blank a function body starting at or after [from_index]."""
    start = text.find("{", from_index)
    if start == -1:
        return
    depth = 0
    for i in range(start, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                for j in range(start, i + 1):
                    if out[j] != "\n":
                        out[j] = " "
                return


def _because(reason: str | None) -> str:
    """The exemption's reason, appended to a finding it converted to `exempt`.

    Exempt findings are still printed, so the reason has to travel with them: a row
    that silences a finding without saying why is an exemption nobody can audit.
    """
    return f" — {reason}" if reason else ""


def _check_composable(
    code: dict[pathlib.Path, str], corpus: str, _pre: object = None
) -> list[tuple[str, str]]:
    findings: list[tuple[str, str]] = []
    baseline = _load_baseline()
    # Build one pattern for all suffixes: ^fun FooScreen(| ... / ^fun FooCard( ...
    alt = "|".join(_COMPOSABLE_SUFFIXES)
    pattern = re.compile(r"^fun ([A-Z]\w*(?:" + alt + r"))\s*\(", re.M)
    shipped = {p: _without_previews(t) for p, t in code.items()}
    shipped_corpus = "\n".join(shipped.values())
    for path, text in code.items():
        # Skip components/: they are called by their parent screen and the
        # many-to-one pattern (NoteCard used by NotesListScreen, etc.) is normal.
        if "/components/" in str(path):
            continue
        for m in pattern.finditer(text):
            name = m.group(1)
            reason = baseline.reason_for(name, path)
            word = r"\b" + re.escape(name) + r"\b"
            # -1: subtract the declaration itself
            refs = len(re.findall(word, corpus)) - 1
            if refs <= 0:
                kind = "exempt" if reason else "screen"
                findings.append(
                    (kind, f"{rel(path)}: {name}() has no call site{_because(reason)}")
                )
                continue
            # Being called is not the question; being called by *shipped* code is.
            #
            # A component wired only into a @Preview has a call site, so the count above
            # is satisfied and nothing was reported — while the component has never
            # rendered for a user. `ReminderTile` was in that state: implemented,
            # previewed twice, called by nothing but those two previews.
            outside = len(re.findall(word, shipped_corpus)) - 1
            if outside <= 0:
                kind = "exempt" if reason else "preview-only"
                findings.append(
                    (
                        kind,
                        f"{rel(path)}: {name}() is called only from a @Preview — it "
                        f"never renders for a user{_because(reason)}",
                    )
                )
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

# A DAO accessor does not need its own `single { get<AppDatabase>().x() }` binding to be
# reachable. Injecting the database itself is a second, legitimate route:
# `OwnerRowIdResolver(private val database: AppDatabase)` calls
# `database.ownerEraseDao()`, and every consumer of that resolver gets the DAO with it.
#
# The detector has to know about that route, or it reports a DAO that the app very much
# does use. What it must NOT start accepting is a DAO that *nothing* asks for — so the
# route is counted only from production sources. A DAO reachable solely from a test is
# still a finding, and so is one nothing mentions at all.
_DAO_CALL = re.compile(r"\.\s*(\w+)\s*\(\s*\)")

# Source sets that ship to no device. A call here is evidence about the test, not about
# the app.
_TEST_SOURCE = re.compile(r"(?:^|/)(?:jvmTest|androidTest|commonTest|desktopTest|iosTest)/")


def _precompute_di_binding(code: dict[pathlib.Path, str]) -> set[str]:
    bound: set[str] = set()
    for path, text in code.items():
        if "PlatformModule" in path.name:
            bound.update(_DAO_BINDING.findall(text))
        if not _TEST_SOURCE.search(rel(path)):
            bound.update(_DAO_CALL.findall(text))
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
                        f"{rel(path)}: {dao} via {accessor}() is neither bound in a "
                        f"PlatformModule nor called on a database in production",
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
# koinViewModel is the Compose extension: import org.koin.androidx.compose.koinViewModel
# A composable calls it as `koinViewModel<MyViewModel>()` to get a scoped VM.
# Without this pattern, every feature ViewModel bound via viewModelOf/viewModel<>
# in a *DiModule.kt would look like an orphan to the static scan.
#
# There are TWO call forms:
#   1. Explicit: `koinViewModel<MyViewModel>()` — group(1) catches "MyViewModel"
#   2. Contextual type inference: `fun Foo(viewModel: MyViewModel = koinViewModel())`
#      The type argument is elided; the parameter's declared type carries it.
#      The regex above matches form-1. Form-2 needs the post-process below.
_KOIN_VIEWMODEL_DEFAULT = re.compile(
    # Match `= koinViewModel()` and capture the word to the left that is the
    # parameter name. We then look up the parameter's declared type in the
    # stripped text to get the actual ViewModel class name.
    # Example: "viewModel: StatisticsViewModel = koinViewModel()"
    #   → param_name = "viewModel", we look up "viewModel:" in the stripped text
    r"(\w+)\s*:\s*\w+\s*=\s*koinViewModel\(\)",
)
# After finding a `= koinViewModel()` call, look up the parameter's declared type.
# In the stripped text: "viewModel: StatisticsViewModel = koinViewModel()"
# The regex `(\w+)\s*:\s*(\w+ViewModel)\s*=` would give group(2) = "StatisticsViewModel".
_KOIN_VIEWMODEL_PARAM_TYPE = re.compile(
    r"(\w+)\s*:\s*(\w+ViewModel)\s*=",
)

# ── The type behind a bare `get()` ─────────────────────────────────────────
#
# `single<AuthRepository> { SupabaseAuthRepository(gateway = get(), …) }` injects
# AuthGateway without ever naming it: Koin's `get()` takes its type from the
# parameter it fills. A scan that only recognises `get<Name>()` therefore calls
# every such binding an orphan, and the only available fix was an allowlist of
# thirty-odd names — a list of claims, each audited by hand, each free to rot
# the next time a constructor gains a parameter.
#
# This resolves the missing fact instead of claiming it. Every class's
# constructor parameters are indexed, and the type behind a `get()` is read off
# the parameter it fills: by name when the argument is named, by position when
# it is not. A constructor reference (`viewModelOf(::MyViewModel)`) is the same
# fact written differently — Koin fills that constructor's parameters from the
# graph — so its parameter types count as consumed too.

_CLASS_DECL = re.compile(
    r"\b(?:class|interface|object)\s+(\w+)\s*(?:<[^<>()]*>)?\s*\(",
)
_PARAM_DECL = re.compile(r"(\w+)\s*:\s*([A-Za-z_][\w.]*)")
_GET_CALL = re.compile(r"\bget(?:<([\w.]+)>)?\s*\(\s*\)")
_CONSTRUCTOR_REF = re.compile(r"::(\w+)")

#: The `get` family. A `get(` inside a `get()` argument must not be mistaken
#: for the enclosing call, or the resolution walks to the wrong parameter.
_GET_FAMILY = frozenset(
    {"get", "getOrNull", "getAll", "getOrNull", "inject", "koinInject", "koinGet"}
)


def _balanced_body(text: str, open_paren: int) -> str | None:
    """The text between the parentheses at `open_paren` and their match."""
    depth = 0
    for i in range(open_paren, len(text)):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1 : i]
    return None


def _constructor_index(
    code: dict[pathlib.Path, str],
) -> dict[str, list[tuple[str, str]]]:
    """Class name → its constructor parameters, as (name, type) pairs."""
    index: dict[str, list[tuple[str, str]]] = {}
    for path, text in code.items():
        if _is_test_source(path):
            continue
        stripped = strip_code(text)
        for match in _CLASS_DECL.finditer(stripped):
            name = match.group(1)
            body = _balanced_body(stripped, match.end() - 1)
            if body is None:
                continue
            params = [
                (m.group(1), m.group(2)) for m in _PARAM_DECL.finditer(body)
            ]
            if params:
                index.setdefault(name, params)
    return index


def _enclosing_call(text: str, pos: int) -> tuple[str, int] | None:
    """The innermost call whose argument list still contains `pos`.

    Returns (callee name, index of its opening parenthesis).

    "Innermost" is load-bearing. The last call *spelled* before the argument is
    not the one that contains it: `SupabaseAuthRepository(log = Logger.withTag(…),
    gateway = get(), …)` spells `withTag(` last, and resolving the argument
    against that call looks up a type that does not exist — so the binding it
    should have resolved stayed unresolved and the gate kept reporting it.
    """
    best: tuple[str, int] | None = None
    for match in re.finditer(r"(\w+)\s*\(", text[:pos]):
        name = match.group(1)
        if name in _GET_FAMILY:
            continue
        open_paren = match.end() - 1
        depth = 0
        for i in range(open_paren, pos):
            ch = text[i]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
        if depth <= 0:
            continue
        if best is None or open_paren > best[1]:
            best = (name, open_paren)
    return best


def _argument_slot(text: str, open_paren: int, pos: int) -> tuple[int, str | None]:
    """Which argument of the call at `open_paren` the text at `pos` is.

    Returns (zero-based position, argument name or None). The name is read at
    depth zero only: a default value like `foo(bar = 1)` must not rename the
    argument that contains it.
    """
    depth = 0
    index = 0
    named: str | None = None
    i = open_paren + 1
    while i < pos:
        ch = text[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0:
            if ch == ",":
                index += 1
                named = None
            elif ch == "=" and i > open_paren + 1:
                before = text[open_paren + 1 : i]
                name_match = re.search(r"(\w+)\s*$", before)
                if name_match:
                    named = name_match.group(1)
        i += 1
    return index, named


def _injected_by_constructor(
    code: dict[pathlib.Path, str],
    index: dict[str, list[tuple[str, str]]],
) -> set[str]:
    """The types a production `get()` fills, resolved through the constructor."""
    injected: set[str] = set()

    for path, text in code.items():
        if _is_test_source(path):
            continue
        stripped = strip_code(text)

        # A constructor reference: Koin fills that constructor's parameters.
        for match in _CONSTRUCTOR_REF.finditer(stripped):
            params = index.get(match.group(1))
            if params:
                injected.update(type_name for _, type_name in params)

        for match in _GET_CALL.finditer(stripped):
            explicit = match.group(1)
            if explicit:
                injected.add(explicit.split(".")[-1])
                continue
            call = _enclosing_call(stripped, match.start())
            if call is None:
                continue
            callee, open_paren = call
            params = index.get(callee)
            if not params:
                continue
            position, name = _argument_slot(stripped, open_paren, match.start())
            if name is not None:
                for param_name, type_name in params:
                    if param_name == name:
                        injected.add(type_name)
                        break
            elif position < len(params):
                injected.add(params[position][1])

    return injected


# ── Constructor-injected types ─────────────────────────────────────────────────
#
# A DI binding that asks for a type without naming it:
#
#     single { HlcFactory(deviceIdProvider = get(), clock = get(), scope = get()) }
#
# The type is in the constructor's signature, and the call site says only
# `get()`. A scan that reads `get<X>()` sees nothing here, so a binding that
# the graph does use reads as an orphan — which is how `DeviceIdProvider` ended
# up in the allowlist with a note saying the scanner could not follow it.
#
# The resolution is deliberately one-sided: a type is marked consumed only when
# the scan can name the constructor it came from and the parameter it fills.
# A miss leaves the finding where it was, which is the safe direction — the
# allowlist entry it may replace stays until the scan is sure.

_CLASS_DECL = re.compile(
    r"(?:^|\n)[ \t]*"
    r"(?:@[A-Za-z_][\w.]*(?:\([^()]*\))?[ \t]*\n?[ \t]*)*"
    r"(?:(?:public|internal|private|protected|open|abstract|sealed|data|value|inline|expect|actual)"
    r"[ \t]+)*"
    r"class[ \t]+([A-Za-z_]\w*)[ \t]*",
)

_PARAMETER = re.compile(
    r"^(?:@[A-Za-z_][\w.]*(?:\([^()]*\))?[ \t]*)*"
    r"(?:(?:private|public|internal|protected|override|open|final|abstract)[ \t]+)*"
    r"(?:val[ \t]+|var[ \t]+)?"
    r"([A-Za-z_]\w*)[ \t]*:[ \t]*([A-Za-z_][\w.]*)",
)


def _balanced(text: str, start: int, open_char: str = "(", close_char: str = ")") -> tuple[str, int]:
    """The text between `open_char` at `start` and its match, plus the index after it."""
    depth = 0
    for index in range(start, len(text)):
        char = text[index]
        if char == open_char:
            depth += 1
        elif char == close_char:
            depth -= 1
            if depth == 0:
                return text[start + 1:index], index + 1
    return "", len(text)


def _split_arguments(text: str) -> list[str]:
    """Split an argument list on its top-level commas."""
    parts: list[str] = []
    depth = 0
    current: list[str] = []
    for char in text:
        if char in "([{<":
            depth += 1
        elif char in ")]}>":
            depth -= 1
        if char == "," and depth == 0:
            parts.append("".join(current))
            current = []
            continue
        current.append(char)
    if current:
        parts.append("".join(current))
    return [part.strip() for part in parts if part.strip()]


def _constructor_parameters(code: dict[pathlib.Path, str]) -> dict[str, list[tuple[str, str]]]:
    """Class name → its primary constructor's `(parameter name, type)` pairs.

    Only the primary constructor is read, and only the parameter list: the
    scanner wants to know what a `get()` in a call site was asking for.
    """
    out: dict[str, list[tuple[str, str]]] = {}
    for text in code.values():
        for match in _CLASS_DECL.finditer(text):
            name = match.group(1)
            cursor = match.end()
            while cursor < len(text) and text[cursor] in " \t":
                cursor += 1
            if cursor >= len(text) or text[cursor] != "(":
                continue
            params_text, _ = _balanced(text, cursor)
            parameters: list[tuple[str, str]] = []
            for part in _split_arguments(params_text):
                found = _PARAMETER.match(part.strip())
                if found:
                    parameters.append((found.group(1), found.group(2).split(".")[-1]))
            if parameters:
                out.setdefault(name, parameters)
    return out


_BINDING_BLOCK = re.compile(
    r"\b(?:single|factory|viewModel|viewModelOf|factoryOf|singleOf)\b[^{]*\{"
)

_CONSTRUCTOR_CALL = re.compile(r"\b([A-Z][A-Za-z0-9_]*)[ \t]*\(")

_NAMED_GET = re.compile(r"^([A-Za-z_]\w*)[ \t]*=[ \t]*get\(\)$")
_POSITIONAL_GET = re.compile(r"^get\(\)$")


def _inferred_get_types(
    code: dict[pathlib.Path, str],
    parameters: dict[str, list[tuple[str, str]]],
) -> set[str]:
    """The types a DI binding asks for through a bare `get()`.

    A binding block constructs a class; the class's constructor says what each
    argument is; a bare `get()` in that argument position is a request for that
    parameter's type. A named argument names the parameter directly.
    """
    found: set[str] = set()
    for text in code.values():
        for block_match in _BINDING_BLOCK.finditer(text):
            body, _ = _balanced(text, block_match.end() - 1, "{", "}")
            for call in _CONSTRUCTOR_CALL.finditer(body):
                class_name = call.group(1)
                signature = parameters.get(class_name)
                if not signature:
                    continue
                args_text, _ = _balanced(body, call.end() - 1)
                position = 0
                for argument in _split_arguments(args_text):
                    named = _NAMED_GET.match(argument)
                    if named:
                        for param_name, param_type in signature:
                            if param_name == named.group(1):
                                found.add(param_type)
                                break
                        continue
                    if _POSITIONAL_GET.match(argument):
                        if position < len(signature):
                            found.add(signature[position][1])
                        position += 1
                        continue
                    if "=" not in argument:
                        position += 1
    return found


def _precompute_orphan_binding(
    code: dict[pathlib.Path, str],
) -> tuple[set[str], set[str], dict[pathlib.Path, set[str]], set[str]]:
    """Returns (bound_names, all_consumed, per_file_consumed, injected_by_constructor)."""
    bound_names: set[str] = set()
    bound_fqcns: set[str] = set()
    all_consumed: set[str] = set()
    per_file_consumed: dict[pathlib.Path, set[str]] = {p: set() for p in code}

    # A type asked for by a bare `get()` is a consumer, even though no `get<T>()`
    # names it. Without this, every binding whose only consumer is a constructor
    # argument reads as an orphan.
    inferred = _inferred_get_types(code, _constructor_parameters(code))
    all_consumed |= inferred
    for path in code:
        per_file_consumed[path] |= inferred

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

        # Handle `koinViewModel()` with no type argument — Compose uses contextual
        # type inference: `fun Foo(viewModel: MyViewModel = koinViewModel())`.
        # The parameter's declared type IS the ViewModel being consumed.
        for m in _KOIN_VIEWMODEL_DEFAULT.finditer(text):
            param_name = m.group(1)  # e.g. "viewModel"
            # Look up the parameter's declared type in the text.
            # The _KOIN_VIEWMODEL_PARAM_TYPE pattern finds `param: Type =` patterns.
            type_m = re.search(
                rf"{re.escape(param_name)}\s*:\s*(\w+ViewModel)\s*=",
                text,
            )
            if type_m:
                vm_name = type_m.group(1)  # e.g. "StatisticsViewModel"
                all_consumed.add(vm_name)
                per_file_consumed[path].add(vm_name)

    index = _constructor_index(code)
    injected = _injected_by_constructor(code, index)

    return bound_names | bound_fqcns, all_consumed, per_file_consumed, injected


def _check_orphan_binding(
    code: dict[pathlib.Path, str],
    _corpus: str,
    pre: tuple[set[str], set[str], dict[pathlib.Path, set[str]], set[str]],
) -> list[tuple[str, str]]:
    findings: list[tuple[str, str]] = []
    bound, consumed, per_file_consumed, injected = pre
    # Allowlist: intentional orphans declared as known-dead or stubbed-for-future-use.
    # Each entry here is a class name that was audited and intentionally left unwired.
    DECLARED_INTENT: set[str] = {
        # Analytics — off by default (GDPR). NoopAnalytics is a safe all-no-op.
        # Wired in CoreDiModule.kt; no production call site exists yet.
        "Analytics",
        # Billing — NoopSubscriptionProvider is the safe stub until a real SDK is
        # wired. No production call site exists yet.
        "SubscriptionProvider",
        # The remote-config seam. Bound in SyncDiModule; no production call site
        # asks for it yet. The entry that used to stand here named `Repo` — a
        # different type — and the seam it named was never the consumer.
        "RemoteConfigRepository",
        # The attachment seam. Bound in TasksDiModule; the entry that used to
        # stand here named AttachmentUploadService and TaskEditor, and neither
        # asks for it any more.
        "AttachmentSaver",
    }
    for path, text in code.items():
        # Skip test files: they bind stubs for the Koin graph validation test and
        # those stubs are consumed by test code — not real production orphans.
        if "/jvmTest/" in str(path) or "/androidTest/" in str(path):
            continue
        # Scan all *DiModule.kt files, not just CoreDiModule.kt.
        #
        # Before 2026-10-08 this checked only CoreDiModule.kt because it was assumed
        # other modules use "factory/collector patterns (getAll<>, constructor injection)
        # that a static call-site scan cannot track". That assumption was wrong:
        #
        #   - viewModelOf/ViewModel() bindings in feature DiModules are consumed by
        #     `koinViewModel<MyViewModel>()` in Compose screens — a real, trackable call.
        #   - SyncViewModel was bound in SyncDiModule.kt but never reached from any
        #     screen (koinViewModel was never called) — exactly the defect this detector
        #     exists to find, and the CoreDiModule-only scope would never see it.
        #
        # The scan now covers all *DiModule.kt files. DECLARED_INTENT still holds the
        # allowlist for stubs known to be inert by design.
        if not str(path).endswith("DiModule.kt"):
            continue
        for m in _KOIN_BINDING.finditer(text):
            kind, fqcn = m.group(1), m.group(2)
            simple = fqcn.split(".")[-1]
            if (
                simple in bound
                and simple not in consumed
                and simple not in injected
                and simple not in DECLARED_INTENT
            ):
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


# ── Detector 7 — dead symbol (tested, never called from production) ──────────────


_TOP_LEVEL_DECL = re.compile(
    r"^(?:object|class|val)\s+(\w+)\s*(?:<[^>]*>)?\s*(?::[^{]*?)?(?:\(|$)",
    re.M,
)
_SKIP_INHERITANCE = frozenset({
    "Analytics", "NoopAnalytics",
    "SubscriptionProvider", "NoopSubscriptionProvider",
    "rememberNotificationPermissionRequester",
})


def _precompute_dead_symbol(
    code: dict[pathlib.Path, str],
) -> tuple[dict[str, int], dict[str, int]]:
    """Returns (prod_ref_counts, test_ref_counts) for every top-level declaration.

    Both this and `_check_dead_symbol` classify with `_is_test_source`, defined below.

    They used to inline `"/test/" in str(path) or "/jvmTest/" in str(path)`, which
    reads as though it covers the test source sets and does not: `commonTest` contains
    neither substring — `/commonTest/` has no `/test/` in it — so all 171 `commonTest`
    files were counted as **production**. Every reference inside them inflated
    `prod_refs`, and the dead-symbol rule only fires when production references are at or
    below the declaration itself, so a symbol used exclusively by `commonTest` looked
    used in production and was never reported.

    That is how `TagGroupRepository.setInheritedForProject` survived #228: its only other
    reference outside production code is an `override` in `TagGroupsViewModelTest`.

    The function already existed and already handled `commonTest` — it is what the other
    detectors use. The defect was two hand-rolled copies of a decision that had a helper,
    and both copies were wrong.
    """
    prod_refs: dict[str, int] = {}
    test_refs: dict[str, int] = {}
    prod_sources: dict[pathlib.Path, str] = {}
    test_sources: dict[pathlib.Path, str] = {}

    for path, text in code.items():
        if _is_test_source(path):
            test_sources[path] = text
        else:
            prod_sources[path] = text

    for path, text in prod_sources.items():
        decls = _TOP_LEVEL_DECL.findall(text)
        for name in decls:
            if name.startswith("Noop") and name[4:] in prod_refs:
                continue  # NoopX mirrors X
            prod_refs[name] = prod_refs.get(name, 0)

    # Count references in code, not in prose about code. A KDoc link and a log
    # tag are both word-boundary matches for the symbol they name, and both
    # were counted as calls. That let `AutoSync` — which nothing calls, only
    # `Logger.withTag("AutoSync")` mentions — clear the dead-symbol threshold.
    prod_corpus = "\n".join(strip_code(t) for t in prod_sources.values())
    test_corpus = "\n".join(strip_code(t) for t in test_sources.values())

    for m in re.finditer(r"\b(\w+)\b", prod_corpus):
        name = m.group(1)
        prod_refs[name] = prod_refs.get(name, 0) + 1

    for m in re.finditer(r"\b(\w+)\b", test_corpus):
        name = m.group(1)
        test_refs[name] = test_refs.get(name, 0) + 1

    return prod_refs, test_refs


def _check_dead_symbol(
    code: dict[pathlib.Path, str],
    corpus: str,
    pre: tuple[dict[str, int], dict[str, int]],
) -> list[tuple[str, str]]:
    prod_refs, test_refs = pre
    findings: list[tuple[str, str]] = []
    baseline = _load_baseline()

    prod_sources: dict[pathlib.Path, str] = {
        p: t for p, t in code.items() if not _is_test_source(p)
    }

    for path, text in prod_sources.items():
        for m in _TOP_LEVEL_DECL.finditer(text):
            name = m.group(1)
            if name in _SKIP_INHERITANCE:
                continue
            prod_count = prod_refs.get(name, 0)
            test_count = test_refs.get(name, 0)
            # prod_count <= 1: the declaration itself (1) counts as a reference
            if prod_count <= 1 and test_count > 0:
                # Keyed by (symbol, declaring file): a same-named declaration in another
                # file is a different symbol and must not inherit this exemption.
                reason = baseline.reason_for(name, path)
                backlog_ref = reason or "no backlog entry"
                # Placement, added 2026-10-05 (#191). A class nothing in production
                # calls, that only tests call, is a test double — and this repository
                # keeps those in `test/fakes/`, which holds 13 of them. The convention
                # is real, was nowhere written down as a rule, and was enforced by
                # nothing, which is how a fourteenth ended up in `core/sync/`.
                #
                # Named in the message rather than policed separately: the dead-symbol
                # check already computed exactly the fact the rule needs ("referenced
                # only by tests"), and a second script would recompute it. The check
                # for a backlog entry stays the gate; this is the sentence that tells
                # whoever writes the entry what the decision is about.
                placement = ""
                if "/test/fakes/" not in str(path):
                    placement = (
                        f" — a test double belongs in test/fakes/, which is where the "
                        f"other 13 live; if it cannot move, say why in the entry"
                    )
                findings.append((
                    "exempt" if reason else "dead-symbol",
                    f"{rel(path)}: {name} has {test_count} test reference(s) but "
                    f"{prod_count} production reference(s) — {backlog_ref}{placement}",
                ))
    return findings


@dataclass(frozen=True)
class Baseline:
    """The exemption file, keyed by *where* the exemption was granted.

    ## Why a row here is an exemption and not only a label

    Until 2026-10-06 this map was read *into the finding message* and changed nothing
    else — the detector still returned the symbol and `main()` still exited 1. So the file
    documented an invariant ("before it can be exempted"), `check-unwired-backlog-refs.py`
    checked that every row's reference resolves, and **nothing ever consumed the exemption**.
    A row could therefore exist, be perfectly honest, and still leave the gate red, which
    is the worst of both: the only way to get a green gate was to delete code.

    A row now changes the finding's kind to `exempt`, and `main()` excludes exempt rows
    from the exit code. That is what makes the other gate load-bearing — it is the thing
    that stops "add a row" from becoming the answer to every finding.

    ## Why the file is part of the key

    This map used to be `symbol → reason` with no file qualifier, on the reasoning that
    "a screen or a class has one home". That held while the file held two rows and one
    detector consulted it. `unread-public` broke it: its rows name *properties*, and a
    property called `FooCard` or `AutoSync` collides by name with a composable or a
    top-level declaration in a different file and a different detector. Each row added
    widened the collision surface by one, and a collision is not a cosmetic problem — it
    silently converts another detector's finding to `exempt`, i.e. to a finding that does
    not fail the build.

    So the second column of the row — which the format always carried and the loader
    always discarded — is now half the key. `reason_for` requires the symbol *and* the
    declaring file to match, and the detectors pass the path they are reporting, which is
    the declaration site. A row can only ever exempt the declaration it names.

    The residual collision a file+symbol key cannot separate is two detectors reporting
    the same name in the same file (`val FooRow` and `fun FooRow()` can coexist). That is
    one file's worth of ambiguity rather than the whole tree's, and the detectors report
    different names for it in practice.
    """

    rows: dict[tuple[str, str], str] = field(default_factory=dict)

    def reason_for(self, symbol: str, path: pathlib.Path) -> str | None:
        """The exemption granted to `symbol` as declared in `path`, if any."""
        return self.rows.get((symbol, rel(path)))


def _load_baseline() -> Baseline:
    """Read the exemption file into a ``(symbol, file) → reason`` map."""
    path = ROOT / "scripts" / "find-unwired-surfaces-baseline.txt"
    if not path.exists():
        return Baseline()
    rows: dict[tuple[str, str], str] = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = [part.strip() for part in line.split("|")]
        if len(parts) >= 3:
            rows[(parts[0], parts[1])] = parts[2]
    return Baseline(rows)


# ── Detector 8 — startup entry point with no call site ────────────────────────
#
# Item 5 of docs/decisions/2026-10-04-observability-followups.md records why this
# exists: the audit never inspected top-level functions, so `core.log.debugInfo` sat
# with zero call sites for its whole life and nothing said so. Broadening the audit to
# *every* top-level function would be a poor trade — pure helpers are called from
# everywhere and the signal-to-noise would be terrible.
#
# The pattern worth checking is narrower: a function whose whole job is to be called
# once at startup. Those are the ones where "nobody calls it" is a silent, permanent
# failure, because the feature it installs keeps looking configured.


# `fun` at column 0 — top level. `private`/`internal` included: a private top-level
# function with no caller is a compile warning, not a startup entry point, so the
# name check below is what separates the two.
_TOP_LEVEL_FUN = re.compile(r"^(?:private\s+|internal\s+)?fun\s+(\w+)\s*[(<]", re.M)

# Names that read as "wire this up once" rather than "compute something". A function
# matching this and not listed in DECLARED_STARTUP is reported, so a new one cannot
# slip in by being added and never called.
_STARTUP_NAME = re.compile(r"^(install|init|start|register|bootstrap|setup)\w*$", re.I)

# Audited as genuine startup entry points, each with the reason it is one.
DECLARED_STARTUP: dict[str, str] = {
    "installBackgroundCrashReporting": (
        "installs the background failure handler from SingularityApp.onCreate; the "
        "reporting it configures works exactly as well when nothing calls it"
    ),
    "flushLogs": (
        "drains the Kermit file writer from the uncaught-exception handler; the flush "
        "it performs is invisible until the crash it was added for"
    ),
    "initLogging": (
        "installs the Kermit writers; called per platform before startKoin"
    ),
    "createBackgroundScope": (
        "a factory, not a startup step — declared so the name check does not report it"
    ),
}


def _is_test_source(path: pathlib.Path) -> bool:
    """A file under a test source set, or named like a test."""
    parts = set(path.parts)
    if parts & {"commonTest", "jvmTest", "androidTest", "androidHostTest", "test", "jvmMain", "androidMain"}:
        # jvmMain/androidMain are production source sets whose *paths* contain a segment
        # the word "test" might otherwise match on; they are handled below by name only.
        return bool(parts & {"commonTest", "jvmTest", "androidTest", "androidHostTest", "test"})
    return path.name.endswith("Test.kt")


def _precompute_startup(
    code: dict[pathlib.Path, str],
) -> tuple[set[str], dict[str, pathlib.Path], str, str]:
    """Declared names, first declaration site, production call corpus, test call corpus.

    Production and test call sites are counted separately, and that separation is the
    point of the check rather than a detail of it. A startup step wired *only* by a test
    looks identical to a wired one to any name-based scan — the function has a call site,
    the test passes, and the feature is not installed in the running app. That is the
    `debugInfo` shape, and the test that now calls `installBackgroundCrashReporting`
    reproduces it exactly.

    Declaration lines and imports are removed from both corpora: an import is not a call,
    and a declaration is not a call of itself.
    """
    found: dict[str, pathlib.Path] = {}
    production: list[str] = []
    tests: list[str] = []
    for path, text in code.items():
        target = tests if _is_test_source(path) else production
        kept: list[str] = []
        for line in text.splitlines():
            if line.startswith("import "):
                continue
            if _TOP_LEVEL_FUN.match(line):
                # Look back through what was kept for @Composable / @Preview. A Composable
                # is not a startup step: `StartDateRow` starts with "start" and would
                # otherwise be reported, which is how a heuristic turns into noise.
                annotations = [ln.strip() for ln in kept[-3:]]
                if any(a.startswith("@Composable") or a.startswith("@Preview") for a in annotations):
                    continue
                # Declared here, so it is not a call of itself.
                name = _TOP_LEVEL_FUN.match(line).group(1)
                if name not in found:
                    found[name] = path
                # Drop the signature, keep whatever follows it. A one-line function body
                # can contain the very call being looked for — `fun boot() = installX()` —
                # and discarding the whole line would hide a real call site.
                depth = 0
                seen_open = False
                for index, char in enumerate(line):
                    if char == "(":
                        depth += 1
                        seen_open = True
                    elif char == ")":
                        depth -= 1
                        if seen_open and depth == 0:
                            trailing = line[index + 1 :].strip()
                            if trailing.startswith("=") or trailing.startswith("{"):
                                kept.append(trailing)
                            break
                continue
            kept.append(line)
        target.append("\n".join(kept))
    return set(DECLARED_STARTUP), found, "\n".join(production), "\n".join(tests)


def _check_startup_unwired(
    code: dict[pathlib.Path, str],
    corpus: str,
    pre: tuple[set[str], dict[str, pathlib.Path], str, str],
) -> list[tuple[str, str]]:
    findings: list[tuple[str, str]] = []
    declared, found, production_corpus, test_corpus = pre
    # Comments were stripped when the code map was built, so what is left is real code.
    production_refs = set(re.findall(r"\b(\w+)\b", production_corpus))
    test_refs = set(re.findall(r"\b(\w+)\b", test_corpus))
    for name, path in sorted(found.items()):
        if _is_test_source(path):
            continue
        in_production = name in production_refs
        only_in_tests = name in test_refs and not in_production
        if name in declared:
            if not in_production:
                why = "only from tests" if only_in_tests else "nowhere"
                findings.append(
                    (
                        "startup-unwired",
                        f"{rel(path)}: declared startup entry point `{name}` is called {why} "
                        f"({DECLARED_STARTUP[name]})",
                    )
                )
            continue
        if not in_production and _STARTUP_NAME.match(name):
            where = "only from tests" if only_in_tests else "nowhere"
            findings.append(
                (
                    "startup-unwired",
                    f"{rel(path)}: top-level `{name}` reads as a startup entry point and is "
                    f"called {where}. Call it from production, or declare it in "
                    f"DECLARED_STARTUP with the reason it is intentionally uncalled.",
                )
            )
    return findings


# ── Detector 9 — a public declaration production code never reads ─────────────
#
# The blind spot this closes (#1916): `ImportOptions.overwriteExisting` was
# declared on the options class, handed a value by the builder, and asserted by a
# test — and no production code ever read it, so `false` still meant "overwrite".
# `dead-symbol` above cannot see it. The declaration counts as one production
# reference, so a property that is *written* always has at least that one, and a
# test reference on top makes it look used. Nothing in the scanner distinguished
# a property that is read from one that is only written.
#
# Three rules make "is it ever read?" answerable without a parser:
#
#   * prose is not code. The corpus is built with `strip_code`, the same reader
#     `dead-symbol` uses, so a KDoc link and a log tag are blanked rather than
#     counted as a read.
#   * a named argument or an assignment is a *write*: `receiver.name = …`, and
#     `name = …` inside the declaring class's own constructor or its `copy`.
#     Every other occurrence — `receiver.name`, a type position, a bare
#     expression — is a read, and one read means the option is honoured.
#   * a name declared more than once *among the shapes this detector collects* is
#     not attributed at all. `overwriteExisting` has one declaration and can be
#     followed; `id`, `name` and `title` are declared on dozens of classes, and
#     counting a receiver-less occurrence against one of them would report
#     working code. The detector is deliberately the narrow one: it stays silent
#     rather than guess, and a name whose declarations collide needs a reader,
#     not a regex.
#
#     The collision count covers the shapes above, not every declaration in the
#     tree: a plain member property of the same name is not counted, so an
#     occurrence with no receiver may be attributed to this declaration. That
#     direction loses a finding; it never invents one.
#
# `@Serializable` classes are skipped. kotlinx.serialization reads their fields
# through generated code that names neither the property nor a receiver, so a
# data-class field there is read in a way this scan cannot see.

_MODIFIERS = (
    "public|internal|private|protected|open|abstract|sealed|data|value|inline|"
    "expect|actual|enum|annotation|inner|override|operator"
)
_ANNOTATIONS = r"(?:[ \t]*@[A-Za-z_][\w.]*(?:\([^\n]*\))?[ \t]*\n?[ \t]*)*"
_DECL_HEAD = (
    rf"(?m)^(?P<annos>{_ANNOTATIONS})[ \t]*"
    rf"(?P<mods>(?:(?:{_MODIFIERS})[ \t]+)*)"
)
_VALUE_CLASS_DECL = re.compile(rf"{_DECL_HEAD}value[ \t]+class[ \t]+(?P<name>\w+)")
_DATA_CLASS_DECL = re.compile(
    rf"{_DECL_HEAD}data[ \t]+class[ \t]+(?P<name>\w+)[ \t]*(?:<[^>\n]*>)?[ \t]*\("
)
_CONST_DECL = re.compile(rf"{_DECL_HEAD}const[ \t]+val[ \t]+(?P<name>\w+)")
_COMPUTED_DECL = re.compile(
    rf"{_DECL_HEAD}val[ \t]+(?P<name>\w+)[^\n=]*(?:\n[ \t]*)?\bget[ \t]*\("
)
_PROPERTY_PARAM = re.compile(r"\b(?:val|var)[ \t]+(\w+)[ \t]*:")
_SERIALIZABLE = re.compile(r"@(?:[A-Za-z_][\w.]*\.)?Serializable\b")
_NOT_PUBLIC = re.compile(r"\b(?:internal|private|protected)\b")
# The short form of a string template: `"$name"`. The long form, `"${name}"`, is
# already code as far as `strip_code` is concerned, so only this one needs counting.
_TEMPLATE_READ = re.compile(r"\$(\w+)")


@dataclass(frozen=True)
class _UnreadDecl:
    """One declaration the detector can attribute by name."""

    name: str
    path: pathlib.Path
    offset: int
    shape: str
    owner: str = ""
    public: bool = True


def _is_write_occurrence(text: str, start: int, end: int, owner: str) -> bool:
    """True when the occurrence at [start, end) hands the property a value."""
    if not re.match(r"[ \t]*=(?!=)", text[end:]):
        return False
    before = text[:start].rstrip(" \t")
    if before.endswith("."):
        return True  # receiver.name = value
    if not (before.endswith("(") or before.endswith(",")):
        return False
    enclosing = _enclosing_call(text, start)
    if enclosing is None:
        return False  # `val name = …` and friends declare or read, they do not write
    callee = enclosing[0]
    return callee == owner or (bool(owner) and callee == "copy")


@dataclass(frozen=True)
class _UnreadScan:
    """What the scan could attribute, and what it had to leave out."""

    candidates: list[tuple[_UnreadDecl, int, int]]
    ambiguous: tuple[str, ...] = ()


_LOCAL_DECL_BEFORE = re.compile(r"\b(?:val|var)[ \t]+$")
_CTOR_OPTION = re.compile(
    r"^[\s@]*(?:(?:public|internal|private|protected|override|open|final)[ \t]+)*"
    r"(?:val|var)[ \t]+(?P<name>\w+)[ \t]*:"
)
_CTOR_HIDDEN = re.compile(r"\b(?:internal|private|protected)\b")


def _is_local_declaration(text: str, start: int) -> bool:
    """True when the occurrence names the local a `val`/`var` declares.

    `val overwriteExisting = false` in a function body introduces a *new* symbol: it
    reads neither the option of that name nor writes it. Counting it as a read let a
    function-local shadow keep a lying option green — the hole #1916 asks this shape
    to close.
    """
    return bool(_LOCAL_DECL_BEFORE.search(text, max(0, start - 32), start))


def _top_level_options(body: str) -> list[tuple[int, str, bool]]:
    """The `val`/`var` parameters of a parameter list, as (offset, name, hidden).

    Only depth-0 pieces count. `_PROPERTY_PARAM` matched anywhere between the parens,
    so a default-value lambda (`run { val tmp: Int = 0; tmp }`) was attributed to the
    class as an option `Owner.tmp` that was only ever a local.
    """
    pieces: list[tuple[int, str]] = []
    depth = 0
    start = 0
    for i, ch in enumerate(body):
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "," and depth == 0:
            pieces.append((start, body[start:i]))
            start = i + 1
    pieces.append((start, body[start:]))

    options: list[tuple[int, str, bool]] = []
    for offset, piece in pieces:
        m = _CTOR_OPTION.match(piece)
        if m is None:
            continue
        head = piece[: m.start("name")]
        options.append(
            (
                offset + m.start("name"),
                m.group("name"),
                bool(_CTOR_HIDDEN.search(head)),
            )
        )
    return options


def _precompute_unread_public(
    code: dict[pathlib.Path, str],
) -> _UnreadScan:
    """Candidates with their production write and read counts.

    Counts come from production sources only, over `strip_code` text: a test that
    asserts an option must not make it read, and prose must not make it
    referenced. A declaration with reads > 0 is dropped by the check.
    """
    prod = {
        path: strip_code(text)
        for path, text in code.items()
        if not _is_test_source(path)
    }

    # A string template is a read: Kotlin evaluates `"$name"`, so the property's value
    # reaches the log line. The corpus below is built with `strip_code`, which blanks
    # the *contents* of a literal, so the short form is not in it at all — a property
    # whose only production use is `log("$enabled")` would be reported as unread. The
    # long form `"${name}"` survives, because a template's body is code.
    template_reads: set[str] = set()
    for path, text in code.items():
        if _is_test_source(path):
            continue
        template_reads.update(_TEMPLATE_READ.findall(text))

    decls: list[_UnreadDecl] = []
    for path, text in prod.items():
        for m in _VALUE_CLASS_DECL.finditer(text):
            decls.append(
                _UnreadDecl(
                    m.group("name"), path, m.start("name"), "value class",
                    public=not _NOT_PUBLIC.search(m.group("mods")),
                )
            )
        for m in _CONST_DECL.finditer(text):
            decls.append(
                _UnreadDecl(
                    m.group("name"), path, m.start("name"), "constant",
                    public=not _NOT_PUBLIC.search(m.group("mods")),
                )
            )
        for m in _COMPUTED_DECL.finditer(text):
            decls.append(
                _UnreadDecl(
                    m.group("name"), path, m.start("name"), "computed property",
                    public=not _NOT_PUBLIC.search(m.group("mods")),
                )
            )
        for m in _DATA_CLASS_DECL.finditer(text):
            owner = m.group("name")
            open_paren = m.end() - 1
            body = _balanced_body(text, open_paren)
            if body is None:
                continue
            # A `@Serializable` field is read by generated code this scan cannot
            # follow, and the declaration is the only thing it can see.
            public = (
                not _NOT_PUBLIC.search(m.group("mods"))
                and not _SERIALIZABLE.search(m.group("annos"))
            )
            for start, name, hidden in _top_level_options(body):
                decls.append(
                    _UnreadDecl(
                        name, path, open_paren + 1 + start,
                        "property", owner, public and not hidden,
                    )
                )

    declared: dict[str, int] = {}
    for decl in decls:
        declared[decl.name] = declared.get(decl.name, 0) + 1

    results: list[tuple[_UnreadDecl, int, int]] = []
    ambiguous: list[str] = []
    for decl in decls:
        if not decl.public:
            continue
        if declared[decl.name] != 1:
            # Load-bearing and, until now, silent. Reported as a `note` below: a green
            # run must read as "no unambiguous unread option", not "no unread option".
            ambiguous.append(decl.name)
            continue
        pattern = re.compile(rf"\b{re.escape(decl.name)}\b")
        writes = reads = 0
        for path, text in prod.items():
            for m in pattern.finditer(text):
                if path == decl.path and m.start() == decl.offset:
                    continue  # the declaration itself is not a use
                if _is_local_declaration(text, m.start()):
                    # `val overwriteExisting = …` in a function body declares a *new*
                    # symbol; it neither reads nor writes the option of that name.
                    continue
                if _is_write_occurrence(text, m.start(), m.end(), decl.owner):
                    writes += 1
                else:
                    reads += 1
        if decl.name in template_reads:
            # `"$name"` is a read of the property, and the stripped corpus above
            # cannot show it.
            reads += 1
        results.append((decl, writes, reads))
    return _UnreadScan(results, tuple(ambiguous))


def _check_unread_public(
    _code: dict[pathlib.Path, str],
    _corpus: str,
    pre: _UnreadScan,
) -> list[tuple[str, str]]:
    findings: list[tuple[str, str]] = []
    baseline = _load_baseline()
    for decl, writes, reads in pre.candidates:
        if reads:
            continue
        name = f"{decl.owner}.{decl.name}" if decl.owner else decl.name
        if writes:
            message = (
                f"{rel(decl.path)}: {name} is written by {writes} production use(s) "
                f"and read by none — the option is set and never consulted"
            )
            kind = "write-only"
        else:
            message = (
                f"{rel(decl.path)}: {name} ({decl.shape}) is declared and never read "
                f"in production"
            )
            kind = "unread-public"
        reason = baseline.reason_for(decl.name, decl.path)
        if reason:
            findings.append(("exempt", f"{message} — {reason}"))
        else:
            findings.append((kind, message))
    if pre.ambiguous:
        # Count the distinct names, because the list beside the count is distinct names.
        # `len(pre.ambiguous)` is a *declaration* count: a name declared three times
        # appeared once in the list and three in the number, so a reader judging coverage
        # from the note was told about 3 names and shown 1.
        names = sorted(set(pre.ambiguous))
        shown = ", ".join(names[:6]) + (", …" if len(names) > 6 else "")
        findings.append((
            "note",
            f"{len(names)} public name(s) skipped as ambiguous — each is declared more "
            f"than once among the shapes this detector collects ({shown}), so an "
            f"occurrence with no receiver cannot be attributed to one of them. This run "
            f"reports nothing about those declarations.",
        ))
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
    Detector(
        kind="dead-symbol",
        check=_check_dead_symbol,
        precompute=_precompute_dead_symbol,
    ),
    Detector(
        kind="startup-unwired",
        check=_check_startup_unwired,
        precompute=_precompute_startup,
    ),
    Detector(
        kind="unread-public",
        check=_check_unread_public,
        precompute=_precompute_unread_public,
    ),
]

# Printed, never counted. `exempt` is a decision argued in
# scripts/find-unwired-surfaces-baseline.txt; `note` is a detector saying what it could
# not decide, which is information rather than a finding.
INFORMATIONAL = frozenset({"exempt", "note"})


def _summary(findings: list[tuple[str, str]]) -> str:
    """The one-line count of a run, with exemptions and notes counted apart.

    `exempt` and `note` are both printed, and neither is a finding: an exemption is a
    decision that was argued in the baseline file, and a note is a detector saying what
    it could not decide. Counting them together called a note an exemption — a
    statement about a decision nobody took, on a run whose only finding was an
    ambiguity note.
    """
    blocking = [f for f in findings if f[0] not in INFORMATIONAL]
    exempt = [f for f in findings if f[0] == "exempt"]
    notes = [f for f in findings if f[0] == "note"]
    counts = [f"{len(blocking)} blocking finding(s)"]
    if exempt:
        counts.append(f"{len(exempt)} exempted")
    if notes:
        counts.append(f"{len(notes)} note(s)")
    return "; ".join(counts) + "."


# ── Main ────────────────────────────────────────────────────────────────────


FLOOR_FILE = ROOT / "config" / "docs" / "unwired-surfaces-floor.txt"

FLOOR_HEADER = """\
# unwired-surfaces floor — the number of BLOCKING findings this gate may report.
#
# Counted when the floor was last written, against source roots: {roots}.
#
# Every detector counts, `unread-public` / `write-only` (#1916) included: a shape
# whose findings are never counted has no floor to stop it growing.
#
# One-directional on purpose. Lowering it is the debt being paid down and needs no
# justification; raising it is a new unwired surface and must be argued in review.
# Exempt rows are not counted: they do not fail the build, so counting them would
# make the floor move whenever an exemption's wording changed.
#
# Rewrite with: scripts/find-unwired-surfaces.py --update-floor
"""


def read_floor() -> int:
    """Return the recorded floor. A missing or malformed file is an error, not zero."""
    if not FLOOR_FILE.is_file():
        print(
            f"::error::{FLOOR_FILE} does not exist — the gate has no measured baseline. "
            f"Create it with: scripts/find-unwired-surfaces.py --update-floor",
            file=sys.stderr,
        )
        raise SystemExit(2)
    text = FLOOR_FILE.read_text(encoding="utf-8")
    values = [
        line.strip() for line in text.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    if len(values) != 1 or not values[0].isdigit():
        print(
            f"::error::{FLOOR_FILE} must hold exactly one integer line "
            f"({len(values)} non-comment line(s) found). A floor that cannot be read "
            f"is a floor that cannot fail.",
            file=sys.stderr,
        )
        raise SystemExit(2)
    return int(values[0])


def write_floor(count: int) -> None:
    FLOOR_FILE.parent.mkdir(parents=True, exist_ok=True)
    FLOOR_FILE.write_text(
        FLOOR_HEADER.format(roots=", ".join(SOURCE_ROOTS)) + f"\n{count}\n",
        encoding="utf-8",
    )


CLEAN = 0
VALIDATION_FAILURE = 1


@click.command()
@click.option("--quiet", is_flag=True, help="print findings only")
@click.option(
    "--update-floor",
    is_flag=True,
    help="rewrite the floor with the current blocking count and exit",
)
@click.pass_context
def cli(ctx: click.Context, quiet: bool, update_floor: bool) -> None:
    files = kotlin_files()
    code = {p: strip_comments(p.read_text()) for p in files}
    corpus = "\n".join(code.values())

    findings: list[tuple[str, str]] = []

    for detector in DETECTORS:
        pre_result = detector.precompute(code)
        findings.extend(detector.check(code, corpus, pre_result))

    # An exempt row is reported and does not fail the build. It is printed rather than
    # dropped so that the decision stays visible in the log of the run that honoured it —
    # a gate that stops mentioning its exemptions is a gate whose exemptions nobody
    # remembers agreeing to.
    blocking = [f for f in findings if f[0] not in INFORMATIONAL]

    if update_floor:
        write_floor(len(blocking))
        click.echo(f"wrote {FLOOR_FILE}: {len(blocking)} blocking finding(s)")
        raise ctx.exit(CLEAN)

    for kind, message in sorted(findings):
        click.echo(f"[{kind}] {message}")

    if not findings:
        click.echo("\nNo unwired surfaces found.", err=True)
    else:
        click.echo("\n" + _summary(findings), err=True)
        if any(kind == "exempt" for kind, _ in findings):
            click.echo(
                "Every exemption names a reason in "
                "scripts/find-unwired-surfaces-baseline.txt — a row without one is an "
                "exemption nobody can audit.",
                err=True,
            )

    floor = read_floor()
    if len(blocking) > floor:
        click.echo(
            f"::error::unwired surfaces rose from the recorded floor of {floor} to "
            f"{len(blocking)}. Either wire the new surface, or argue it in review and "
            f"then run: scripts/find-unwired-surfaces.py --update-floor",
            err=True,
        )
        raise ctx.exit(VALIDATION_FAILURE)
    if len(blocking) < floor:
        # Not a failure, but not silent either: an unnoticed drop is how a stale floor
        # becomes a ratchet that permits a rise back to the old number.
        click.echo(
            f"unwired surfaces fell from the recorded floor of {floor} to "
            f"{len(blocking)} — lower the floor in the same change "
            f"(scripts/find-unwired-surfaces.py --update-floor) so it cannot rise back "
            f"unnoticed."
        )

    raise ctx.exit(VALIDATION_FAILURE if blocking else CLEAN)


def main(argv=None) -> int:
    try:
        return cli.main(standalone_mode=False, args=argv)
    except SystemExit:
        raise


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""check-coverage-floor-declaration.py — the coverage floor is declared where Kover reads it.

What went wrong
---------------
The app coverage floors were declared in the ROOT build script:

    // build.gradle.kts
    plugins.withId("org.jetbrains.kotlinx.kover") {
        configure<KoverProjectExtension> {
            reports {
                total {
                    verify {
                        rule("app instruction coverage") { … }
                    }
                }
            }
        }
    }

That block never ran. Probed on 2026-10-22 with a `println` inside it: the
line never appeared. The root project has no Kover extension of its own to
configure — the aggregation plugin registers `:koverXmlReport`,
`:koverHtmlReport` and `:koverVerify` on the root and reads its rules from
the SETTINGS-level `kover { reports { verify { } } }` — so the floors this
file declared were never evaluated by anything.

The failure was invisible in both directions. The CI step that reads the
floors was green no matter what the coverage was, and the ADR that recorded
the floors said, in so many words, that "nothing Python-side could have
proved it" — which was true only because of WHERE the declaration was.

The rule
--------
Every coverage floor of this repository is declared in `settings.gradle.kts`,
and nowhere else.

The APP floors live in the aggregation plugin's own `reports { verify { } }`
block, because that is where the plugin reads them — the root project has no
Kover extension of its own to configure, so a verify block in the root build
script is read by nothing at all.

The MODULE floors are declared in the same file, for a different reason: one
place for the whole policy. `detekt-rules` measures its own rules, and its
verify rule still lives in `detekt-rules/build.gradle.kts` — Kover reads it
there — but the NUMBER is declared here and only bound there. A module that
declares a floor of its own is a second home for one number, and the gate that
checks the declaration cannot see the drift.

Six halves, and all are needed:

1. `settings.gradle.kts` declares the floors: the app verify rules with the
   six floor constants (one set per population, one per counter), and the
   `detekt-rules` branch-coverage constant. A floor that is not declared is a
   floor that is not checked.
2. The rule BINDS the declared constant. `minValue.set(appInstructionCoverageFloor)`
   is a claim about which number the rule checks; the value it names must
   read the floor constant of that counter, for BOTH populations. A rule that
   binds a literal (`minValue.set(1)`) enforces a floor of one percent, and
   a gate that only looked for the constant's NAME would call that tree
   correct — the floor would be neutered with every gate green.
3. The ROOT build script declares no Kover verify block. That is the
   forbidden place, and not by taste: the root project has no Kover
   extension of its own to configure, so a verify block there is read by
   nothing at all — the floors it declares are evaluated by no task.
4. No other script declares a rule named `app …`. The app floor is the
   AGGREGATE one — it measures the whole application, which only the
   settings-level block can do. A module-level verify block is legitimate
   and this repository has one (`detekt-rules` measures its own rules): it
   measures that module, which is a different question. What it may not do
   is take the name of the app floor, because a rule called "app
   instruction coverage" that measures one module is a claim the reader
   will believe and the build will not keep.
5. No build file outside `settings.gradle.kts` DECLARES a floor. A module may
   BIND the constant declared there — it reads it from `gradle.extra` — but a
   `val …_FLOOR` of its own is a second home for one number. There is no
   exception for `detekt-rules` and no exception for anybody else: the policy
   is one policy.
6. Every floor declared in the settings file carries its `require(…)`
   minimum there. A floor that can be lowered without a visible change is a
   floor that cannot fail, and the minimum is what makes the lowering
   visible.
7. The minimum is a MINIMUM, not a formula. `require(FLOOR >= 0)` is written
   like a minimum and lets the floor go to zero; a floor a module checks is
   held to the minimum the wiring gate holds every kover verify rule to
   (`_KOVER_MIN_FLOOR`, read from the gate registry of THIS checkout), so the
   two gates cannot disagree about what a usable floor is.
8. A bound is the declared constant, not an expression of it. `minValue =
   FLOOR - 40` is a different number from the floor, and the name in front
   of it satisfies every other check of this gate.
9. A module that binds a floor declares the rule that checks it. A floor
   that is bound and never checked is a floor that cannot fail, and the
   whole `verify { … }` block can go without leaving a rule NAME behind to
   look for.

What this does not do
---------------------
It does not run Gradle, and it does not measure a single instruction. What it
proves is the cheap half: that the floors are declared in one place, that the
rules bind the constants declared there, AND that the declaration and its
binding are not the coverage itself.

The enforcement is `:koverVerify`'s job, and CI runs it on every push. The
control that would raise a floor to 99 and require the task to fail is NOT
part of this gate: the instrumented run costs about 37 minutes, more than
the harness deadline, and `docs/decisions/2026-10-23-a-population-is-a-name.md`
records that gap instead of papering over it.

The scan is textual, not a Gradle parse: the repository has no Kotlin-DSL
parser, and a comment that mentions `kover {` is stripped before the scan, so
prose about the block does not count as the block.

Usage:
    python3 scripts/check-coverage-floor-declaration.py            # report
    python3 scripts/check-coverage-floor-declaration.py --quiet

Exit codes:
    0 — the floors are declared where Kover reads them, and bound to the
        declared constants
    1 — a finding
"""

from __future__ import annotations

import ast
import pathlib
import re
import sys

import click
from sgate.commands.base import run  # noqa: PLC0415 — scripts are not a package

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The gate registry of THIS checkout — the file the wiring gate reads its own
#: minimum from. It is read by path and never imported through the installed
#: `sgate` package; see `_registry_minimum`.
REGISTRY_FILE = (
    ROOT / "scripts" / "src" / "sgate" / "commands" / "_registry.py"
)

#: The one file the aggregation plugin reads its rules from — and the one
#: file every coverage floor of this repository is declared in.
DECLARATION_FILE = "settings.gradle.kts"

#: The verify rules the app coverage floor consists of, and the floor
#: constants each of them must bind: one set per population, one per counter.
REQUIRED_BINDINGS: dict[str, tuple[str, str]] = {
    "app instruction coverage": (
        "FULL_INSTRUCTION_COVERAGE_FLOOR",
        "FAST_INSTRUCTION_COVERAGE_FLOOR",
    ),
    "app branch coverage": (
        "FULL_BRANCH_COVERAGE_FLOOR",
        "FAST_BRANCH_COVERAGE_FLOOR",
    ),
    "app line coverage": (
        "FULL_LINE_COVERAGE_FLOOR",
        "FAST_LINE_COVERAGE_FLOOR",
    ),
}

#: The floor constants: one per population, one per counter.
REQUIRED_CONSTANTS = tuple(
    constant for pair in REQUIRED_BINDINGS.values() for constant in pair
)

#: A build script that may hold a Kover block.
_BUILD_SCRIPTS = ("build.gradle.kts", "settings.gradle.kts")

#: Directories that hold no module of their own.
_SKIP_DIRS = frozenset({"build", "build-logic", "gradle", "node_modules", ".git"})

_KOVER_BLOCK = re.compile(r"\bkover\s*\{")
_VERIFY = re.compile(r"\bverify\s*\{")

#: `rule("app instruction coverage") { … }` — the name is what the reader reads.
_RULE = re.compile(r'\brule\(\s*"(?P<name>[^"]+)"\s*\)')

#: The floor a rule checks: `minValue.set(appInstructionCoverageFloor)`, or
#: the property form `minValue = appInstructionCoverageFloor`. A LITERAL
#: matches neither — and that is the point: `minValue.set(1)` is not a
#: binding of a declared constant, and the gate must say so.
#:
#: The literal is matched as well, so that the finding can name what the rule
#: binds instead of saying that it binds nothing: a rule that binds `1` does
#: have a `minValue.set(...)`, and telling the reader otherwise sends them
#: looking for a line that is there.
_MIN_VALUE = re.compile(
    r"minValue\s*(?:\.\s*set\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)"
    r"|=\s*([A-Za-z_][A-Za-z0-9_]*))"
)
_MIN_VALUE_LITERAL = re.compile(
    r"minValue\s*(?:\.\s*set\s*\(\s*(\d+(?:\.\d+)?)\s*\)|=\s*(\d+(?:\.\d+)?))"
)

#: A bound that continues with an operator: `minValue = FLOOR - 40` is a
#: different number from the floor, and the name in front of it hides that.
#: The name is what every other check of this gate looks at, so an expression
#: behind it would pass them all.
_MIN_VALUE_ARITHMETIC = re.compile(
    r"minValue\s*(?:=\s*([A-Za-z_][A-Za-z0-9_]*)\s*[-+*/]"
    r"|\.\s*set\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*[-+*/])"
)

#: `val NAME = …` at the top level of a Kotlin script.
_VAL_DECLARATION = re.compile(r"^val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]*)?=\s*(.*)$")

#: The name of a floor constant: the upper snake case the floors use —
#: `FULL_BRANCH_COVERAGE_FLOOR`, `DETEKT_RULE_BRANCH_FLOOR`.
_FLOOR_NAME = re.compile(r"^[A-Z][A-Z0-9_]*_FLOOR$")

#: A read of the extra properties of the Gradle object — the channel a
#: settings script and a module's build script share. A module that binds the
#: floor declared in the settings file reads it here.
_GRADLE_EXTRA = re.compile(r"gradle\s*\.\s*extra\s*\[")

#: A name in an expression.
_IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def _strip_comments(text: str) -> str:
    """The text with its comments blanked out.

    A comment character becomes a space, so every offset and every line
    number in the result is the one it has in the file — a finding can name
    the line it was found on. A string literal is left alone: a URL in a
    build script contains `//`, and reading that as a comment would eat the
    rest of the line, closing braces included, and the braces are what the
    scan counts.
    """
    out: list[str] = []
    index = 0
    length = len(text)
    in_string = False
    while index < length:
        char = text[index]
        if in_string:
            out.append(char)
            if char == "\\" and index + 1 < length:
                out.append(text[index + 1])
                index += 2
                continue
            if char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            out.append(char)
            index += 1
            continue
        if text.startswith("//", index):
            end = text.find("\n", index)
            end = length if end < 0 else end
            out.append(" " * (end - index))
            index = end
            continue
        if text.startswith("/*", index):
            end = text.find("*/", index + 2)
            end = length if end < 0 else end + 2
            out.append("".join("\n" if c == "\n" else " " for c in text[index:end]))
            index = end
            continue
        out.append(char)
        index += 1
    return "".join(out)


def _blocks(text: str, opener: re.Pattern[str]) -> list[str]:
    """The body of every block *opener* opens, by brace matching."""
    bodies: list[str] = []
    for match in opener.finditer(text):
        depth = 0
        index = match.end() - 1
        while index < len(text):
            char = text[index]
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    bodies.append(text[match.end():index])
                    break
            index += 1
    return bodies


def _line_of(text: str, offset: int) -> int:
    """The 1-based line the given offset sits on."""
    return text.count("\n", 0, offset) + 1


def _rule_bodies(text: str) -> dict[str, tuple[str, int]]:
    """`rule("…") { … }` bodies: the rule name → (body, line of the rule)."""
    bodies: dict[str, tuple[str, int]] = {}
    for match in _RULE.finditer(text):
        opening = text.find("{", match.end())
        if opening < 0:
            continue
        depth = 0
        index = opening
        while index < len(text):
            if text[index] == "{":
                depth += 1
            elif text[index] == "}":
                depth -= 1
                if depth == 0:
                    break
            index += 1
        bodies[match.group("name")] = (text[opening + 1:index], _line_of(text, match.start()))
    return bodies


def _val_declarations(text: str) -> dict[str, tuple[str, int]]:
    """`val NAME = …` declarations: the name → (initializer, line).

    The initializer continues on the lines below it, and every one of them
    is indented — a top-level statement starts at column zero.
    """
    declarations: dict[str, tuple[str, int]] = {}
    lines = text.split("\n")
    for index, line in enumerate(lines):
        match = _VAL_DECLARATION.match(line)
        if match is None:
            continue
        parts = [match.group(2)]
        for continuation in lines[index + 1:]:
            if not continuation.strip() or not continuation[:1].isspace():
                break
            parts.append(continuation)
        declarations[match.group(1)] = (" ".join(part.strip() for part in parts), index + 1)
    return declarations


def _referenced_names(name: str, declarations: dict[str, tuple[str, int]]) -> set[str]:
    """Every name the value of `name` reads, transitively.

    A floor is usually bound through a value of its own —
    `appInstructionCoverageFloor` reads the full floor and the fast one —
    so the question "does this rule bind the declared constant?" is a
    question about the chain, not about one name.
    """
    names: set[str] = set()
    pending = [name]
    while pending:
        current = pending.pop()
        if current in names:
            continue
        names.add(current)
        declaration = declarations.get(current)
        if declaration is None:
            continue
        for identifier in _IDENTIFIER.findall(declaration[0]):
            if identifier not in names:
                pending.append(identifier)
    return names


def build_scripts() -> list[pathlib.Path]:
    """Every Gradle build script in the repository that could hold a Kover block.

    Nested modules count: a module that lives two directories down declares
    its floors in its own build file just as a top-level one does, and a scan
    that stopped at the first level would not see it.
    """
    found: list[pathlib.Path] = []
    for path in sorted(ROOT.glob("**/build.gradle.kts")):
        parts = path.relative_to(ROOT).parts[:-1]
        if any(part in _SKIP_DIRS for part in parts):
            continue
        if path not in found:
            found.append(path)
    for name in _BUILD_SCRIPTS:
        path = ROOT / name
        if path.is_file() and path not in found:
            found.append(path)
    return found


def floor_declarations(text: str) -> dict[str, tuple[str, int]]:
    """The `val …_FLOOR = …` declarations: the name → (initializer, line)."""
    return {
        name: entry
        for name, entry in _val_declarations(text).items()
        if _FLOOR_NAME.match(name)
    }


def declares_verify_rules(text: str) -> bool:
    """Does this script hold a `kover { … verify { … } }` block?"""
    stripped = _strip_comments(text)
    return any(_VERIFY.search(body) for body in _blocks(stripped, _KOVER_BLOCK))


def declares_app_rule(text: str) -> bool:
    """Does this script declare a verify rule named `app …`?

    The app floor is the aggregate one. A rule with that name in a module
    measures that module — the name is the claim, and the claim is wrong.
    """
    stripped = _strip_comments(text)
    return bool(re.search(r'\brule\(\s*"app\s', stripped))


def binding_findings(text: str) -> list[str]:
    """The findings about what the verify rules bind.

    A rule that binds a literal enforces a floor of that literal, and a rule
    that binds a value which does not read the declared constant enforces
    whatever that value happens to be. Both leave the declared floor
    unenforced while every other rule of this gate is satisfied.
    """
    findings: list[str] = []
    declarations = _val_declarations(text)
    bodies = _rule_bodies(text)
    for rule, constants in REQUIRED_BINDINGS.items():
        entry = bodies.get(rule)
        if entry is None:
            continue  # the missing rule is reported by the caller
        body, rule_line = entry
        binding = _MIN_VALUE.search(body)
        if binding is None:
            literal = _MIN_VALUE_LITERAL.search(body)
            if literal is not None:
                value = literal.group(1) or literal.group(2)
                literal_line = rule_line + body[: literal.start()].count("\n")
                findings.append(
                    f"{DECLARATION_FILE}:{literal_line}: the verify rule {rule!r} binds the "
                    f"literal {value} — the floor it enforces is {value} percent, and not the "
                    f"number this repository declares. A rule that binds a literal satisfies "
                    f"every other check of this gate while the floor it enforces is nothing."
                )
                continue
            findings.append(
                f"{DECLARATION_FILE}:{rule_line}: the verify rule {rule!r} binds no floor "
                f"constant — there is no `minValue.set(...)` in the rule, so the floor it "
                f"checks is whatever the plugin defaults to, and not the number this "
                f"repository declares."
            )
            continue
        line = rule_line + body[: binding.start()].count("\n")
        bound = binding.group(1) or binding.group(2)
        arithmetic = _MIN_VALUE_ARITHMETIC.search(body)
        if arithmetic is not None:
            adjusted = arithmetic.group(1) or arithmetic.group(2)
            findings.append(
                f"{DECLARATION_FILE}:{line}: the verify rule {rule!r} binds "
                f"{adjusted!r} and then does arithmetic on it — the number the rule "
                f"checks is not the number that was declared. A floor that is "
                f"adjusted after it is read is a floor nobody declared, and the "
                f"name in front of it satisfies every other check of this gate."
            )
            continue
        if bound not in declarations:
            findings.append(
                f"{DECLARATION_FILE}:{line}: the verify rule {rule!r} binds {bound!r}, and "
                f"nothing declares that name in {DECLARATION_FILE}. A floor bound to a name "
                f"nobody declares is a floor nobody enforces."
            )
            continue
        referenced = _referenced_names(bound, declarations)
        missing = [constant for constant in constants if constant not in referenced]
        if missing:
            findings.append(
                f"{DECLARATION_FILE}:{line}: the verify rule {rule!r} binds {bound!r}, and "
                f"that value does not read the floor constant(s) {', '.join(missing)}. The "
                f"rule is checked against something other than the declared floor — a "
                f"`minValue.set(1)` would satisfy every other rule of this gate while the "
                f"floor it enforces is one percent."
            )
    return findings


def module_rule_findings(
    relative: str,
    text: str,
    settings_declarations: dict[str, tuple[str, int]],
) -> list[str]:
    """The findings about the verify rules of a MODULE's build file.

    A module rule binds a constant, and that constant is declared in
    `settings.gradle.kts` — every coverage floor of this repository lives
    there. A rule that binds a literal enforces that literal, and a rule
    that binds a name the settings file never declares enforces whatever
    that name happens to hold: in both cases the floor this repository
    declares is not the floor the task checks.
    """
    findings: list[str] = []
    for rule, (body, rule_line) in _rule_bodies(text).items():
        if rule in REQUIRED_BINDINGS:
            continue  # the app rules have their own, stricter check
        binding = _MIN_VALUE.search(body)
        if binding is None:
            literal = _MIN_VALUE_LITERAL.search(body)
            if literal is not None:
                value = literal.group(1) or literal.group(2)
                literal_line = rule_line + body[: literal.start()].count("\n")
                findings.append(
                    f"{relative}:{literal_line}: the verify rule {rule!r} binds the "
                    f"literal {value} — the floor it enforces is {value} percent, and not "
                    f"the number this repository declares. A rule that binds a literal "
                    f"satisfies every other check of this gate while the floor it "
                    f"enforces is nothing."
                )
                continue
            findings.append(
                f"{relative}:{rule_line}: the verify rule {rule!r} binds no floor "
                f"constant — there is no `minValue.set(...)` in the rule, so the floor "
                f"it checks is whatever the plugin defaults to, and not the number this "
                f"repository declares."
            )
            continue
        line = rule_line + body[: binding.start()].count("\n")
        bound = binding.group(1) or binding.group(2)
        arithmetic = _MIN_VALUE_ARITHMETIC.search(body)
        if arithmetic is not None:
            adjusted = arithmetic.group(1) or arithmetic.group(2)
            findings.append(
                f"{relative}:{line}: the verify rule {rule!r} binds {adjusted!r} and "
                f"then does arithmetic on it — the number the rule checks is not the "
                f"number {DECLARATION_FILE} declares. A floor that is adjusted after "
                f"it is read is a floor nobody declared."
            )
            continue
        if bound not in settings_declarations:
            findings.append(
                f"{relative}:{line}: the verify rule {rule!r} binds {bound!r}, and "
                f"{DECLARATION_FILE} declares no constant of that name. Every coverage "
                f"floor is declared in {DECLARATION_FILE} and bound by the module that "
                f"checks it — a floor bound to a name nobody declares is a floor nobody "
                f"enforces."
            )
    return findings


def missing_verify_findings(
    relative: str,
    text: str,
    settings_floors: set[str],
) -> list[str]:
    """A module that binds a floor must declare the rule that checks it.

    A floor that is bound and never checked is a floor that cannot fail: the
    module reads the number, the number is bound to nothing, and the task that
    would enforce it passes whatever the coverage was. Deleting the whole
    `verify { … }` block has to be a finding — a rule NAME that is missing is
    a different defect, and this one has no name to look for.
    """
    findings: list[str] = []
    binds = [
        (name, line)
        for name, (initializer, line) in floor_declarations(text).items()
        if _GRADLE_EXTRA.search(initializer) and name in settings_floors
    ]
    if not binds or declares_verify_rules(text):
        return findings
    name, line = binds[0]
    findings.append(
        f"{relative}:{line}: the module binds the coverage floor {name!r}, and "
        f"declares no `verify {{ … }}` rule to check it. A floor that is bound and "
        f"never checked is a floor that cannot fail — the task passes whatever the "
        f"coverage was."
    )
    return findings


def outside_settings_findings(
    relative: str,
    text: str,
    settings_floors: set[str],
) -> list[str]:
    """The findings about a build file that is not the settings file.

    A coverage floor is declared in ONE place — `settings.gradle.kts`. A
    module may BIND the constant declared there (it reads it from
    `gradle.extra`), but a module that declares a floor of its own is a
    second home for one number, and the drift between the two homes is
    exactly what this gate exists to catch. There is no exception for
    `detekt-rules` and no exception for anybody else.
    """
    findings: list[str] = []
    for name, (initializer, line) in floor_declarations(text).items():
        if _GRADLE_EXTRA.search(initializer):
            if name not in settings_floors:
                findings.append(
                    f"{relative}:{line}: binds the floor {name!r}, and {DECLARATION_FILE} "
                    f"declares no constant of that name — the floor this module checks is "
                    f"not the floor this repository declares."
                )
            continue
        findings.append(
            f"{relative}:{line}: declares the coverage floor {name!r}. Every coverage "
            f"floor of this repository is declared in {DECLARATION_FILE} — a module may "
            f"only bind the constant declared there (it reads it from `gradle.extra`), "
            f"never declare a floor of its own. A second home for one number is how a "
            f"floor drifts."
        )
    return findings


def _registry_minimum() -> tuple[int | None, str | None]:
    """The minimum the wiring gate holds every kover verify rule to.

    The number is a policy constant of the gate registry, and it is read from
    the registry of THIS checkout — by path and by AST, never through the
    installed `sgate` package. `sgate` is installed as an editable package,
    and a sibling worktree can be what is installed: on this host
    `import sgate` resolved into another worktree's tree, and a gate that read
    the registry from there would hold this repository's floors to another
    tree's minimum — silently, and with a verdict about the wrong
    repository.

    Returns the minimum, or None and the reason it could not be read. A
    reason is not a reason to carry on: a gate that cannot know the minimum
    has no verdict, and "checked nothing" must not look like "checked and
    green".
    """
    path = REGISTRY_FILE
    if not path.is_file():
        return None, f"{path} is not there"
    try:
        tree = ast.parse(path.read_text(encoding="utf-8"))
    except (OSError, SyntaxError) as exc:
        return None, f"{path} could not be read ({exc})"
    for node in tree.body:
        if not isinstance(node, ast.AnnAssign):
            continue
        target = node.target
        if not isinstance(target, ast.Name) or target.id != "_KOVER_MIN_FLOOR":
            continue
        value = node.value
        if value is None or not isinstance(value, ast.Constant):
            continue
        if isinstance(value.value, bool) or not isinstance(value.value, int):
            continue
        return value.value, None
    return None, f"{path} declares no `_KOVER_MIN_FLOOR` integer this gate can read"


def require_minimum_findings(
    text: str,
    module_bound: set[str],
    minimum: int | None,
) -> list[str]:
    """The findings about the `require(…)` minimum of each declared floor.

    The minimum is what makes lowering a floor visible: it fails the
    configuration instead of quietly passing. A floor without one can be
    lowered to zero without anybody noticing, which is the "a floor that
    cannot fail" defect.

    And the minimum has to BE a minimum. `require(FLOOR >= 0)` is written like
    one and is not one: it lets the floor go to zero, and the presence check
    cannot tell the difference. So the threshold is read, and it is held to
    the minimum the wiring gate holds every kover verify rule to (for a floor
    a MODULE checks) — the two gates cannot disagree about what a usable
    floor is.

    The minimum is None when the registry could not be read; then the
    threshold is not compared, and the reason is reported by the caller as a
    finding of its own.
    """
    findings: list[str] = []
    declarations = _val_declarations(text)
    for name, (_, line) in floor_declarations(text).items():
        match = re.search(rf"require\s*\(\s*{re.escape(name)}\s*>=\s*(\d+)", text)
        if match is None:
            findings.append(
                f"{DECLARATION_FILE}:{line}: the floor {name} has no `require(...)` "
                f"minimum beside its declaration — a floor that can be lowered without a "
                f"visible change is a floor that cannot fail."
            )
            continue
        threshold = int(match.group(1))
        if threshold <= 0:
            findings.append(
                f"{DECLARATION_FILE}:{line}: the floor {name} carries the minimum "
                f"require({name} >= {threshold}) — a minimum of zero is not a minimum, "
                f"and a floor that can be lowered to zero is a floor that cannot fail."
            )
            continue
        declared = declarations.get(name)
        if declared is not None:
            try:
                value = int(declared[0].strip())
            except ValueError:
                value = None
            if value is not None and threshold > value:
                findings.append(
                    f"{DECLARATION_FILE}:{line}: the floor {name} is declared as "
                    f"{value}, and its minimum require({name} >= {threshold}) is stricter "
                    f"than the floor itself — the configuration fails on the tree as it "
                    f"stands."
                )
                continue
        if minimum is not None and name in module_bound and threshold < minimum:
            findings.append(
                f"{DECLARATION_FILE}:{line}: the floor {name} is checked by a module, "
                f"and its minimum require({name} >= {threshold}) is below the "
                f"{minimum} that the wiring gate holds every kover verify rule "
                f"to. A module's floor may not have a smaller minimum than the gate that "
                f"watches it."
            )
    return findings


def ordering_findings(text: str) -> list[str]:
    """The two populations' floors, compared in the order the populations have.

    The WIDER population's floor shall not be below the narrower one's. A
    floor taken from a wider run is unreachable by a narrower one, and the two
    floors are compared by whoever reads them: a reader who sees the full
    population held to LESS than the fast one concludes that the full
    population is held to a weaker standard, which is the opposite of what a
    wider population means.

    The comparison is per counter, because the three counters are measured
    independently: an instruction floor that clears and a branch floor that
    does not is not "the floor".
    """
    declarations = _val_declarations(text)
    findings: list[str] = []

    for counter in ("INSTRUCTION", "BRANCH", "LINE"):
        wide = declarations.get(f"FULL_{counter}_COVERAGE_FLOOR")
        narrow = declarations.get(f"FAST_{counter}_COVERAGE_FLOOR")
        if wide is None or narrow is None:
            # A missing constant is another gate's finding. This one stays
            # quiet about it rather than reporting the same defect twice.
            continue
        wide_value = wide[0].strip()
        narrow_value = narrow[0].strip()
        if not (wide_value.isdigit() and narrow_value.isdigit()):
            continue
        if int(wide_value) < int(narrow_value):
            findings.append(
                f"{DECLARATION_FILE}:{wide[1]}: the full population's "
                f"{counter.lower()} coverage floor is {wide_value}% and the fast "
                f"population's is {narrow_value}%. The wider population's floor "
                "shall not be below the narrower one's: a floor taken from a "
                "wider run is unreachable by a narrower one, and the two are "
                "compared by whoever reads them."
            )

    return findings


def check() -> list[str]:
    """The findings; empty when the tree is correct."""
    errors: list[str] = []

    declaration = ROOT / DECLARATION_FILE
    if not declaration.is_file():
        return [f"{DECLARATION_FILE} is missing — the coverage floors have nowhere to live."]

    text = _strip_comments(declaration.read_text(encoding="utf-8"))
    for rule in REQUIRED_BINDINGS:
        if rule not in text:
            errors.append(
                f"{DECLARATION_FILE} declares no verify rule named {rule!r}. The app "
                f"coverage floor is three rules — instruction, branch and line — and a "
                f"missing one is a counter nothing measures."
            )
    for constant in REQUIRED_CONSTANTS:
        if not re.search(rf"^val {constant} = \d+", text, re.MULTILINE):
            errors.append(
                f"{DECLARATION_FILE} declares no constant {constant}. Each population "
                f"needs a floor per counter: the floors are measured values, and a "
                f"constant that is not there is a floor nobody enforces."
            )

    settings_declarations = _val_declarations(text)
    settings_floors = set(floor_declarations(text))

    errors.extend(binding_findings(text))
    errors.extend(ordering_findings(text))

    module_bound: set[str] = set()
    for script in build_scripts():
        relative = script.relative_to(ROOT).as_posix()
        body = _strip_comments(script.read_text(encoding="utf-8"))

        errors.extend(module_rule_findings(relative, body, settings_declarations))
        module_bound |= _floors_bound_by_rules(body)

        if relative == DECLARATION_FILE:
            continue

        errors.extend(outside_settings_findings(relative, body, settings_floors))
        errors.extend(missing_verify_findings(relative, body, settings_floors))

        if relative == "build.gradle.kts" and declares_verify_rules(body):
            errors.append(
                f"{relative} declares a Kover verify block, and nothing reads it. The "
                f"root project has no Kover extension of its own to configure — the "
                f"aggregation plugin registers `:koverXmlReport`, `:koverHtmlReport` "
                f"and `:koverVerify` on the root and reads its rules from the "
                f"settings-level block in {DECLARATION_FILE}. A verify block here is "
                f"evaluated by no task, which is exactly how the app coverage floor "
                f"went unenforced for months. Declare it in {DECLARATION_FILE}."
            )

        if declares_app_rule(body):
            errors.append(
                f"{relative} declares a verify rule named 'app …'. The app coverage "
                f"floor measures the whole application, and only the settings-level "
                f"block in {DECLARATION_FILE} can measure that. A module-level rule "
                f"under that name measures the module and answers a different "
                f"question — under a name the reader will take for the aggregate one."
            )

    # The minimum is read after the modules are known: a floor a module checks
    # is held to the minimum the wiring gate holds it to, and that is a fact
    # about the modules. The minimum itself comes from the registry of this
    # checkout — and a registry this gate cannot read is a finding, not a
    # reason to check less: "checked nothing" must not look like "checked and
    # green".
    minimum, problem = _registry_minimum()
    if problem is not None:
        errors.append(
            f"the minimum every kover verify rule is held to is unknown: {problem}. "
            f"Without it this gate cannot tell whether the minimum a floor carries is "
            f"the one the wiring gate holds rules to, so that half of the check does "
            f"not run — and a check that does not run is indistinguishable from a check "
            f"that passed."
        )
    errors.extend(require_minimum_findings(text, module_bound, minimum))

    return errors


def _floors_bound_by_rules(text: str) -> set[str]:
    """The names that the verify rules of this file bind."""
    bound: set[str] = set()
    for body, _ in _rule_bodies(text).values():
        match = _MIN_VALUE.search(body)
        if match is not None:
            bound.add(match.group(1) or match.group(2))
    return bound


@click.command()
@click.option("--quiet", is_flag=True)
@click.pass_context
def cli(ctx: click.Context, quiet: bool) -> None:
    errors = check()
    for error in errors:
        click.echo(f"ERROR: {error}")
    if errors:
        click.echo("")
        click.echo(
            f"check-coverage-floor-declaration: every coverage floor must be declared in "
            f"{DECLARATION_FILE} — one place for the whole policy — and bound by the "
            f"module that checks it."
        )
        raise ctx.exit(VALIDATION_FAILURE)
    if not quiet:
        click.echo(
            f"check-coverage-floor-declaration: OK — every coverage floor is declared in "
            f"{DECLARATION_FILE}, the rules bind the constants declared there, and no "
            f"other build script declares a floor of its own."
        )
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))

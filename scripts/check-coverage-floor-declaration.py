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
The app coverage floors live in `settings.gradle.kts`, in the aggregation
plugin's own `reports { verify { } }` block — and nowhere else.

Four halves, and all are needed:

1. `settings.gradle.kts` declares the floors: the three verify rules and the
   six floor constants (one set per population, one per counter). A floor
   that is not declared is a floor that is not checked.
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

What this does not do
---------------------
It does not run Gradle, and it does not measure a single instruction. What it
proves is the cheap half: that the floors are declared where the Kover
aggregation plugin reads them, AND that the rules there bind the declared
constants — the declaration and its binding, not the coverage itself.

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

import pathlib
import re
import sys

import click
from sgate.commands.base import run  # noqa: PLC0415 — scripts are not a package

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The one file the aggregation plugin reads its rules from.
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

#: `val NAME = …` at the top level of a Kotlin script.
_VAL_DECLARATION = re.compile(r"^val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]*)?=\s*(.*)$")

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
    """Every Gradle build script in the repository that could hold a Kover block."""
    found: list[pathlib.Path] = []
    for name in _BUILD_SCRIPTS:
        path = ROOT / name
        if path.is_file():
            found.append(path)
    for path in sorted(ROOT.glob("*/build.gradle.kts")):
        found.append(path)
    return found


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

    errors.extend(binding_findings(text))

    for script in build_scripts():
        relative = script.relative_to(ROOT).as_posix()
        if relative == DECLARATION_FILE:
            continue
        body = _strip_comments(script.read_text(encoding="utf-8"))

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

    return errors


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
            f"check-coverage-floor-declaration: the coverage floor must be declared in "
            f"{DECLARATION_FILE}, where the Kover aggregation plugin reads it, and bound "
            f"to the constants declared there."
        )
        raise ctx.exit(VALIDATION_FAILURE)
    if not quiet:
        click.echo(
            f"check-coverage-floor-declaration: OK — the app coverage floor is declared "
            f"in {DECLARATION_FILE}, bound to the declared constants, and no other build "
            f"script declares a verify block."
        )
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))

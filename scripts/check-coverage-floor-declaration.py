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

Three halves, and all are needed:

1. `settings.gradle.kts` declares the floors: the three verify rules and the
   six floor constants (one set per population, one per counter). A floor
   that is not declared is a floor that is not checked.
2. The ROOT build script declares no Kover verify block. That is the
   forbidden place, and not by taste: the root project has no Kover
   extension of its own to configure, so a verify block there is read by
   nothing at all — the floors it declares are evaluated by no task.
3. No other script declares a rule named `app …`. The app floor is the
   AGGREGATE one — it measures the whole application, which only the
   settings-level block can do. A module-level verify block is legitimate
   and this repository has one (`detekt-rules` measures its own rules): it
   measures that module, which is a different question. What it may not do
   is take the name of the app floor, because a rule called "app
   instruction coverage" that measures one module is a claim the reader
   will believe and the build will not keep.

What this does not do
---------------------
It does not run Gradle and it does not check that the floors are ENFORCED —
that is `:koverVerify`'s job, and the positive control in the gate registry
raises a floor to 99 and requires the task to fail. This check is the cheap
half: it catches the declaration moving back to a place nothing reads.

The scan is textual, not a Gradle parse: the repository has no Kotlin-DSL
parser, and a comment that mentions `kover {` is stripped before the scan, so
prose about the block does not count as the block.

Usage:
    python3 scripts/check-coverage-floor-declaration.py            # report
    python3 scripts/check-coverage-floor-declaration.py --quiet
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

#: The verify rules the app coverage floor consists of.
REQUIRED_RULES = (
    "app instruction coverage",
    "app branch coverage",
    "app line coverage",
)

#: The floor constants: one per population, one per counter.
REQUIRED_CONSTANTS = (
    "FULL_INSTRUCTION_COVERAGE_FLOOR",
    "FULL_BRANCH_COVERAGE_FLOOR",
    "FULL_LINE_COVERAGE_FLOOR",
    "FAST_INSTRUCTION_COVERAGE_FLOOR",
    "FAST_BRANCH_COVERAGE_FLOOR",
    "FAST_LINE_COVERAGE_FLOOR",
)

#: A build script that may hold a Kover block.
_BUILD_SCRIPTS = ("build.gradle.kts", "settings.gradle.kts")

_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
_LINE_COMMENT = re.compile(r"//[^\n]*")
_KOVER_BLOCK = re.compile(r"\bkover\s*\{")
_VERIFY = re.compile(r"\bverify\s*\{")


def _strip_comments(text: str) -> str:
    """The text without comments — a comment may talk about `kover {`."""
    return _LINE_COMMENT.sub("", _BLOCK_COMMENT.sub("", text))


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


def check() -> list[str]:
    """The findings; empty when the tree is correct."""
    errors: list[str] = []

    declaration = ROOT / DECLARATION_FILE
    if not declaration.is_file():
        return [f"{DECLARATION_FILE} is missing — the coverage floors have nowhere to live."]

    text = _strip_comments(declaration.read_text(encoding="utf-8"))
    for rule in REQUIRED_RULES:
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
            f"{DECLARATION_FILE}, where the Kover aggregation plugin reads it."
        )
        raise ctx.exit(VALIDATION_FAILURE)
    if not quiet:
        click.echo(
            f"check-coverage-floor-declaration: OK — the app coverage floor is declared "
            f"in {DECLARATION_FILE}, and no other build script declares a verify block."
        )
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))

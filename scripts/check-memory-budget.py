#!/usr/bin/env python3
"""check-memory-budget.py — every test task runs with the heap the record holds.

What went wrong
---------------
The heap of a test fork was a literal string in five build scripts, and the
heap of the Gradle daemon was a literal string in `gradle.properties`:

    shared/build.gradle.kts        maxHeapSize = "1536m"   (twice)
    desktopApp/build.gradle.kts    maxHeapSize = "1536m"
    mcp-server/build.gradle.kts    maxHeapSize = "1536m"
    detekt-rules/build.gradle.kts  maxHeapSize = "3g"
    adr-cli/build.gradle.kts       maxHeapSize = "1g"
    gradle.properties              org.gradle.jvmargs=-Xmx6144M …

Six literals in five files, and the ADR that records the measurement behind
them (`docs/decisions/2026-10-22-gradle-memory-budget.md`) was read by nobody:
no build read it and no gate checked it. The number was a claim in prose, and
the claim was made six times over.

This is the shape the coverage floor had before its declaration was moved to
the place Kover reads it: **a number that nobody can check is a number nobody
enforces**. A module that raised its heap to 3 GB would still have been green
everywhere — and the measurement behind 1536 MB would have quietly stopped
describing the build.

The rule
--------
There is ONE record of the budget — `config/docs/gradle-memory-budget.properties`
— and the tree is checked against it:

1. Every module that declares a test task, and every module that names a heap
   of its own, is listed in the record, under `test.heap.module.<project>`. A
   module that is not listed has no budget, and that is a finding rather than
   a silence. The test is the module's own build script: a module that writes
   `maxHeapSize` at all is a module whose heap the record has to hold, whether
   or not the script's wording happens to look like a test-task declaration.
2. A module's `maxHeapSize` is the recorded value — either the literal, or a
   read of the record's own line for that module. A module that hardcodes a
   different number is a finding, and the finding names the file, the line and
   BOTH numbers.
3. A module that declares no heap of its own takes the record's default, and
   the root build script has to apply that default — otherwise such a module
   runs with whatever Gradle's default is, which is not a budget at all.
4. The daemon heap in `gradle.properties` is the recorded one.
   `gradle.properties` cannot interpolate a value from another file — Gradle
   reads it before it reads anything else — so the number is written there as
   well, and this gate is what keeps the two copies in step.
5. Every line of the record names a module the build HAS. A line left over
   from a module that was removed is a budget nobody checks and nobody reads,
   and it is as dead as any other leftover — so the record is checked against
   the build in both directions, not only one.

The second half of the rule
---------------------------
A floor is a number, and a number that lives in a place nobody reads is not a
floor. The app coverage floor lives in `settings.gradle.kts`, because the
aggregation plugin reads its rules from there — that half is
`scripts/check-coverage-floor-declaration.py`'s job, and this gate does not
repeat it.

What this gate covers is the MODULE floors: a floor that measures one module
is read by that module's own Kover extension, so it belongs in that module's
own build script, and the constant it binds has to be declared in the same
file. A floor that is declared in the root build script is read by NOBODY:
the root project has no Kover extension of its own to configure, and the
aggregation plugin owns the aggregate. That is exactly how the app coverage
floor went unenforced for months — and the detekt-rules floor is a MODULE
floor, which is precisely why it stays in `detekt-rules/build.gradle.kts`
where Kover reads it and must not be moved to the settings.

What this does not do
---------------------
It does not run Gradle, and it does not measure a byte. What it proves is the
cheap half: that every test task in the tree runs with the heap the record
declares, that the record itself exists, and that the floors are declared
where their Kover reads them.

The scan is textual, like the sibling gates: the repository has no Kotlin-DSL
parser, and a comment that mentions `maxHeapSize` is stripped before the
scan, so prose about the heap does not count as the heap.

Usage:
    python3 scripts/check-memory-budget.py            # report
    python3 scripts/check-memory-budget.py --quiet
    python3 scripts/check-memory-budget.py --root DIR  # another tree
    python3 scripts/check-memory-budget.py --json

Exit codes:
    0 — every test task runs with the recorded heap, and the floors are
        declared where their Kover reads them
    1 — a finding
    3 — the tree could not be read (a configuration error)
    5 — an internal error
"""

from __future__ import annotations

import json
import pathlib
import re

import click
from sgate.commands.base import (  # noqa: PLC0415 — scripts are not a package
    CLEAN,
    CONFIG_ERROR,
    INTERNAL_ERROR,
    VALIDATION_FAILURE,
    run,
)

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The one file the budget is recorded in.
RECORD = "config/docs/gradle-memory-budget.properties"

#: The record's key for the heap a test task runs with when a module names no
#: budget of its own.
DEFAULT_KEY = "test.heap.default"

#: The record's key for the heap of the Gradle daemon.
DAEMON_KEY = "gradle.daemon.heap"

#: The prefix of the record's per-module key.
MODULE_KEY_PREFIX = "test.heap.module."

#: The Gradle properties file the daemon heap is declared in.
GRADLE_PROPERTIES = "gradle.properties"

#: The build script of the root project — the one place no Kover reads.
ROOT_BUILD_SCRIPT = "build.gradle.kts"

#: The settings script — the one place the aggregation plugin reads.
SETTINGS_SCRIPT = "settings.gradle.kts"

#: A module declares a test task when its build script says so.
_TEST_TASK = re.compile(r"(?:withType|named|register)\s*<\s*Test\s*>|useJUnitPlatform")

#: `maxHeapSize = "1536m"` — a heap written as a literal.
_MAX_HEAP_ASSIGNMENT = re.compile(r"maxHeapSize\s*=\s*([^\n]+)")

#: A whole expression that is one quoted string.
_LITERAL = re.compile(r'^"([^"]*)"$')

#: `include(":shared")` — a project of the build.
_INCLUDE = re.compile(r'^include\("(:[^"]+)"\)', re.MULTILINE)

#: `org.gradle.jvmargs=-Xmx6144M …` — the daemon's heap.
_JVM_ARGS = re.compile(r"^org\.gradle\.jvmargs\s*=\s*(.*)$", re.MULTILINE)

#: `-Xmx6144M` inside the daemon's arguments.
_XMX = re.compile(r"-Xmx(\S+)")

#: `kover { … }` — the block a Kover extension is configured in.
_KOVER_BLOCK = re.compile(r"\bkover\s*\{")

#: `verify { … }` — the block a coverage rule is declared in.
_VERIFY = re.compile(r"\bverify\s*\{")

#: `rule("app instruction coverage") { … }` — the rule's name.
_RULE = re.compile(r'\brule\(\s*"(?P<name>[^"]+)"\s*\)')

#: `minValue.set(NAME)` / `minValue = NAME` — the floor a rule binds.
_MIN_VALUE_NAME = re.compile(
    r"minValue\s*(?:\.\s*set\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)"
    r"|=\s*([A-Za-z_][A-Za-z0-9_]*))"
)

#: `val NAME = …` — a declaration at the top level of a Kotlin script.
_VAL_DECLARATION = re.compile(
    r"^val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]*)?=\s*(.*)$", re.MULTILINE
)


def _strip_comments(text: str) -> str:
    """The text with its comments blanked out.

    A comment character becomes a space, so every offset and every line number
    in the result is the one it has in the file — a finding can name the line
    it was found on. A string literal is left alone: a URL in a build script
    contains `//`, and reading that as a comment would eat the rest of the
    line, closing braces included.
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


def _properties(text: str) -> dict[str, tuple[str, int]]:
    """The properties of a `.properties` file, with the line each came from.

    A `#` starts a comment anywhere on the line — the same rule
    `java.util.Properties` applies, and the same rule the build reads the file
    with, so the gate and the build see one file.
    """
    found: dict[str, tuple[str, int]] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            continue
        marker = line.find("#")
        if marker >= 0:
            line = line[:marker]
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        found[key.strip()] = (value.strip(), number)
    return found


def _module_key(project: str) -> str:
    """The record's key for a project path: `:tools:a` → `test.heap.module.tools.a`."""
    return MODULE_KEY_PREFIX + project.lstrip(":").replace(":", ".")


def _modules(tree: pathlib.Path) -> list[tuple[str, pathlib.Path]]:
    """The projects of the build: the project path and its build script.

    Only the UNCONDITIONAL includes count. `:pro` is included behind
    `-PwithPro=true`, and the free configuration — the one the gates and the
    coverage job run — does not contain it.
    """
    settings = tree / SETTINGS_SCRIPT
    if not settings.is_file():
        return []
    found: list[tuple[str, pathlib.Path]] = []
    for project in _INCLUDE.findall(settings.read_text(encoding="utf-8")):
        directory = tree.joinpath(*project.lstrip(":").split(":"))
        found.append((project, directory / ROOT_BUILD_SCRIPT))
    return found


def _declares_test_task(script: pathlib.Path) -> bool:
    """Does this build script declare a test task?"""
    if not script.is_file():
        return False
    return bool(_TEST_TASK.search(_strip_comments(script.read_text(encoding="utf-8"))))


def _heap_declarations(script: pathlib.Path) -> list[tuple[int, str | None, str]]:
    """The `maxHeapSize` declarations of a build script.

    Each is the line it is on, the literal value (None when the heap is READ
    rather than written), and the expression it was assigned.
    """
    if not script.is_file():
        return []
    text = _strip_comments(script.read_text(encoding="utf-8"))
    declarations: list[tuple[int, str | None, str]] = []
    for match in _MAX_HEAP_ASSIGNMENT.finditer(text):
        line = text.count("\n", 0, match.start()) + 1
        expression = match.group(1).strip()
        literal = _LITERAL.match(expression)
        if literal is not None:
            declarations.append((line, literal.group(1), expression))
        else:
            declarations.append((line, None, expression))
    return declarations


def _val_statements(text: str) -> dict[str, tuple[str, int]]:
    """The `val NAME = …` declarations of a Kotlin script.

    The initializer continues on the lines below it, and every one of them is
    indented — a top-level statement starts at column zero.
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


def _read_key(text: str) -> str | None:
    """The budget key this text reads out of the record, when it reads one."""
    match = re.search(
        rf"(?:getProperty\(\s*\"|\[\s*\")({re.escape(MODULE_KEY_PREFIX)}[A-Za-z0-9_.\-]+)",
        text,
    )
    return match.group(1) if match else None


def _budget_reads(text: str) -> dict[str, str]:
    """The values that hold a budget: the name → the record key it reads.

    A module reads the record into a value and hands that value to the test
    task, so the link between the record and the heap is a name. Following the
    name is what makes the check more than a search for a string.
    """
    reads: dict[str, str] = {}
    for name, (initializer, _line) in _val_statements(text).items():
        key = _read_key(initializer)
        if key is not None:
            reads[name] = key
    return reads


def _budget_findings(
    tree: pathlib.Path, record: dict[str, tuple[str, int]]
) -> list[str]:
    """The findings about the test tasks' heaps and the daemon's heap."""
    findings: list[str] = []
    default = record.get(DEFAULT_KEY)
    daemon = record.get(DAEMON_KEY)

    # ── The daemon's heap ────────────────────────────────────────────────────
    properties_path = tree / GRADLE_PROPERTIES
    if not properties_path.is_file():
        findings.append(
            f"{GRADLE_PROPERTIES}:1: the Gradle properties file is missing, so the daemon's "
            f"heap cannot be checked against the recorded one ({DAEMON_KEY}="
            f"{daemon[0] if daemon else 'unknown'})."
        )
    else:
        text = properties_path.read_text(encoding="utf-8")
        match = _JVM_ARGS.search(text)
        if match is None:
            findings.append(
                f"{GRADLE_PROPERTIES}:1: no `org.gradle.jvmargs` is declared, so the Gradle "
                f"daemon runs with Gradle's own default heap instead of the recorded "
                f"{daemon[0] if daemon else 'one'}."
            )
        elif daemon is not None:
            line = text.count("\n", 0, match.start()) + 1
            xmx = _XMX.search(match.group(1))
            if xmx is None:
                findings.append(
                    f"{GRADLE_PROPERTIES}:{line}: `org.gradle.jvmargs` declares no `-Xmx`, so "
                    f"the daemon runs with Gradle's own default heap instead of the recorded "
                    f"{daemon[0]}."
                )
            elif xmx.group(1) != daemon[0]:
                findings.append(
                    f"{GRADLE_PROPERTIES}:{line}: the Gradle daemon's heap is {xmx.group(1)}, "
                    f"and the record declares {daemon[0]} "
                    f"({RECORD}, {DAEMON_KEY}, measured {record.get('measuredAt', ('an unrecorded date', 1))[0]}). "
                    f"One of the two is wrong, and the build does not say which: the daemon's "
                    f"heap is what the whole worktree's ceiling is made of."
                )

    # ── The test tasks' heaps ────────────────────────────────────────────────
    root_script = tree / ROOT_BUILD_SCRIPT
    root_text = _strip_comments(root_script.read_text(encoding="utf-8")) if root_script.is_file() else ""
    root_applies_default = (
        "maxHeapSize" in root_text and _reads_key(root_text, DEFAULT_KEY)
    )

    for project, script in _modules(tree):
        declarations = _heap_declarations(script)
        declares_test_task = _declares_test_task(script)
        if not declarations and not declares_test_task:
            # Neither a heap of its own nor a test task it declares: the module
            # says nothing about a heap, so there is nothing to check.
            continue
        relative = script.relative_to(tree).as_posix()
        key = _module_key(project)
        if key not in record:
            if declarations:
                findings.append(
                    f"{relative}: the module {project} names a heap of its own, and the "
                    f"record ({RECORD}) holds no budget for it — no `{key}` line. A heap "
                    f"that is written down in a build script and not in the record is a "
                    f"heap nobody can check, and the record is the one place the budget "
                    f"is written down."
                )
            else:
                findings.append(
                    f"{relative}: the module {project} declares a test task, and the record "
                    f"({RECORD}) holds no budget for it — no `{key}` line. A module that runs "
                    f"tests with no recorded heap is a module whose heap nobody can check, and "
                    f"the record is the one place the budget is written down."
                )
            continue
        recorded = record[key][0]

        if not declarations:
            # No heap of its own: the module takes the default, and the root
            # has to apply it — otherwise it runs with Gradle's default, which
            # is not a budget.
            if default is None:
                findings.append(
                    f"{relative}: the module {project} declares a test task and no heap of "
                    f"its own, and the record ({RECORD}) declares no {DEFAULT_KEY} for it to "
                    f"take."
                )
            elif recorded != default[0]:
                findings.append(
                    f"{relative}: the module {project} declares a test task and no heap of "
                    f"its own, and the record declares {recorded} for it — which is not the "
                    f"default ({default[0]}). A module with no heap of its own runs with "
                    f"the default, so the record and the build disagree about what it runs "
                    f"with."
                )
            elif not root_applies_default:
                findings.append(
                    f"{relative}: the module {project} declares a test task and no heap of "
                    f"its own, and {ROOT_BUILD_SCRIPT} does not apply the recorded default "
                    f"({DEFAULT_KEY}={default[0]}) to the build's test tasks. Without it "
                    f"this module runs with Gradle's own default heap, which is not the "
                    f"budget the record declares."
                )
            continue

        script_text = _strip_comments(script.read_text(encoding="utf-8"))
        reads = _budget_reads(script_text)
        for line, literal, expression in declarations:
            if literal is not None:
                if literal != recorded:
                    findings.append(
                        f"{relative}:{line}: the module {project} runs its test tasks with a "
                        f"heap of {literal}, and the record declares {recorded} "
                        f"({RECORD}, {key}). One of the two is wrong, and the measurement "
                        f"behind the record does not describe this build any more."
                    )
                continue
            # The heap is READ. It has to be the record's line for THIS module.
            read = reads.get(expression)
            if read is None:
                read = _read_key(expression)
            if read == key:
                continue
            if read is not None:
                findings.append(
                    f"{relative}:{line}: the module {project} reads the budget of another "
                    f"module ({read}) instead of its own ({key}). A module that runs with "
                    f"another module's heap is a module whose heap nobody checked."
                )
                continue
            findings.append(
                f"{relative}:{line}: the module {project} sets its test tasks' heap from "
                f"{expression!r}, and not from the record ({RECORD}, {key}). A heap that is "
                f"written anywhere but the record is a heap nobody can check against the "
                f"measurement behind it."
            )
    return findings


def _reads_key(text: str, key: str) -> bool:
    """Does the text read this key out of the record?

    A read is a `getProperty("…")` or an index — the two shapes a properties
    file is read with. A mention of the key in a message is not a read: the
    build scripts name the key they expect in the error they throw when it is
    missing, and that mention is not a read of the record.
    """
    pattern = re.escape(key)
    return bool(
        re.search(rf"getProperty\(\s*\"{pattern}\"\s*\)", text)
        or re.search(rf"\[\s*\"{pattern}\"\s*\]", text)
    )


def _blocks(text: str, opener: re.Pattern[str]) -> list[tuple[str, int, int]]:
    """The body of every block *opener* opens, with its offsets.

    Brace matching, so the body of a `verify { … }` inside a `kover { … }` is
    the block's own body and not the rest of the file. The offsets are the
    body's own — the one after the opener's brace — so that a line number can
    be computed against the text the block came from.
    """
    bodies: list[tuple[str, int, int]] = []
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
                    bodies.append((text[match.end():index], match.end(), index))
                    break
            index += 1
    return bodies


def _verify_rules(text: str) -> dict[str, tuple[str, int]]:
    """The Kover verify rules of a script: the rule's name → (body, line).

    Only the rules inside a `kover { … verify { … } }` block count: a `rule(…)`
    in prose or in another plugin's DSL is not a coverage floor.
    """
    stripped = _strip_comments(text)
    rules: dict[str, tuple[str, int]] = {}
    for kover_body, kover_start, _ in _blocks(stripped, _KOVER_BLOCK):
        for verify_body, verify_start, _ in _blocks(kover_body, _VERIFY):
            absolute = kover_start + verify_start
            for match in _RULE.finditer(verify_body):
                opening = verify_body.find("{", match.end())
                if opening < 0:
                    continue
                depth = 0
                index = opening
                while index < len(verify_body):
                    char = verify_body[index]
                    if char == "{":
                        depth += 1
                    elif char == "}":
                        depth -= 1
                        if depth == 0:
                            break
                    index += 1
                line = stripped.count("\n", 0, absolute + match.start()) + 1
                rules[match.group("name")] = (verify_body[opening + 1:index], line)
    return rules


def _declared_constants(text: str) -> set[str]:
    """The names a Kotlin script declares with `val`."""
    return {match.group(1) for match in _VAL_DECLARATION.finditer(text)}


def _module_segments(project: str) -> list[str]:
    """The name segments of a project path: `:tools:a` → `["tools", "a"]`."""
    return [part for part in project.lstrip(":").split(":") if part]


def _floor_findings(tree: pathlib.Path) -> list[str]:
    """The findings about where the coverage floors are declared.

    The app floor's location is `scripts/check-coverage-floor-declaration.py`'s
    job — the aggregation plugin reads its rules from `settings.gradle.kts`, and
    that gate checks it. What is checked here is the MODULE floors: a floor
    that measures one module is read by that module's own Kover extension, so
    the constant it binds has to be declared in the same file as the rule that
    binds it.
    """
    findings: list[str] = []
    modules = _modules(tree)
    scripts: list[tuple[str | None, pathlib.Path]] = []
    root_script = tree / ROOT_BUILD_SCRIPT
    if root_script.is_file():
        scripts.append((None, root_script))
    for project, script in modules:
        if script.is_file():
            scripts.append((project, script))

    for project, script in scripts:
        relative = script.relative_to(tree).as_posix()
        text = script.read_text(encoding="utf-8")
        constants = _declared_constants(text)
        for name, (body, line) in _verify_rules(text).items():
            binding = _MIN_VALUE_NAME.search(body)
            if binding is None:
                continue
            bound = binding.group(1) or binding.group(2)
            if bound not in constants:
                findings.append(
                    f"{relative}:{line}: the coverage rule {name!r} binds {bound!r}, and "
                    f"nothing declares that name in this file. A floor bound to a constant "
                    f"declared elsewhere is a floor read from a place its Kover does not "
                    f"read — the constant has to be declared in the same build script as "
                    f"the rule that binds it."
                )
            if project is None:
                continue
            # The rule's NAME is the claim the reader takes away. A rule that
            # names another module measures THIS one, and answers a different
            # question under a name the reader will believe.
            for other, _other_script in modules:
                if other == project:
                    continue
                for segment in _module_segments(other):
                    if name.lower().startswith(segment.lower() + " "):
                        findings.append(
                            f"{relative}:{line}: the coverage rule {name!r} names the module "
                            f"{other}, and it is declared in {project}'s build script — where "
                            f"it measures {project}. A floor named after another module is a "
                            f"claim the reader will believe and the build will not keep: "
                            f"{other}'s floor belongs in {other}'s own build script."
                        )
        if relative == ROOT_BUILD_SCRIPT:
            for match in _VAL_DECLARATION.finditer(_strip_comments(text)):
                name = match.group(1)
                if "floor" not in name.lower():
                    continue
                line = text.count("\n", 0, match.start()) + 1
                findings.append(
                    f"{relative}:{line}: {name} is a coverage floor, and it is declared in the "
                    f"ROOT build script — where no Kover reads it. The root project has no "
                    f"Kover extension of its own to configure: the aggregation plugin owns "
                    f"the aggregate and reads the app floor from {SETTINGS_SCRIPT}, and a "
                    f"module floor is read from that module's own build script. A floor "
                    f"declared here is evaluated by no task."
                )
    return findings


def _dead_record_findings(
    tree: pathlib.Path, record: dict[str, tuple[str, int]]
) -> list[str]:
    """The findings about record lines that name a module the build does not have.

    The record is checked against the build in both directions. A module that
    was removed from the build leaves its line behind, and a line that names
    nothing is a budget nobody reads and nobody checks — the same kind of
    leftover the coverage floor was, one level down. A line added for a module
    that was never in the build is the same defect from the other side: a
    budget for a module that does not exist.
    """
    findings: list[str] = []
    known = {_module_key(project) for project, _ in _modules(tree)}
    for key, (value, line) in record.items():
        if not key.startswith(MODULE_KEY_PREFIX):
            continue
        if key in known:
            continue
        findings.append(
            f"{RECORD}:{line}: the record declares a budget for {key} ({value}), and the "
            f"build has no such module — {SETTINGS_SCRIPT} includes no project for it. A "
            f"budget for a module that does not exist is a number nobody runs and nobody "
            f"checks; remove the line, or add the module to the build."
        )
    return findings


def check(tree: pathlib.Path) -> list[str]:
    """The findings; empty when the tree is correct."""
    record_path = tree / RECORD
    if not record_path.is_file():
        raise FileNotFoundError(
            f"{RECORD} is missing — the memory budget has nowhere to live. The heap of a "
            f"test task and the heap of the Gradle daemon are measurements, and a "
            f"measurement nobody can read is a measurement nobody can check."
        )

    record = _properties(record_path.read_text(encoding="utf-8"))
    findings: list[str] = []
    for key in (DEFAULT_KEY, DAEMON_KEY):
        if key not in record:
            raise FileNotFoundError(
                f"{RECORD} declares no {key} — the budget is incomplete, and an incomplete "
                f"budget cannot be checked."
            )

    findings.extend(_budget_findings(tree, record))
    findings.extend(_dead_record_findings(tree, record))
    findings.extend(_floor_findings(tree))
    return findings


@click.command()
@click.option("--quiet", is_flag=True, help="Print nothing when the gate is clean.")
@click.option(
    "--root",
    default=None,
    help="The tree to check (default: the repository this script lives in).",
)
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON.")
@click.pass_context
def cli(ctx: click.Context, quiet: bool, root: str | None, emit_json: bool) -> None:
    """Check that every test task runs with the heap the record declares."""
    tree = pathlib.Path(root).resolve() if root else ROOT
    if not tree.is_dir():
        message = f"--root {root} is not a directory."
        if emit_json:
            click.echo(
                json.dumps(
                    {"passed": False, "blocking_errors": [message], "warnings": []},
                    ensure_ascii=False,
                )
            )
        else:
            click.echo(f"ERROR: {message}")
        raise ctx.exit(CONFIG_ERROR)

    try:
        findings = check(tree)
    except FileNotFoundError as error:
        message = str(error)
        if emit_json:
            click.echo(
                json.dumps(
                    {"passed": False, "blocking_errors": [message], "warnings": []},
                    ensure_ascii=False,
                )
            )
        else:
            click.echo(f"ERROR: {message}")
        raise ctx.exit(CONFIG_ERROR)

    if emit_json:
        click.echo(
            json.dumps(
                {
                    "passed": not findings,
                    "blocking_errors": findings,
                    "warnings": [],
                },
                ensure_ascii=False,
            )
        )
        raise ctx.exit(CLEAN if not findings else VALIDATION_FAILURE)

    for finding in findings:
        click.echo(f"ERROR: {finding}")
    if findings:
        click.echo("")
        click.echo(
            "check-memory-budget: the heap of every test task, and the daemon's heap, have "
            f"to be the ones {RECORD} declares — and every coverage floor has to be "
            "declared where its Kover reads it."
        )
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        click.echo(
            "check-memory-budget: OK — every test task in the tree runs with the heap "
            f"{RECORD} declares, the daemon's heap is the recorded one, and every coverage "
            "floor is declared where its Kover reads it."
        )
    raise ctx.exit(CLEAN)


def main(argv: list[str] | None = None) -> int:
    """The gate's entry point; the exit code the registry reads."""
    try:
        return run(cli.main, argv)
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001 — an internal error is an exit code, not a traceback
        click.echo(f"check-memory-budget: internal error: {exc}", err=True)
        return INTERNAL_ERROR


if __name__ == "__main__":
    raise SystemExit(main())

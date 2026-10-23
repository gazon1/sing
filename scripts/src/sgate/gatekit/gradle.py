"""sgate.gatekit.gradle — what the Gradle build declares.

Five gates asked the build the same three questions, and each answered them its
own way:

  * *which modules exist?* — `check-doc-dead-refs.py` and
    `check-pro-licence-boundary.py` each kept a hand-written list of build
    files. A module added to `settings.gradle.kts` was invisible to both until
    somebody remembered to type its name in two places, and the lists had
    already drifted apart from each other.

  * *where is a module's build file?* — `check-gate-wiring.py` walked
    `*/build.gradle.kts` three times over, and `check-test-task-inputs.py`
    walked the same tree with its own copy of the walk.

  * *what is inside a named block?* — `check-gate-wiring.py` read `kover { }`
    with a brace matcher and `detekt { }` with a regex that cannot balance
    braces, so the two answers to the same question could disagree. The regex
    stops at the first line that starts with `}`; a nested block inside `detekt`
    ends it early, and the body it hands the rule is truncated.

The build answers none of these questions differently per gate, so the answers
live here, once:

  * `modules` — the modules the build includes, read from `settings.gradle.kts`;
  * `module_build_files` — the build file of every module in the tree;
  * `blocks` — the body of every named block, brace-matched.

ADR `docs/decisions/2026-10-22-the-gate-suite-runs-this-checkout.md`.
"""
from __future__ import annotations

import re
from pathlib import Path

#: `include(":shared")` — the settings file's way of naming a module.
INCLUDE_RE = re.compile(r'include\s*\(\s*":(?P<name>[A-Za-z0-9_:-]+)"\s*\)')

#: A block comment, replaced by as many newlines as it had, so the line numbers
#: of the text around it do not move.
_BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)

#: A full-line `//` comment. A comment that ends a line is left alone: it is
#: prose, and a prose line is not a brace.
_LINE_COMMENT_RE = re.compile(r"^\s*//.*$", re.MULTILINE)

#: Directories that are not modules, however they came to be in the tree.
#: `build-logic` holds convention plugins, which configure other projects'
#: tasks and cannot be resolved to one module; the rest are noise.
SKIP_DIRS = frozenset({"build-logic", "node_modules", ".git", "build", "gradle"})


def _strip_comments(text: str) -> str:
    """The text with its comments removed, keeping the line numbering intact.

    Comments matter here for a specific reason: a comment above a block quotes
    the API by name (`reports { total { verify { rule { bound {`), and prose
    that names a construct must not count as declaring it. A comment with
    unbalanced braces would otherwise derail the matcher entirely.
    """
    without_block = _BLOCK_COMMENT_RE.sub(
        lambda m: "\n" * m.group(0).count("\n"), text
    )
    return _LINE_COMMENT_RE.sub("", without_block)


def included_modules(text: str) -> list[str]:
    """The modules a settings file includes, in the order it includes them.

    The names are the ones the settings file uses, without the leading colon:
    `include(":shared")` gives `shared`, `include(":tools:unwritten-properties")`
    gives `tools:unwritten-properties`.

    A module included behind a guard — `if (withPro) { include(":pro") }` — is
    still included by the file: the guard decides whether the *build* takes the
    branch, not whether the module is part of the project.
    """
    out: list[str] = []
    for match in INCLUDE_RE.finditer(_strip_comments(text)):
        name = match.group("name")
        if name not in out:
            out.append(name)
    return out


def modules(root: Path) -> list[str]:
    """The modules the build includes, read from `settings.gradle.kts`.

    An empty list when the settings file is missing: a gate that cannot see the
    module list says so, rather than reporting on a list of its own.
    """
    settings = root / "settings.gradle.kts"
    if not settings.is_file():
        return []
    return included_modules(settings.read_text(encoding="utf-8", errors="replace"))


def module_build_files(root: Path) -> list[Path]:
    """The build file of every module in the tree, top-level modules only.

    Top-level only, and by directory rather than by the settings file, because
    two things are true at once and both matter: a module may exist on disk
    while the build does not include it (`:pro`, behind the `withPro` guard),
    and a module may be included while living in a nested directory
    (`:tools:unwritten-properties`, which is a KSP processor and not a Gradle
    module with tasks of its own). A gate asking "which modules ship in the
    free build" wants the former; a gate asking "which build files exist"
    wants both, minus the nested ones.
    """
    out: list[Path] = []
    for child in sorted(root.iterdir()):
        if not child.is_dir() or child.name in SKIP_DIRS or child.name.startswith("."):
            continue
        candidate = child / "build.gradle.kts"
        if candidate.is_file():
            out.append(candidate)
    return out


def build_files_of(root: Path, names: list[str]) -> list[Path]:
    """The build file of each named module, in the order the names are given.

    A module with no build file on disk is skipped: a module named in
    `settings.gradle.kts` and a directory in the tree are two facts, and the
    second one is what this answers.
    """
    out: list[Path] = []
    for name in names:
        candidate = root.joinpath(*name.split(":"))
        build_file = candidate / "build.gradle.kts"
        if build_file.is_file():
            out.append(build_file)
    return out


def module_build_relpaths(root: Path) -> list[str]:
    """The build file of every module the build includes, relative to the root.

    The paths are the ones a document cites — `shared/build.gradle.kts` — so a
    gate can compare them with what the tree holds without a second spelling of
    the same path.
    """
    return [
        path.relative_to(root).as_posix()
        for path in build_files_of(root, modules(root))
    ]


def blocks(text: str, name: str) -> list[str]:
    """The body of every `name { … }` block in a build script.

    Brace matching, not a regex: a regex cannot balance braces, and the block
    is where the rules live. Without reading only this far, a `verify { }`
    block belonging to some other plugin — or a `minValue` anywhere else in the
    file — would qualify a module that declares no kover floor at all.

    The name is matched on a word boundary, so `kover` does not match
    `subprojects` and `detekt` does not match `detektBaseline`.
    """
    source = _strip_comments(text)
    out: list[str] = []
    for match in re.finditer(r"\b" + re.escape(name) + r"\s*\{", source):
        depth = 0
        for i in range(match.end() - 1, len(source)):
            if source[i] == "{":
                depth += 1
            elif source[i] == "}":
                depth -= 1
                if depth == 0:
                    out.append(source[match.end():i])
                    break
    return out


def has_block(text: str, name: str) -> bool:
    """Whether the build script declares a block with that name."""
    return bool(blocks(text, name))

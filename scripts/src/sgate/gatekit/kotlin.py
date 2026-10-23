"""sgate.gates.kotlin — what the Kotlin sources declare and mention.

A gate that compares a document against the code needs two facts from the
sources: what a file declares, and what names a file mentions. Several gates
needed both and each wrote its own regular expressions — which is how two
gates ended up disagreeing about whether a class is declared.

The rules here are the ones the repository's own conventions state:

* a **port** is an interface declared in a `commonMain` source set and bound in
  a platform module (`PlatformModule.jvm.kt` / `PlatformModule.android.kt`);
* a **link** is declared by an `object …LinkSpec` and implemented by a
  `class …DeepLink` — the naming convention the deep-link registry uses;
* a **type name** is a capitalised identifier.

The module reads the sources as text. It does not compile them: a gate that
needs the compiler is a different kind of gate, and it is a slower one.
"""
from __future__ import annotations

import re
from pathlib import Path

#: `object TaskLinkSpec {` — a declaration the deep-link registry reads.
OBJECT_DECLARATION = re.compile(r"^\s*(?:public\s+|internal\s+|private\s+)?object\s+([A-Za-z_][A-Za-z0-9_]*)", re.MULTILINE)

#: `class TaskDeepLink : AppDeepLink {` — and the same for an interface.
CLASS_DECLARATION = re.compile(
    r"^\s*(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|data\s+)*"
    r"(?:class|interface)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)

#: A type name: a capitalised identifier.
TYPE_NAME = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\b")

#: The source sets the common code lives in.
COMMON_MAIN = "commonMain"


def kotlin_files(root: Path, source_set: str = COMMON_MAIN) -> list[Path]:
    """Every Kotlin file of a source set, sorted."""
    return sorted(
        path
        for path in root.rglob("*.kt")
        if source_set in path.parts
    )


def text_of(path: Path) -> str:
    """The text of a source file."""
    return path.read_text(encoding="utf-8")


def declared_objects(text: str) -> set[str]:
    """The names of the objects the text declares."""
    return set(OBJECT_DECLARATION.findall(text))


def declared_classes(text: str) -> set[str]:
    """The names of the classes and interfaces the text declares."""
    return set(CLASS_DECLARATION.findall(text))


def type_names(text: str) -> set[str]:
    """The type names the text mentions."""
    return set(TYPE_NAME.findall(text))


def files_declaring(root: Path, name: str, source_set: str = COMMON_MAIN) -> list[Path]:
    """The source files that declare the named object, class or interface."""
    found: list[Path] = []
    for path in kotlin_files(root, source_set):
        text = text_of(path)
        if name in declared_objects(text) or name in declared_classes(text):
            found.append(path)
    return found


def link_specs(root: Path, source_set: str = COMMON_MAIN) -> set[str]:
    """The link specs the sources declare — `object …LinkSpec`."""
    specs: set[str] = set()
    for path in kotlin_files(root, source_set):
        specs |= declared_objects(text_of(path))
    return {name for name in specs if name.endswith("LinkSpec")}


def link_matchers(root: Path, source_set: str = COMMON_MAIN) -> dict[str, Path]:
    """The deep-link matchers, by name: `class …DeepLink`."""
    matchers: dict[str, Path] = {}
    for path in kotlin_files(root, source_set):
        for name in declared_classes(text_of(path)):
            if name.endswith("DeepLink"):
                matchers[name] = path
    return matchers

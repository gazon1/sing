"""arch_suite.py — the one reader of the architecture suite's declaration.

## Why this exists

The architecture suite's four facts — the package the gates live in, the path
fragment that package occupies, the directory that holds it, and the directory
its reports land in — were declared in four places: the build's test filter,
the run-floor gate's path fragments, the slow-population gate's report
directories, and the impact map's package name.

A rename that reached one of them left the others naming a package that no
longer existed, and every one of those gates kept reporting green: a gate
that looks for a suite that is not there does not fail, it counts nothing.

So the declaration lives in ONE file — `config/docs/arch-suite.properties` —
and this module is the one reader of it. The build reads the same file through
Gradle's own properties loader.

## What this module is not

It is not a gate and it does not decide anything. It answers "what is the
architecture suite" for the callers that need the answer, and it fails loudly
when the declaration does not say, because a caller that guesses a package name
is the defect this module was written to remove.
"""
from __future__ import annotations

import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
DECLARATION = ROOT / "config" / "docs" / "arch-suite.properties"


class MissingDeclaration(RuntimeError):
    """The declaration file does not say what the caller needs it to say.

    A dedicated type so a caller can name the missing key in its own message —
    a gate that reports "the architecture suite is not declared" is a gate that
    says what to fix; one that reports "no tests ran" is not.
    """


def _values() -> dict[str, str]:
    """The declaration's key/value pairs."""
    values: dict[str, str] = {}
    if not DECLARATION.is_file():
        return values
    for line in DECLARATION.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, _, value = stripped.partition("=")
        values[key.strip()] = value.strip()
    return values


def _require(key: str) -> str:
    value = _values().get(key)
    if not value:
        raise MissingDeclaration(
            f"{DECLARATION.relative_to(ROOT)} declares no '{key}' — the "
            'architecture suite is declared there, and a caller that cannot '
            'read the declaration cannot say what the suite is.'
        )
    return value


def package() -> str:
    """The Kotlin package the architecture gates live in."""
    return _require('arch.package')


def path_fragment() -> str:
    """The package's path fragment, relative to a test source root."""
    return _require('arch.path')


def directory_marker() -> str:
    """The path segment that marks a file as one of the suite's.

    With a leading and a trailing slash, so that it matches a directory and
    not a file whose name merely starts with the same letters.
    """
    return f"/{_require('arch.directory')}/"


def report_dir() -> str:
    """Where the suite's JUnit reports land, relative to the repository root."""
    return _require('arch.report.dir')

#!/usr/bin/env python3
"""check-yaml-duplicate-keys.py — a YAML mapping may not repeat a key.

Why this exists (2026-10-07)
---------------------------
`config/detekt/detekt-rules-module.yml` shipped with `Filename:` twice inside the
same `ktlint:` mapping. Every tool that parses it with SnakeYAML refused the
file outright — `found duplicate key Filename` — so `:detekt-rules:detekt` died
before reading a single source file.

What made it survive to `main` is the shape of the damage. `:shared` and
`:desktopApp` keep their own detekt configs, so the two most-used lint targets
still ran and still reported zero findings. A gate that is *unreachable* is
worse than a gate that is absent: it looks like coverage.

The defect itself is invisible to the editor, to git, and to every tool that does
not parse this specific file. Two branches each adding one key to one mapping is
a legal-looking merge. Only a parse disagrees.

## Why the whole tree, not just the config that broke

The instance is one file. The class is "a file only one consumer parses, so
nobody notices when it stops parsing". `Maestro/flows/**` is consumed by Maestro
and by `check_maestro_flow_tags`; `infra/kiwi/scenarios/**` by the traceability
harness; `.github/workflows/**` by Actions, which reports its own syntax error
far away from the edit that caused it. Scoping the gate to the file that happened
to break would buy nothing and read as a fix.

## What a duplicate key means, per consumer

Not uniformly fatal, which is exactly why it is worth catching centrally:

- **SnakeYAML / PyYAML / Ktor config loaders** — hard error. The consumer is dead.
- **Go YAML (Kubernetes, Actions)** — silently keeps the last value. The consumer
  works, with a configuration nobody wrote.
- **A hand-rolled reader doing `map[key] = value`** — also last-one-wins, and the
  earlier key's whole subtree is discarded.

So the outcome ranges from "the build stops" to "the build proceeds and does
something other than what the file says". The second is worse and quieter.

## The positive control

A parser that quietly stops matching reports success having checked nothing —
the vacuous-gate failure this repository has paid for before
(`2026-10-05-provenance-audit` §4). So:

- `--self-test` builds a synthetic corpus that **does** contain a duplicate at two
  different depths (top level and nested), asserts both are caught, asserts a
  clean corpus is not reported, and asserts that a repeated key in *separate
  documents of one stream* is legal — the multi-document Maestro shape, which a
  naive `yaml.safe_load` would report as a duplicate.
- The scan is not vacuous: if it finds **no** YAML files at all, it fails rather
  than passes. An empty result is only trustworthy when the rule has just been
  proven to fire.
- `scripts/tests/test_check_yaml_duplicate_keys.py` pins the same properties,
  including the multi-document case and the same-key-in-two-lists case.

Usage:
    scripts/check-yaml-duplicate-keys.py           # human-readable report
    scripts/check-yaml-duplicate-keys.py --quiet   # findings only, for the gate
    scripts/check-yaml-duplicate-keys.py --self-test
"""

from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: Directories whose YAML is deliberately not parsed by a YAML library, or is
#: generated at build time. Kept as data so a reader can see exactly what is not
#: covered rather than having to infer it from a glob.
EXCLUDE_DIRS = ("build/", ".gradle/", "node_modules/")

YAML_GLOBS = ("*.yml", "*.yaml")


class DuplicateKeyError(Exception):
    """A mapping repeated a key. Carries the key and where it was found."""

    def __init__(self, key: object) -> None:
        super().__init__(f"duplicate key {key!r}")
        self.key = key


def _no_duplicate_keys() -> type[yaml.SafeLoader]:
    """A SafeLoader that rejects a repeated key instead of keeping the last one.

    PyYAML's own behaviour is last-one-wins with no diagnostic, which is precisely
    the "works, with a configuration nobody wrote" outcome above. The override
    hooks the mapping constructor, so it applies at every nesting depth rather
    than only at the document root.
    """

    class StrictLoader(yaml.SafeLoader):
        pass

    def construct_mapping(loader: yaml.Loader, node: yaml.MappingNode, deep: bool = False) -> dict:
        mapping: dict = {}
        for key_node, _value_node in node.value:
            key = loader.construct_object(key_node, deep=deep)
            if key in mapping:
                raise _PendingDuplicate(key)
            mapping[key] = None
        return _pairs(loader, node, deep)

    StrictLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, construct_mapping)
    return StrictLoader


class _PendingDuplicate(Exception):
    """Internal carrier so the loader can raise before it knows the doc index."""

    def __init__(self, key: object) -> None:
        super().__init__(repr(key))
        self.key = key


def _pairs(loader: yaml.Loader, node: yaml.MappingNode, deep: bool) -> dict:
    mapping: dict = {}
    for key_node, value_node in node.value:
        mapping[loader.construct_object(key_node, deep=deep)] = loader.construct_object(value_node, deep=deep)
    return mapping


def parse_documents(text: str) -> int:
    """Parse every document in a stream, raising `DuplicateKeyError` on any repeat.

    Multi-document streams matter here: `Maestro/flows/**` is one file holding a
    header document and the flow body separated by `---`. A key repeated in two
    *different* documents is legal and common there, so each document is parsed
    as its own mapping rather than the stream being treated as one — otherwise
    every Maestro flow reports a false positive.
    """
    count = 0
    try:
        for _doc in yaml.load_all(text, Loader=_no_duplicate_keys()):
            count += 1
    except _PendingDuplicate as exc:
        raise DuplicateKeyError(exc.key) from exc
    return count


def tracked_yaml_files(root: pathlib.Path) -> list[pathlib.Path]:
    """Every tracked YAML file under `root`, minus the excluded directories.

    Uses `git ls-files` rather than a glob so a file that is gitignored (a local
    override, a scratch config) is not read by a gate: it is not part of what the
    repository claims to contain, so it cannot be a defect in it.
    """
    try:
        out = subprocess.run(
            ["git", "-C", str(root), "ls-files", "--", *YAML_GLOBS],
            capture_output=True,
            text=True,
            check=True,
        ).stdout
    except (subprocess.CalledProcessError, FileNotFoundError):
        # Not a git checkout (a tarball, a CI cache mount): fall back to a walk,
        # which is less precise about gitignored files but never silently skips.
        return _walk_yaml(root)

    files = [root / line for line in out.splitlines() if line.strip()]
    return [f for f in files if f.exists() and not _excluded(f, root)]


def _excluded(path: pathlib.Path, root: pathlib.Path) -> bool:
    try:
        relative = path.relative_to(root).as_posix()
    except ValueError:
        return True
    return any(relative.startswith(prefix) for prefix in EXCLUDE_DIRS)


def _walk_yaml(root: pathlib.Path) -> list[pathlib.Path]:
    found: list[pathlib.Path] = []
    for pattern in YAML_GLOBS:
        for path in root.rglob(pattern):
            if path.is_file() and not _excluded(path, root):
                found.append(path)
    return sorted(found)


# ── the self-test ───────────────────────────────────────────────────────────

#: A repeated key at the top level: what `detekt-rules-module.yml` did.
TOP_LEVEL_DUPLICATE = "a:\n  1\nFilename:\n  active: false\nFilename:\n  active: true\n"

#: A repeated key nested two levels down. A checker that only inspects the root
#: mapping reports this file as clean, which is the near-miss that makes a
#: "looks like it works" gate worse than none.
NESTED_DUPLICATE = "ktlint:\n  standard:\n    Filename:\n      active: true\n    Filename:\n      active: false\n"

#: The legal shapes a naive checker reports as violations.
MULTI_DOCUMENT = "appId: com.example\nname: flow\n---\n- tapOn:\n    id: a\n---\n- tapOn:\n    id: b\n"
SAME_KEY_TWO_LISTS = "first:\n  - name: a\nsecond:\n  - name: b\n"
CLEAN = "a:\n  b: 1\n  c: 2\nd:\n  - 1\n  - 2\n"


def self_test() -> int:
    """Assert the detector fires on both shapes and stays quiet on the legal ones."""
    failures: list[str] = []

    def expect_duplicate(label: str, text: str) -> None:
        try:
            parse_documents(text)
        except DuplicateKeyError:
            return
        failures.append(f"{label}: expected a DuplicateKeyError, got a clean parse")

    def expect_clean(label: str, text: str) -> None:
        try:
            parse_documents(text)
        except DuplicateKeyError as exc:
            failures.append(f"{label}: expected a clean parse, got {exc}")

    expect_duplicate("top-level duplicate", TOP_LEVEL_DUPLICATE)
    expect_duplicate("nested duplicate", NESTED_DUPLICATE)
    expect_clean("multi-document stream", MULTI_DOCUMENT)
    expect_clean("same key in two lists", SAME_KEY_TWO_LISTS)
    expect_clean("clean document", CLEAN)

    # A duplicate inside one document of a stream, to prove the multi-document
    # support did not weaken the check.
    expect_duplicate(
        "duplicate inside a multi-document stream",
        "appId: com.example\n---\nname: a\nname: b\n",
    )

    if failures:
        print("check-yaml-duplicate-keys: self-test FAILED\n", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print("check-yaml-duplicate-keys: self-test passed (5 shapes)")
    return 0


# ── main ────────────────────────────────────────────────────────────────────


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="findings only")
    parser.add_argument("--self-test", action="store_true", help="prove the detector fires")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    files = tracked_yaml_files(ROOT)
    if not files:
        # The vacuous-pass shape: no files found is indistinguishable from a glob
        # that stopped matching, and reporting that as clean is the failure.
        print(
            "check-yaml-duplicate-keys: no tracked YAML files found — the file list "
            "is empty, so this check verified nothing. A clean result is only "
            "trustworthy when the rule has just been proven to fire (--self-test).",
            file=sys.stderr,
        )
        return 1

    duplicates: list[tuple[pathlib.Path, str]] = []
    for path in files:
        text = path.read_text(encoding="utf-8", errors="replace")
        try:
            parse_documents(text)
        except DuplicateKeyError as exc:
            duplicates.append((path, str(exc)))
        except yaml.YAMLError as exc:
            # A parse failure is not this gate's business, and reporting it here
            # would make this gate fail for another gate's defect.
            if not args.quiet:
                print(f"  skipped (not valid YAML, another gate's business): {path.relative_to(ROOT)}")

    if not args.quiet:
        print(f"check-yaml-duplicate-keys: {len(files)} tracked YAML file(s) parsed")

    if duplicates:
        print(f"\ncheck-yaml-duplicate-keys: {len(duplicates)} duplicate key(s)\n", file=sys.stderr)
        for path, detail in duplicates:
            print(f"  - {path.relative_to(ROOT)}: {detail}", file=sys.stderr)
        print(
            "\nA repeated key is a hard error for SnakeYAML and silently last-one-wins "
            "for several other readers. The key is not in effect; the file says "
            "something other than what it contains.",
            file=sys.stderr,
        )
        return 1

    if not args.quiet:
        print("check-yaml-duplicate-keys: no duplicate keys")
    return 0


if __name__ == "__main__":
    sys.exit(main())
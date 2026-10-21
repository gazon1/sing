#!/usr/bin/env python3
"""document_corpus.py — the single place that knows what the document corpora are.

The decisions have their own policy (`config/docs/adr-corpus.json`, read by
`adr_corpus.py` and by `AdrCorpus.kt`). The *documents* — the architecture notes, the
procedures, the specifications — had no policy at all: their roots were string
literals in the `SearchScope` enum, and only the Kotlin search engine knew them. So
the gates that read the same trees each carried their own copy of the path, and the
Python side could not answer "which files are documents?" at all.

The rules now live in `config/docs/document-corpus.json`, read by every consumer
(Python via stdlib `json`, Kotlin via kotlinx-serialization) — the same arrangement
the ADR corpus has had since 2026-10-10, and for the same reason: a policy only one
side can read is a policy the other side ignores.

`DocumentCorpusReadersAgreeTest` compares this reader with the Kotlin one, so the two
cannot drift apart unnoticed. The walk is deliberately identical to the Kotlin one:
same roots, same depth bound, same extension filter — a reader that walked
differently would disagree about what a search can find, which is the thing the
agreement test exists to prevent.
"""

from __future__ import annotations

import dataclasses
import json
import pathlib

ROOT: pathlib.Path = pathlib.Path(__file__).resolve().parent.parent

#: Where the policy lives, relative to the repo root.
CONFIG_PATH = pathlib.Path('config') / 'docs' / 'document-corpus.json'

#: The policy a checkout without the file falls back to.
#:
#: The same roots the literals had. A missing policy file must not change what a
#: search can see — it must only mean that the policy is not there.
DEFAULT_MAX_DEPTH = 6
DEFAULT_FILE_EXTENSION = '.md'
DEFAULT_ROOTS: dict[str, list[str]] = {
    'architecture': ['docs/architecture'],
    'skills': ['.agents/skills'],
    'specs': ['openspec/specs'],
}


@dataclasses.dataclass(frozen=True)
class DocumentCorpusConfig:
    """The policy: where the documents live, how deep the walk goes, what a document is."""

    max_depth: int
    file_extension: str
    roots: dict[str, list[str]]

    def roots_for(self, scope: str) -> list[str]:
        """The roots of a scope, or nothing when the scope is not declared."""
        return list(self.roots.get(scope, []))

    def is_declared(self, scope: str) -> bool:
        """Whether the policy declares this scope at all."""
        return scope in self.roots


def load(root: pathlib.Path | None = None) -> DocumentCorpusConfig:
    """Read `config/docs/document-corpus.json`, or fall back to the defaults.

    The fallback is the same one the Kotlin reader uses, so a checkout without the
    policy file still has two readers that agree.
    """
    base = root or ROOT
    path = base / CONFIG_PATH
    if not path.is_file():
        return DocumentCorpusConfig(
            max_depth=DEFAULT_MAX_DEPTH,
            file_extension=DEFAULT_FILE_EXTENSION,
            roots={scope: list(roots) for scope, roots in DEFAULT_ROOTS.items()},
        )
    raw = json.loads(path.read_text(encoding='utf-8'))
    corpora = raw.get('corpora') or {}
    roots: dict[str, list[str]] = {}
    for scope, value in corpora.items():
        declared = value.get('roots') if isinstance(value, dict) else None
        if not isinstance(declared, list):
            continue
        cleaned = [str(item) for item in declared if str(item).strip()]
        if cleaned:
            roots[scope] = cleaned
    return DocumentCorpusConfig(
        max_depth=int(raw.get('maxDepth', DEFAULT_MAX_DEPTH)),
        file_extension=str(raw.get('fileExtension', DEFAULT_FILE_EXTENSION)),
        roots=roots or {scope: list(items) for scope, items in DEFAULT_ROOTS.items()},
    )


def documents_in(
    root: pathlib.Path,
    cfg: DocumentCorpusConfig,
    scope: str,
) -> list[pathlib.Path]:
    """The documents of a scope, as paths relative to the repository root.

    The walk matches the Kotlin one exactly: a directory is listed while its depth is
    not past the bound, and a file is a document when its name ends with the configured
    extension.
    """
    found: list[pathlib.Path] = []

    def walk(directory: pathlib.Path, depth: int) -> None:
        if depth > cfg.max_depth:
            return
        try:
            entries = sorted(directory.iterdir())
        except OSError:
            return
        for entry in entries:
            if entry.is_dir():
                walk(entry, depth + 1)
            elif entry.name.endswith(cfg.file_extension):
                found.append(entry)

    for relative in cfg.roots_for(scope):
        directory = root / relative
        if not directory.is_dir():
            continue
        walk(directory, 0)
    return sorted(found, key=lambda path: path.relative_to(root).as_posix())


def relative_paths(root: pathlib.Path, cfg: DocumentCorpusConfig, scope: str) -> list[str]:
    """The documents of a scope, as paths relative to the repository root.

    The strings use `/` on every platform, so the answer means the same thing in the
    Kotlin reader and in this one.
    """
    return [path.relative_to(root).as_posix() for path in documents_in(root, cfg, scope)]


if __name__ == '__main__':
    import sys

    _config = load()
    _arguments = [arg for arg in sys.argv[1:] if not arg.startswith('-')]
    _scope = _arguments[0] if _arguments else 'architecture'
    if '--documents' not in sys.argv:
        print(
            'usage: document_corpus.py --documents <scope> '
            f"(scopes: {', '.join(sorted(_config.roots))})",
            file=sys.stderr,
        )
        raise SystemExit(2)
    if not _config.is_declared(_scope):
        print(f'unknown scope: {_scope}', file=sys.stderr)
        raise SystemExit(2)
    print('\n'.join(relative_paths(ROOT, _config, _scope)))

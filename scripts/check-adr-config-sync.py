#!/usr/bin/env python3
"""check-adr-config-sync.py — no consumer may re-derive the ADR corpus (#520).

Why this exists (2026-10-10): five consumers discovered `docs/decisions/` five
different ways, and they had already drifted apart.

    consumer                         rule                     saw deferred/
    -------------------------------- ------------------------ -------------
    check_adr_status.py               dated, top level        no
    refresh-decisions-digest.py       dated, recursive        yes
    normalize-adr-frontmatter.py      dated, top level        no
    check-backlog-status.py           deferred/ + deleted fd  (only here)
    AdrTools.kt                       any *.md, archive too   yes (archive too)

The status gate reported "all statuses in vocabulary" while 129 files under
`deferred/` used a vocabulary of their own. Not carelessness — five
unagreed definitions of one directory, each of which looked correct.

The rules now live in `config/docs/adr-corpus.json`. This gate makes the
agreement structural instead of cultural: a consumer that grows its own glob
fails here instead of failing silently for three months.

Two things are checked:

  1. Every known consumer resolves its corpus through `adr_corpus`, and no consumer
     hardcodes a glob over `docs/decisions/`.
  2. `AdrCorpusConfig.DEFAULT` in Kotlin matches the JSON. It is the fallback for
     checkouts that have no `config/` directory, and a fallback that drifts is a
     second source of truth with extra steps.
"""

from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Consumers that must resolve the corpus through adr_corpus, and the call that
# proves it. A consumer that stops calling loses this check, which is the point.
PYTHON_CONSUMERS = {
    'check_adr_status.py': 'adr_corpus.load(',
    'refresh-decisions-digest.py': 'adr_corpus.load(',
    'normalize-adr-frontmatter.py': 'adr_corpus.load(',
    'check-backlog-status.py': 'adr_corpus.load(',
}

KOTLIN_CONFIG = 'shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/AdrCorpus.kt'
KOTLIN_CONSUMER = 'shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/AdrTools.kt'

# A glob/iterdir/rglob over the corpus root, in any consumer but adr_corpus itself.
# `adr_corpus.py` is the owner and is exempt by construction.
OWNER = 'scripts/adr_corpus.py'
GLOB_RE = re.compile(r"""\.(?:r?glob|iterdir)\(|listDirectoryEntries\(""")
CORPUS_LITERAL = re.compile(r"""['"][^'"]*docs/decisions['"]""")


def check_consumers(errors: list[str]) -> None:
    for name, required in PYTHON_CONSUMERS.items():
        path = ROOT / 'scripts' / name
        if not path.is_file():
            errors.append(f'{name}: missing')
            continue
        text = path.read_text(encoding='utf-8')
        if required not in text:
            errors.append(f'{name}: does not import adr_corpus ({required} not found)')

    kt = ROOT / KOTLIN_CONSUMER
    if not kt.is_file():
        errors.append(f'{KOTLIN_CONSUMER}: missing')
    elif 'AdrCorpusConfig' not in kt.read_text(encoding='utf-8'):
        errors.append(f'{KOTLIN_CONSUMER}: does not use AdrCorpusConfig')


def check_no_own_globs(errors: list[str]) -> None:
    """No consumer may walk the corpus itself."""
    targets = [ROOT / 'scripts' / n for n in PYTHON_CONSUMERS]
    targets.append(ROOT / KOTLIN_CONSUMER)
    for path in targets:
        if not path.is_file():
            continue
        for i, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
            code = line.split('#', 1)[0]
            if GLOB_RE.search(code) and 'corpus' not in code.lower():
                errors.append(
                    f'{path.relative_to(ROOT)}:{i}: walks the corpus itself '
                    f'— ask adr_corpus instead: {line.strip()[:80]}'
                )
            if CORPUS_LITERAL.search(code):
                errors.append(
                    f'{path.relative_to(ROOT)}:{i}: hardcodes a docs/decisions path '
                    f'— read it from adr_corpus: {line.strip()[:80]}'
                )


def check_kotlin_defaults(errors: list[str]) -> None:
    """The Kotlin fallback must equal the JSON, or it is a second source of truth."""
    cfg_path = ROOT / 'config' / 'docs' / 'adr-corpus.json'
    kt_path = ROOT / KOTLIN_CONFIG
    if not cfg_path.is_file() or not kt_path.is_file():
        errors.append('config/docs/adr-corpus.json or AdrCorpus.kt missing')
        return

    cfg = json.loads(cfg_path.read_text(encoding='utf-8'))
    kt = kt_path.read_text(encoding='utf-8')

    expected = {
        'root': cfg['root'],
        'decisionPattern': cfg['decisionPattern'],
        'archiveDir': cfg['archiveDir'],
        'deferredDir': cfg['deferredDir'],
        'archivedStatus': cfg['vocabulary']['archivedStatus'],
    }
    for key, value in expected.items():
        # Kotlin literals wrap, so match the value rather than the whole statement.
        if f'"{value}"' not in kt:
            errors.append(
                f'AdrCorpusConfig.DEFAULT.{key} does not match adr-corpus.json '
                f'(expected "{value}")'
            )

    for status in cfg['vocabulary']['statuses']:
        if f'"{status}"' not in kt:
            errors.append(f'AdrCorpusConfig.DEFAULT.statuses missing "{status}"')

    if str(cfg['staleDays']) not in kt:
        errors.append(f"AdrCorpusConfig.DEFAULT.staleDays should be {cfg['staleDays']}")


def main() -> int:
    errors: list[str] = []
    check_consumers(errors)
    check_no_own_globs(errors)
    check_kotlin_defaults(errors)

    if errors:
        for err in errors:
            print(f'ERROR: {err}', file=sys.stderr)
        print(
            '\nEvery consumer resolves the ADR corpus through scripts/adr_corpus.py '
            'and config/docs/adr-corpus.json. A consumer that grows its own glob '
            'reintroduces the blind spot #520 exists to close.',
            file=sys.stderr,
        )
        return 1

    print(
        f'check-adr-config-sync: OK — {len(PYTHON_CONSUMERS)} Python consumers + '
        f'AdrStorage resolve the corpus through adr_corpus; '
        f'Kotlin defaults match config/docs/adr-corpus.json'
    )
    return 0


if __name__ == '__main__':
    sys.exit(main())
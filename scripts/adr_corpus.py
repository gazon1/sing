#!/usr/bin/env python3
"""adr_corpus.py — the single place that knows what the ADR corpus is.

Why this exists (2026-10-10, #520): five consumers discovered the same corpus
five different ways, and they had already drifted apart.

    consumer                        rule                    sees deferred/
    ------------------------------- ----------------------- --------------
    check_adr_status.py:51          glob dated, top level   no
    refresh-decisions-digest.py:28  glob dated, recursive   yes
    normalize-adr-frontmatter.py:228 glob dated, top level no
    check-backlog-status.py:262     deferred/ + a deleted  (only here)
    AdrTools.kt:157                 recursive, any *.md    yes (archive too)

Three of the five could not see `docs/decisions/deferred/`. The status gate
reported "all statuses in vocabulary" while 129 files used a vocabulary of
their own. That was not an oversight — it was five unagreed definitions of the
same directory.

The rules now live in `config/docs/adr-corpus.json`, read by every consumer
(Python via stdlib `json`, Kotlin via kotlinx-serialization). Adding a corpus
rule means editing that one file, not hunting four globs.

Three classes, deliberately not one:

* **decision** — a dated record under `root`, matching `decisionPattern`.
* **archived** — a dated record under `root/archive/`; left the live corpus but
  still a resolvable reference target. Moving is not deleting.
* **deferred** — backlog under `root/deferred/`. **Not decisions.** Slug names,
  no real date, different status vocabulary. Validating these as ADRs is what
  produced the blind spot in the first place.

`normalize_status` folds case and strips brackets. It deliberately does NOT map
out-of-vocabulary values (`CLOSED` -> `accepted`): that is a one-time migration,
and doing it silently would let a typo be reinterpreted as a decision.
"""

from __future__ import annotations

import json
import pathlib
import re
from dataclasses import dataclass

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFIG_PATH = ROOT / 'config' / 'docs' / 'adr-corpus.json'


@dataclass(frozen=True)
class AdrCorpusConfig:
    root: pathlib.Path
    decision_pattern: re.Pattern[str]
    archive_dir: str
    deferred_dir: str
    ignored_files: frozenset[str]
    statuses: frozenset[str]
    archived_status: str
    stale_days: int

    # -- discovery ----------------------------------------------------------

    def decisions(self, *, include_archived: bool = False) -> list[pathlib.Path]:
        """Dated ADR records. Excludes `archive/` unless asked."""
        return decisions_in(self.root, self, include_archived=include_archived)

    def archived(self) -> list[pathlib.Path]:
        return archived_in(self.root, self)

    def deferred(self) -> list[pathlib.Path]:
        """Backlog entries. Not ADR decisions — see the module docstring."""
        return deferred_in(self.root, self)

    def all_slugs(self) -> set[str]:
        """Every slug that exists, wherever it lives.

        Includes `archive/` because a superseded target that was archived is
        still resolvable, and includes `deferred/` because those slugs are
        referenced from prose and from other ADRs.
        """
        return all_slugs_in(self.root, self)

    def _is_decision(self, path: pathlib.Path) -> bool:
        return bool(self.decision_pattern.match(path.stem))

    # -- status -------------------------------------------------------------

    def is_valid_status(self, status: str) -> bool:
        return status in self.statuses or status == self.archived_status

    def normalize_status(self, raw: str | None) -> str:
        """Fold case and strip brackets. No value mapping — see docstring.

        `OPEN` and `open` must answer the same question everywhere; a policy
        gate and a query that disagree on what "open" means is worse than
        either being wrong alone.
        """
        if raw is None:
            return ''
        return raw.strip().strip('[]"\'').lower()


# ── discovery, root passed explicitly ─────────────────────────────────────────
#
# The methods above are conveniences for the real repo. These take a root because
# consumers' tests point the corpus at a temporary directory and monkeypatch their
# own module global — binding discovery to `cfg.root` would silently ignore that.


def decisions_in(
    root: pathlib.Path,
    cfg: AdrCorpusConfig,
    *,
    include_archived: bool = False,
) -> list[pathlib.Path]:
    out = [
        p for p in sorted(root.glob('*.md'))
        if cfg._is_decision(p) and p.name not in cfg.ignored_files
    ]
    if include_archived:
        out += archived_in(root, cfg)
    return out


def archived_in(root: pathlib.Path, cfg: AdrCorpusConfig) -> list[pathlib.Path]:
    d = root / cfg.archive_dir
    if not d.is_dir():
        return []
    return [p for p in sorted(d.glob('*.md')) if p.name not in cfg.ignored_files]


def deferred_in(root: pathlib.Path, cfg: AdrCorpusConfig) -> list[pathlib.Path]:
    d = root / cfg.deferred_dir
    if not d.is_dir():
        return []
    return [p for p in sorted(d.glob('*.md')) if p.name not in cfg.ignored_files]


def all_slugs_in(root: pathlib.Path, cfg: AdrCorpusConfig) -> set[str]:
    return (
        {p.stem for p in decisions_in(root, cfg, include_archived=True)}
        | {p.stem for p in deferred_in(root, cfg)}
    )


def load(root: pathlib.Path | None = None) -> AdrCorpusConfig:
    """Read `config/docs/adr-corpus.json` and resolve it against the repo root."""
    base = root or ROOT
    raw = json.loads((base / 'config' / 'docs' / 'adr-corpus.json').read_text(encoding='utf-8'))
    vocab = raw['vocabulary']
    return AdrCorpusConfig(
        root=base / raw['root'],
        decision_pattern=re.compile(raw['decisionPattern']),
        archive_dir=raw['archiveDir'],
        deferred_dir=raw['deferredDir'],
        ignored_files=frozenset(raw['ignoredFiles']),
        statuses=frozenset(vocab['statuses']),
        archived_status=vocab['archivedStatus'],
        stale_days=int(raw['staleDays']),
    )


def read_status(text: str, cfg: AdrCorpusConfig) -> str:
    """Status from a file's frontmatter, normalized. '' when absent."""
    front = frontmatter(text)
    if front is None:
        return ''
    for line in front.splitlines():
        if line.startswith('status:'):
            return cfg.normalize_status(line.split(':', 1)[1])
    return ''


def frontmatter(text: str) -> str | None:
    """The fenced block at the top of the file, without the `---` fences."""
    lines = text.splitlines()
    if not lines or lines[0].strip() != '---':
        return None
    end = next((i for i, l in enumerate(lines[1:], 1) if l.strip() == '---'), None)
    return None if end is None else '\n'.join(lines[1:end])
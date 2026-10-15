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

Two classes, deliberately not three:

* **decision** — a dated record under `root`, matching `decisionPattern`.
* **archived** — a dated record under `root/archive/`; left the live corpus but
  still a resolvable reference target. Moving is not deleting.

The backlog (formerly `deferred/`) was migrated to GH issues #636-643 on 2026-10-15.
See 2026-10-15-backlog-status-gate-removal.md.

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

# Fallbacks for a checkout whose config predates the frontmatter contract (#518).
# Same reasoning as above: a fallback keeps a stale checkout working, and
# check-adr-config-sync.py fails the moment these stop matching the file.
DEFAULT_FRONTMATTER_KEYS = (
    'title', 'date', 'created', 'updated', 'last_updated', 'status', 'tags', 'Tags',
    'deciders', 'decider', 'authors', 'author', 'reviewedBy', 'review-by', 'owner',
    'profile', 'epic', 'description', 'summary', 'id', 'slug', 'type', 'impact',
    'review', 'supersedes', 'superseded-by', 'superseded_by', 'doesNotSupersede',
    'replaces', 'follows', 'decides', 'adr', 'adr-number', 'end-date', 'labels',
    'status-was', 'issuesRelated', 'related', 'references', 'skills', 'context',
    'Context', 'Decision', 'Rationale', 'Consequences', 'Links', 'deprecated-by',
    'gh', 'issue', 'backlog', 'tracked-as',
)
DEFAULT_FRONTMATTER_KEY_ORDER = (
    'title', 'date', 'status', 'tags', 'deciders', 'supersedes', 'superseded-by', 'epic',
)
DEFAULT_SECTION_KEYS = ('Context', 'Decision', 'Rationale', 'Consequences', 'Links')
DEFAULT_NEW_ADR_STATUS = 'open'
DEFAULT_NEW_ADR_KEY_ORDER = ('title', 'date', 'status', 'tags')
DEFAULT_DISCOVER_MAX_DEPTH = 3  # Guards against unbounded filesystem walks (#536)


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
    immutable_since: str | None = None
    review_by_field: str = 'review-by'
    review_by_required: bool = False
    frontmatter_keys: frozenset[str] = frozenset(DEFAULT_FRONTMATTER_KEYS)
    frontmatter_key_order: tuple[str, ...] = DEFAULT_FRONTMATTER_KEY_ORDER
    section_keys: frozenset[str] = frozenset(DEFAULT_SECTION_KEYS)
    new_adr_status: str = DEFAULT_NEW_ADR_STATUS
    new_adr_key_order: tuple[str, ...] = DEFAULT_NEW_ADR_KEY_ORDER
    discover_max_depth: int = DEFAULT_DISCOVER_MAX_DEPTH  # filesystem walk depth limit (#536)

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
    """Backlog entries. Not ADR decisions — directory migrated to GH issues (#636-643).

    Returns [] since the deferred/ directory was deleted in PR #645.
    kept here for dataclass field compatibility with Kotlin (AdrCorpus.kt).
    """
    return []


def all_slugs_in(root: pathlib.Path, cfg: AdrCorpusConfig) -> set[str]:
    """Every slug that exists, wherever it lives.

    Includes `archive/` because a superseded target that was archived is
    still resolvable. Deferred slugs are no longer in the filesystem (GH issues #636-643).
    """
    return {p.stem for p in decisions_in(root, cfg, include_archived=True)}


def load(root: pathlib.Path | None = None) -> AdrCorpusConfig:
    """Read `config/docs/adr-corpus.json` and resolve it against the repo root."""
    base = root or ROOT
    raw = json.loads((base / 'config' / 'docs' / 'adr-corpus.json').read_text(encoding='utf-8'))
    vocab = raw['vocabulary']
    fm = raw.get('frontmatter', {})
    # deferredDir is no longer in the JSON (removed 2026-10-15), but the field
    # stays in the dataclass for Kotlin compatibility.  The value is unused because
    # deferred_in() returns [].
    deferred_dir: str = raw.get('deferredDir', 'deferred')
    return AdrCorpusConfig(
        root=base / raw['root'],
        decision_pattern=re.compile(raw['decisionPattern']),
        archive_dir=raw['archiveDir'],
        deferred_dir=deferred_dir,
        ignored_files=frozenset(raw['ignoredFiles']),
        statuses=frozenset(vocab['statuses']),
        archived_status=vocab['archivedStatus'],
        stale_days=int(raw['staleDays']),
        immutable_since=(raw.get('immutableSince') or {}).get('date'),
        review_by_field=(raw.get('reviewBy') or {}).get('field', 'review-by'),
        review_by_required=bool((raw.get('reviewBy') or {}).get('required', False)),
        frontmatter_keys=frozenset(fm.get('knownKeys', DEFAULT_FRONTMATTER_KEYS)),
        frontmatter_key_order=tuple(fm.get('keyOrder', DEFAULT_FRONTMATTER_KEY_ORDER)),
        section_keys=frozenset(fm.get('sectionKeys', DEFAULT_SECTION_KEYS)),
        new_adr_status=fm.get('newAdrStatus', DEFAULT_NEW_ADR_STATUS),
        new_adr_key_order=tuple(fm.get('newAdrKeyOrder', DEFAULT_NEW_ADR_KEY_ORDER)),
        discover_max_depth=int(
            raw.get('discover', {}).get('maxDepth', DEFAULT_DISCOVER_MAX_DEPTH)
        ),
    )


def read_review_by(text: str, cfg: AdrCorpusConfig) -> str:
    """The `review-by:` date an ADR commits to being revisited, or ''.

    Empty means the decision expressed no intent, and the caller falls back to
    `stale_days`. That fallback is the whole point of #527: 30 days was never a
    decision anybody made, so it is a default rather than a rule.
    """
    front = frontmatter(text)
    if front is None:
        return ''
    for line in front.splitlines():
        if line.startswith(cfg.review_by_field + ':'):
            return cfg.normalize_status(line.split(':', 1)[1])
    return ''


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
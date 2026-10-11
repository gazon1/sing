#!/usr/bin/env python3
"""normalize-backlog-status.py — one-time migration of `deferred/` frontmatter (#516).

Why this is a script and not a runtime mapping
----------------------------------------------
`adr_corpus.normalize_status` folds case and strips brackets, and it deliberately
stops there. It does **not** rewrite `CLOSED` into `accepted`, because doing that
on every read would turn a typo into a policy decision that nobody made.

The backlog needed exactly one such decision, made once, by a human:

    CLOSED    -> closed        OPEN       -> open
    RESOLVED  -> resolved      PARTIALLY  -> partial
    DEFERRED  -> deferred
    `OPEN.`   -> open          `CLOSED.`  -> closed

The trailing period is the interesting half. Four files carry `status: OPEN.` and
`status: CLOSED.` — punctuation, not vocabulary. Left alone they are excluded from
every status query silently, so `adr list-open` under-reports by one and the gate
cannot see them at all.

The backlog vocabulary is deliberately **not** the decision vocabulary. A backlog
entry asks "is this still worth doing"; a decision asks "is this still the answer we
chose". Mapping the 35 open backlog entries onto the decision `open` would then age
them against the 30-day staleness rule — and every one of them is dated 2000-01-01,
because they were never dated at all. They would all fail instantly, and the rule
would have told us nothing.

Usage:
    scripts/normalize-backlog-status.py --dry-run   # report, change nothing
    scripts/normalize-backlog-status.py             # rewrite in place
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import adr_corpus  # noqa: E402

# The decision, made once, on 2026-10-10 (#516). Keys are compared upper-cased and
# stripped of trailing punctuation, so `CLOSED.` and `CLOSED` land on the same entry.
MIGRATION = {
    'OPEN': 'open',
    'CLOSED': 'closed',
    'RESOLVED': 'resolved',
    'PARTIALLY': 'partial',
    'DEFERRED': 'deferred',
}

STATUS_LINE = re.compile(r'^(status:)(.*)$')


def canonical(raw: str, cfg: adr_corpus.AdrCorpusConfig) -> str | None:
    """Target status for `raw`, or None if the file is already in canonical form.

    "Canonical" means lowercase, no trailing punctuation, and in the backlog
    vocabulary — not merely "recognisable". Leaving `status: CLOSED` in a file
    while the writer emits `status: closed` is the same drift #520 was filed
    over, just smaller: the gate and the writer would both accept it, and a
    grep for `status: closed` would still miss 80 files.
    """
    stripped = raw.strip().rstrip('.').strip()
    if not stripped:
        return None
    current = cfg.normalize_status(raw)
    if current in cfg.backlog_statuses:
        # Already in the vocabulary — rewrite only to fix case or punctuation.
        return None if stripped == current else current
    return MIGRATION.get(stripped.upper())


def migrate(path: pathlib.Path, cfg: adr_corpus.AdrCorpusConfig, write: bool) -> str | None:
    text = path.read_text(encoding='utf-8')
    front = adr_corpus.frontmatter(text)
    if front is None:
        return None

    change: str | None = None
    lines_out = []
    inside = False
    for i, line in enumerate(text.splitlines(keepends=True)):
        if i == 0 and line.strip() == '---':
            inside = True
            lines_out.append(line)
            continue
        if inside and line.strip() == '---':
            inside = False
            lines_out.append(line)
            continue
        if inside:
            match = STATUS_LINE.match(line.rstrip('\n'))
            if match:
                target = canonical(match.group(2), cfg)
                if target is not None:
                    change = target
                    newline = '\n' if line.endswith('\n') else ''
                    lines_out.append(f'{match.group(1)} {target}{newline}')
                    continue
        lines_out.append(line)

    if change is not None and write:
        path.write_text(''.join(lines_out), encoding='utf-8')
    return change


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--dry-run', action='store_true',
                        help='report what would change; write nothing')
    args = parser.parse_args()

    cfg = adr_corpus.load()
    entries = cfg.deferred()
    if not entries:
        print('normalize-backlog-status: OK — no deferred/ entries')
        return 0

    changed: dict[str, int] = {}
    unmapped: list[tuple[str, str]] = []
    for path in entries:
        front = adr_corpus.frontmatter(path.read_text(encoding='utf-8'))
        raw = ''
        for line in (front or '').splitlines():
            if line.startswith('status:'):
                raw = line.split(':', 1)[1]
                break
        target = migrate(path, cfg, write=not args.dry_run)
        if target is not None:
            changed[target] = changed.get(target, 0) + 1
        elif raw and canonical(raw, cfg) is None and cfg.normalize_status(raw) not in cfg.backlog_statuses:
            unmapped.append((path.name, raw.strip()))

    verb = 'would normalise' if args.dry_run else 'normalised'
    print(f'normalize-backlog-status: {verb} {sum(changed.values())} of {len(entries)} entries')
    for status, count in sorted(changed.items()):
        print(f'  {count:3}  -> {status}')
    if unmapped:
        print(f'ERROR: {len(unmapped)} value(s) not in the migration table:', file=sys.stderr)
        for name, raw in unmapped:
            print(f'  {name}: {raw!r}', file=sys.stderr)
        return 1

    print('normalize-backlog-status: OK — every backlog status is in the backlog vocabulary')
    return 0


if __name__ == '__main__':
    sys.exit(main())
#!/usr/bin/env python3
"""check_adr_status.py — enforce the ADR status policy from docs/doc-maintenance.md.

doc-maintenance.md rule 2 ("no orphaned `superseded-by`") and rule 3 ("no `open` ADRs
older than 30 days") were prose rules that nothing checked. On 2026-10-05 the corpus
turned out to hold 24 ADRs older than 30 days that were still `open` or `deferred`,
6 with an out-of-vocabulary status, and 2 marked `superseded` with no `superseded-by`.

Checks:
  1. `status:` is present and is one of the documented vocabulary values.
  2. `superseded` implies a `superseded-by` whose target exists (or is in archive/).
  3. `superseded-by` never points at a non-existent ADR.
  4. No `open` ADR older than 30 days (the rule that was unenforced).
  5. Files under `archive/` carry `status: archived`.

Usage:
  python3 scripts/check_adr_status.py
  python3 scripts/check_adr_status.py --stale-days N   # default 30
"""

from __future__ import annotations

import argparse
import datetime as dt
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DECISIONS = ROOT / 'docs' / 'decisions'

# The documented vocabulary. `archived` is legal only under docs/decisions/archive/.
VOCABULARY = {'accepted', 'deferred', 'superseded', 'open'}
ARCHIVE_STATUS = 'archived'


def frontmatter(text: str) -> str | None:
    lines = text.splitlines()
    if not lines or lines[0].strip() != '---':
        return None
    end = next((i for i, l in enumerate(lines[1:], 1) if l.strip() == '---'), None)
    return None if end is None else '\n'.join(lines[1:end])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--stale-days', type=int, default=30)
    args = parser.parse_args()

    today = dt.date.today()
    dated = sorted(DECISIONS.glob('[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]-*.md'))
    archived = sorted((DECISIONS / 'archive').glob('*.md')) if (DECISIONS / 'archive').is_dir() else []

    # Every slug that exists, wherever it lives. A superseded target in archive/ is
    # still a resolvable reference: moving is not deleting.
    slugs = {p.stem for p in dated + archived}

    errors: list[str] = []

    def check(path: pathlib.Path, *, in_archive: bool) -> None:
        rel = path.relative_to(ROOT)
        fm = frontmatter(path.read_text())
        if fm is None:
            errors.append(f'{rel}: no parseable frontmatter block')
            return

        status = re.search(r'(?m)^status:\s*(\S+)', fm)
        if not status:
            errors.append(f'{rel}: missing `status:` (rule 4)')
            return
        value = status.group(1)

        if in_archive:
            if value != ARCHIVE_STATUS:
                errors.append(f'{rel}: archived file must carry `status: {ARCHIVE_STATUS}`, '
                              f'found `{value}`')
            return

        if value not in VOCABULARY:
            errors.append(f'{rel}: status `{value}` is not in the documented vocabulary '
                          f'({", ".join(sorted(VOCABULARY))})')
            return

        # Accept both spellings in the corpus, but a superseded ADR must name a target
        # that resolves.
        target = re.search(r'(?m)^superseded[_-]by:\s*(\S+)', fm)
        if value == 'superseded' and not target:
            errors.append(f'{rel}: status `superseded` requires `superseded-by` (rule 2)')
        if target:
            slug = target.group(1).removesuffix('.md')
            if slug not in slugs:
                errors.append(f'{rel}: `superseded-by` points at {slug}, which does not '
                              'exist in docs/decisions/ or its archive/ (rule 2)')

        if value == 'open':
            m = re.match(r'^(\d{4}-\d{2}-\d{2})-', path.stem)
            if m:
                try:
                    written = dt.date.fromisoformat(m.group(1))
                except ValueError:
                    written = None
                if written and (today - written).days > args.stale_days:
                    errors.append(
                        f'{rel}: `open` for {(today - written).days} days — resolve to '
                        f'`accepted` or move to `deferred` with a revisit trigger (rule 3)')

    for path in dated:
        check(path, in_archive=False)
    for path in archived:
        check(path, in_archive=True)

    for err in errors:
        print(f'ERROR: {err}')

    if errors:
        print('')
        print(f'check_adr_status.py: {len(errors)} error(s) in '
              f'{len(dated)} decision + {len(archived)} archived ADR(s)')
        sys.exit(1)

    print(f'check_adr_status.py: OK — {len(dated)} decision ADR(s), {len(archived)} '
          f'archived, all statuses in vocabulary, no orphaned superseded-by, '
          f'no `open` older than {args.stale_days} days')


if __name__ == '__main__':
    main()

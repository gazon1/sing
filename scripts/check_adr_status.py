#!/usr/bin/env python3
"""check_adr_status.py — catch ADR *drift*, not re-check what the writer enforces.

This gate used to carry seven rules. Four of them are properties of a single file at
the moment it is written, and the writer (`AdrStorage.writeAdr` / `updateAdr`) is now
the only place that can be trusted to produce them. Duplicating them here was two
implementations of one rule, which is how the vocabulary drifted in the first place:
this file accepted five spellings because it had never seen `deferred/`, while the
writer had. See `2026-10-10-adr-file-local-rules-live-in-the-writer`.

So the split is now by what the writer can know:

  writer    vocabulary · superseded-by presence · superseded-by resolves ·
            archived is refused · frontmatter is written
  this gate everything that happened *outside* any writer: a hand edit, a merge,
            a deletion, a bulk script, a status that aged past its threshold.

The rules kept here (#523):
  1. staleness    — no `open` decision older than N days
  2. location     — `archive/` carries `archived`, `deferred/` carries a backlog status
  3. drift        — frontmatter, status presence, vocabulary, and link targets still hold

Vocabulary is checked *per location*, which is the part that was blind before #516:
a backlog entry is held to the backlog vocabulary, a decision to the decision one.

Usage:
    python3 scripts/check_adr_status.py
    python3 scripts/check_adr_status.py --stale-days N   # default from config
"""

from __future__ import annotations

import argparse
import datetime as dt
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import adr_corpus  # noqa: E402  — single owner of corpus discovery (#520)
from adr_corpus import read_review_by  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
CORPUS = adr_corpus.load(ROOT)

STATUS_RE = re.compile(r'(?m)^status:\s*(\S+)')
SUPERSEDED_BY_RE = re.compile(r'(?m)^superseded[_-]by:\s*(\S+)')
SUPERSEDES_RE = re.compile(r'(?m)^supersedes:\s*(\S+)')
DATED_STEM_RE = re.compile(r'^(\d{4}-\d{2}-\d{2})-')


def written_on(path: pathlib.Path) -> dt.date:
    """The decision's date, taken from its filename.

    The filename, not the frontmatter: a decision is dated when it was decided, and
    `title:`/`date:` are hand-editable while the name is what every reference resolves
    against.
    """
    m = DATED_STEM_RE.match(path.stem)
    if not m:
        return dt.date.fromisoformat(CORPUS.stale_days and '2000-01-01')
    try:
        return dt.date.fromisoformat(m.group(1))
    except ValueError:
        return dt.date(2000, 1, 1)


def check(path: pathlib.Path, *, location: str, slugs: set[str], stale_days: int, today: dt.date) -> list[str]:
    """Return the drift found in one file. Empty list means clean.

    Everything this function reads from the outside is a parameter. That is not
    style: the first two revisions of this rewrite reached into `main()`'s locals
    and died with `NameError: name 'slugs' is not defined` / `name 'args' is not
    defined` — on paths that only execute when a file is actually wrong. A gate that
    crashes instead of checking is the exact failure #528 and #529 exist to make
    impossible to ship silently.
    """
    rel = path.relative_to(ROOT)
    text = path.read_text(encoding='utf-8', errors='replace')
    fm = adr_corpus.frontmatter(text)
    if fm is None:
        return [f'{rel}: no parseable frontmatter block']

    raw = STATUS_RE.search(fm)
    if not raw:
        return [f'{rel}: missing `status:`']
    value = CORPUS.normalize_status(raw.group(1))

    # ── rule 2: location. Which vocabulary applies is decided by where the file is.
    if location == 'archived':
        if value != CORPUS.archived_status:
            return [f'{rel}: archived file must carry `status: {CORPUS.archived_status}`, '
                    f'found `{raw.group(1)}`']
        return []

    if location == 'backlog':
        if not CORPUS.is_valid_backlog_status(value):
            return [f'{rel}: backlog status `{raw.group(1)}` is not in the backlog '
                    f'vocabulary ({", ".join(sorted(CORPUS.backlog_statuses))})']
        return []

    # ── rule 3: drift on a live decision.
    errors: list[str] = []
    if not CORPUS.is_valid_status(value):
        return [f'{rel}: status `{raw.group(1)}` is not in the documented vocabulary '
                f'({", ".join(sorted(CORPUS.statuses))})']

    target = SUPERSEDED_BY_RE.search(fm)
    if value == 'superseded' and not target:
        errors.append(f'{rel}: status `superseded` requires `superseded-by`')
    if target:
        slug = target.group(1).strip('[]"\'').removesuffix('.md')
        if slug not in slugs:
            errors.append(f'{rel}: `superseded-by` points at {slug}, which does not '
                          f'exist in {CORPUS.root}/ or its {CORPUS.archive_dir}/')

    # `supersedes` is the forward edge and nothing else validated it (#517). It is the
    # one the digest acts on: refresh-decisions-digest.py drops the Consequences
    # bullets of any slug named here, so a typo does not merely fail to link — it
    # silently removes a real decision's guidance from the generated summary.
    newer = SUPERSEDES_RE.search(fm)
    if newer:
        older = newer.group(1).strip('[]"\'').removesuffix('.md')
        if older not in slugs:
            errors.append(f'{rel}: `supersedes` points at {older}, which does not exist — '
                          f"the digest drops the target's Consequences bullets when it "
                          f'cannot resolve this')
        elif older == path.stem:
            errors.append(f'{rel}: `supersedes` points at itself')

    # ── rule 1: staleness. Only decisions are dated, so only decisions can age.
    #
    # A decision that says when it will be revisited is judged against that date; one
    # that says nothing falls back to the 30-day default (#527). The default was never
    # anybody's decision — it was a number written into a gate — so it is the fallback
    # and not the law.
    if value == 'open':
        due = read_review_by(text, CORPUS)
        threshold = due or (written_on(path) + dt.timedelta(days=stale_days)).isoformat()
        try:
            due_date = dt.date.fromisoformat(threshold)
        except ValueError:
            errors.append(f'{rel}: `{CORPUS.review_by_field}` must be an ISO date, '
                          f'found {threshold!r}')
            return errors
        if today > due_date:
            overdue = (today - due_date).days
            why = (f'`{CORPUS.review_by_field}` was {due}' if due
                   else f'written {written_on(path)} and never given a {CORPUS.review_by_field}')
            errors.append(f'{rel}: `open` and past due by {overdue} day(s) ({why}) — '
                          f'resolve it, or set `{CORPUS.review_by_field}` to the date you '
                          f'will revisit it')
    return errors


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--stale-days', type=int, default=CORPUS.stale_days)
    args = parser.parse_args()

    today = dt.date.today()
    dated = CORPUS.decisions()
    archived = CORPUS.archived()
    backlog = CORPUS.deferred()

    # Every slug that exists, wherever it lives. A superseded target in archive/ is
    # still resolvable: moving is not deleting.
    slugs = CORPUS.all_slugs()

    errors: list[str] = []
    for path in dated:
        errors += check(path, location='decision', slugs=slugs,
                         stale_days=args.stale_days, today=today)
    for path in archived:
        errors += check(path, location='archived', slugs=slugs,
                         stale_days=args.stale_days, today=today)
    for path in backlog:
        errors += check(path, location='backlog', slugs=slugs,
                       stale_days=args.stale_days, today=today)

    for err in errors:
        print(f'ERROR: {err}')

    if errors:
        print('')
        print(f'check_adr_status.py: {len(errors)} error(s) in {len(dated)} decision + '
              f'{len(archived)} archived + {len(backlog)} backlog entries')
        sys.exit(1)

    print(f'check_adr_status.py: OK — {len(dated)} decision + {len(archived)} archived + '
          f'{len(backlog)} backlog entries; status vocabulary holds in all three, '
          f'no orphaned superseded-by, no `open` older than {args.stale_days} days')


if __name__ == '__main__':
    main()
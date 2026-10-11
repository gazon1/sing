#!/usr/bin/env python3
"""check-adr-immutable.py — a closed decision's body must stop changing (#528).

The failure
-----------
Nothing stopped someone reopening an `accepted` ADR and rewriting its
`## Consequences`. The gate checked the *frontmatter* — status, links, age — and
the frontmatter kept being perfectly valid, because editing the body does not
change a single field it looks at. The decision trail silently changed.

The rule
--------
Once a decision is no longer `open`, its body is a record of what was decided and
why. Changing it is not an edit, it is a rewrite of history. If a decision should
say something new, that is what a superseding ADR is for — which is exactly what
`supersedes` exists for, and why the two are the same rule seen from both ends.

The mechanism
-------------
From `Kaikei-e/DocDag`, which calls this `immutable_violation --immutable-since`:
find the commit where the status *became* closed, then ask whether anything after
it touched the body.

    immutable-since = the commit at which status first left `open`
    violation       = any later commit that changed the file's body

There is deliberately no stored hash. Git already holds the answer, and a stored
hash is a second source of truth that goes stale the moment somebody edits the
baseline file. Same reasoning as the corpus rules (#520).

What it deliberately does not do
--------------------------------
It does not police the frontmatter, and it does not require an ADR to be closed.
An `open` decision is still being worked on; its body is expected to grow. Only
closing it starts the clock.

Usage:
    python3 scripts/check-adr-immutable.py
    python3 scripts/check-adr-immutable.py --since 2026-10-01   # only recent closures
    python3 scripts/check-adr-immutable.py --max-age-days 90     # ignore old closures
"""

from __future__ import annotations

import argparse
import datetime as dt
import pathlib
import re
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import adr_corpus  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
CORPUS = adr_corpus.load(ROOT)

STATUS_RE = re.compile(r'(?m)^status:\s*(\S+)')
FENCE_RE = re.compile(r'\A---\n(.*?)\n---\n?', re.DOTALL)


def git(*args: str) -> str:
    return subprocess.run(
        ['git', *args], cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout


def status_at(rev: str, rel: str) -> str | None:
    """Status carried by `rel` at `rev`, or None if the file did not exist yet.

    Takes the repo-relative path throughout. Passing a `Path` and re-deriving it here
    is how the first version of this file crashed with `ValueError: is not in the
    subpath of` — the caller had already done the conversion.
    """
    try:
        text = git('show', f'{rev}:{rel}')
    except subprocess.CalledProcessError:
        return None
    match = adr_corpus.frontmatter(text)
    if match is None:
        return None
    raw = STATUS_RE.search(match)
    return CORPUS.normalize_status(raw.group(1)) if raw else None


def body_at(rev: str, rel: str) -> str | None:
    """The body of `rel` at `rev`: everything after the frontmatter fence.

    Whitespace is normalised before comparison, and the reason is arithmetic rather
    than taste. Comparing raw bytes reported 105 of 556 closed decisions as rewritten,
    and inspecting them showed one of the culprits was a *single trailing newline* —
    added by the bulk frontmatter normalisation in 3a470d929. A gate that fires on
    that is a gate people learn to re-run with `--since`, which is how a gate stops
    being read.

    Trailing whitespace is not part of what a decision says. Interior indentation is
    left alone: changing that changes the code blocks a decision pins down.
    """
    try:
        text = git('show', f'{rev}:{rel}')
    except subprocess.CalledProcessError:
        return None
    return normalise_body(text)


def normalise_body(text: str) -> str:
    """Everything after the frontmatter fence, with trailing whitespace removed.

    Split out of [body_at] so the working-tree comparison and the history comparison
    are the same function. Two definitions of "the body" would make one of them
    quietly wrong.
    """
    match = FENCE_RE.match(text)
    body = text[match.end():] if match else text
    return '\n'.join(line.rstrip() for line in body.splitlines()).strip()


def immutable_since(rel: str) -> str | None:
    """The commit at which this file's status first left `open`."""
    commits = git('log', '--format=%H', '--follow', '--', rel).split()
    # Newest first. The closure commit is the newest one whose parent was still open.
    for rev in commits:
        here = status_at(rev, rel)
        if here is None or here == 'open':
            continue
        parents = git('rev-list', '--parents', '-n', '1', rev).split()[1:]
        if not parents:
            return rev
        before = status_at(parents[0], rel)
        if before != here:
            return rev  # status changed *at* this commit — this is the closure
    return None


def body_changed_after(closing_rev: str, rel: str) -> list[str]:
    """Commits after the closure that changed the file's body."""
    baseline = body_at(closing_rev, rel)
    offenders: list[str] = []
    for rev in git('rev-list', f'{closing_rev}..HEAD', '--', rel).split():
        body = body_at(rev, rel)
        if body is not None and baseline is not None and body != baseline:
            when = git('show', '-s', '--format=%cs', rev).strip()
            offenders.append(f'{rev[:9]} ({when})')
    return offenders


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--since',
                        help='override the corpus floor (config: immutableSince.date)')
    parser.add_argument('--max-age-days', type=int,
                        help='ignore closures older than this many days')
    args = parser.parse_args()

    decisions = CORPUS.decisions()
    if not decisions:
        sys.exit('check-adr-immutable.py: no decisions found — is the corpus root right?')

    errors: list[str] = []
    checked = 0
    floor = args.since or CORPUS.immutable_since
    since_date = dt.date.fromisoformat(floor) if floor else None
    today = dt.date.today()

    for path in decisions:
        rel = path.relative_to(ROOT).as_posix()
        head_status = status_at('HEAD', rel)
        if head_status in (None, 'open'):
            continue  # still being worked on; no clock has started

        closing = immutable_since(rel)
        if closing is None:
            continue  # closed from the moment it was created

        when = dt.date.fromisoformat(git('show', '-s', '--format=%cs', closing).strip())
        if since_date and when < since_date:
            continue
        if args.max_age_days is not None and (today - when).days > args.max_age_days:
            continue

        checked += 1

        # The working tree, not just history. A git-based rule is blind to an edit
        # that has not been committed yet, which is exactly the edit a pre-commit
        # hook should catch — and which made this gate impossible to give a
        # positive control for, since the control harness edits files and does not
        # write commits.
        head_body = body_at('HEAD', rel)
        work_body = normalise_body(path.read_text(encoding='utf-8'))
        if head_body is not None and work_body != head_body:
            errors.append(
                f'{rel}: the working tree body differs from HEAD for a decision closed '
                f'as `{head_status}` in {when}. Commit it only if it is frontmatter-only; '
                f'a closed decision\'s body is superseded, not edited.'
            )

        offenders = body_changed_after(closing, rel)
        if offenders:
            errors.append(
                f'{rel}: closed as `{head_status}` in {when} ({closing[:9]}), but the body '
                f'was rewritten afterwards in {len(offenders)} commit(s): '
                f'{", ".join(offenders[:3])}'
                f'{"…" if len(offenders) > 3 else ""}. A closed decision is a record of '
                f'what was decided — supersede it with a new ADR instead.'
            )

    for err in errors:
        print(f'ERROR: {err}')

    print('')
    print(f'check-adr-immutable.py: {checked} closed decision(s) closed since '
          f'{floor or "the beginning"} checked, {len(errors)} body rewrite(s) after closure')
    if errors:
        return 1
    print('check-adr-immutable.py: OK — no closed decision had its body rewritten')
    return 0


if __name__ == '__main__':
    sys.exit(main())
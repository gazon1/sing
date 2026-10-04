#!/usr/bin/env python3
"""refresh-decisions-digest.py — generate DIGEST.md from ADR entries.

Run via: ./scripts/refresh-decisions-digest.sh
Or directly: python3 ./scripts/refresh-decisions-digest.py
"""

import re
import sys
from pathlib import Path

DECISIONS_DIR = Path(__file__).parent.parent / 'docs' / 'decisions'
DIGEST = DECISIONS_DIR / 'DIGEST.md'
MAX_DIGEST_LINES = 1250  # dropped the duplicate slug→tags index (~385 lines) in 2026-10-03
MAX_ITEMS_PER_TAG = 10  # per tag section cap; the digest is an index, the ADR body is one link away
MAX_BULLETS_PER_ADR = 3  # per ADR cap inside the per-tag sections


def main() -> None:
    entries = sorted(DECISIONS_DIR.glob('[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]-*.md'))
    if not entries:
        print(f"no dated entries in {DECISIONS_DIR} — digest untouched")
        return

    titles: dict[str, str] = {}
    tags_raw: dict[str, str] = {}
    statuses: dict[str, str] = {}
    superseded: dict[str, str] = {}

    for path in entries:
        slug = path.stem
        fm: dict[str, str] = {}
        in_fm = False
        for line in path.read_text().splitlines():
            if line.strip() == '---':
                in_fm = not in_fm
                if not in_fm:
                    break
            if in_fm and ':' in line:
                key, _, val = line.partition(':')
                fm[key.strip()] = val.strip().strip('"').strip("'")
        titles[slug] = fm.get('title', '')
        tags_raw[slug] = fm.get('tags', '')
        statuses[slug] = fm.get('status', '')
        if fm.get('supersedes'):
            superseded[fm['supersedes']] = slug

    CRITICAL_RE = re.compile(r'\*\*Always\*\*|\*\*Never\*\*|\*\*MUST\*\*')
    CRIT_SUFFIX_RE = re.compile(r'\s*_\(from\s+`([^`]+)`\)_\s*$')

    # Build corpus: dedupe on (bullet_lower, slug)
    by_slug_tag: dict[str, list[tuple[str, str]]] = {}
    slug_for_bullet: dict[str, str] = {}

    for path in entries:
        slug = path.stem
        if slug in superseded:
            continue
        text = path.read_text()
        in_consequences = False
        for line in text.splitlines():
            if line.strip() == '## Consequences':
                in_consequences = True
                continue
            if in_consequences and line.startswith('## '):
                in_consequences = False
                continue
            if in_consequences:
                m = re.match(r'^[-*]\s+(.+)', line.strip())
                if m:
                    bullet = m.group(1).strip()
                    if bullet:
                        tag_str = tags_raw.get(slug, '').strip('[]').strip()
                        if tag_str:
                            for tag in tag_str.split(','):
                                tag = tag.strip().strip('"\'').lower()
                                if tag:
                                    by_slug_tag.setdefault(slug, []).append((tag, bullet))
                        else:
                            by_slug_tag.setdefault(slug, []).append(('_untagged_', bullet))
                    slug_for_bullet[bullet.lower()] = slug

    seen_bullet_slug: set[tuple[str, str]] = set()
    corpus: list[tuple[str, str, str]] = []
    for slug, tag_bullets in by_slug_tag.items():
        for tag, bullet in tag_bullets:
            key = (bullet.lower(), slug)
            if key not in seen_bullet_slug:
                seen_bullet_slug.add(key)
                corpus.append((tag, bullet, slug))

    corpus.sort()
    critical = [(b, slug_for_bullet[b.lower()]) for (t, b, s) in corpus if CRITICAL_RE.search(b)]

    # Cap each ADR's contribution to the per-tag sections. A verbose ADR that
    # lists fifteen consequences used to fill every tag section it was tagged
    # with, so the digest grew with the wordiest author rather than with the
    # number of decisions. The Critical section is exempt: **Always** / **Never**
    # rules are exactly what a reader came for, and they are already a short,
    # hand-curated list.
    per_slug_kept: dict[str, int] = {}
    dropped_per_slug: dict[str, int] = {}
    per_tag: dict[str, list[tuple[str, str]]] = {}
    for (t, b, s) in corpus:
        if CRITICAL_RE.search(b):
            continue
        seen = per_slug_kept.get(s, 0)
        if seen >= MAX_BULLETS_PER_ADR:
            dropped_per_slug[s] = dropped_per_slug.get(s, 0) + 1
            continue
        per_slug_kept[s] = seen + 1
        per_tag.setdefault(t, []).append((b, s))

    open_deferred = {
        s: (statuses[s], titles[s])
        for s in statuses
        if statuses[s] in ('open', 'deferred')
    }

    recent_superseded: list[tuple[str, str]] = []
    for path in reversed(entries):
        slug = path.stem
        if slug in superseded:
            recent_superseded.append((slug, titles.get(slug, '')))
            if len(recent_superseded) >= 5:
                break

    def fmt_bullet(bullet: str, slug: str) -> str:
        """Append slug citation unless bullet already ends with one for the same slug."""
        existing = CRIT_SUFFIX_RE.search(bullet)
        if existing and existing.group(1) == slug:
            return bullet
        return f"{bullet} _(from `{slug}`)_"

    out: list[str] = []
    out.append("# Decision Log Digest")
    out.append("")
    out.append("Auto-generated from `docs/decisions/`. Run `./scripts/refresh-decisions-digest.sh` to rebuild.")
    out.append("")
    out.append("## Critical")
    out.append("")

    if critical:
        seen_b: set[str] = set()
        for b, s in critical:
            if b.lower() not in seen_b:
                seen_b.add(b.lower())
                out.append(f"- {fmt_bullet(b, s)}")
    else:
        out.append("_No critical markers. Add **Always**, **Never**, or **MUST** to Consequences._")

    out.append("")
    out.append("## Per-tag")
    out.append("")

    for tag in sorted(per_tag.keys()):
        out.append(f"### `{tag}`")
        out.append("")
        seen: set[tuple[str, str]] = set()
        items = sorted(per_tag[tag])
        shown = 0
        marker_done: set[str] = set()
        for b, s in items:
            key = (b.lower(), s)
            if key in seen:
                continue
            seen.add(key)
            if shown >= MAX_ITEMS_PER_TAG:
                continue
            extra = dropped_per_slug.get(s, 0)
            if extra and s not in marker_done:
                marker_done.add(s)
                out.append(f"- {b} _(+{extra} more in `{s}`)_")
            else:
                out.append(f"- {b}")
            shown += 1
        total = len(seen)
        if total > MAX_ITEMS_PER_TAG:
            out.append(f"- _... and {total - MAX_ITEMS_PER_TAG} more items_")
        out.append("")

    out.append("## Open / Deferred")
    out.append("")
    if open_deferred:
        out.append(f"_{len(open_deferred)} entries need attention._")
        out.append("")
        for slug in sorted(open_deferred.keys()):
            st, ti = open_deferred[slug]
            out.append(f"- `{slug}` — **{st}** — {ti}")
    else:
        out.append("_No open or deferred entries._")

    out.append("")
    if recent_superseded:
        out.append("## Recently superseded")
        out.append("")
        for slug, ti in recent_superseded:
            out.append(f"- `{slug}` — {ti}")
        out.append("")

    out.append("## Active entries")
    out.append("")
    # One index, not two. A former "Index (slug -> tags)" section listed every slug
    # alongside its tags while this one lists the same slugs alongside their titles —
    # ~385 duplicated lines that pushed the digest past its budget on every new ADR.
    # Tags are already reachable through the per-tag sections above, and a title is the
    # more useful half of an index.
    for path in entries:
        slug = path.stem
        if slug not in superseded:
            out.append(f"- `{slug}` — {titles.get(slug, '') or '_(no title)_'}")
    out.append("")

    DIGEST.write_text('\n'.join(out) + '\n')
    total = len(out)
    print(f"refreshed {DIGEST} ({total} lines, {len(entries)} entries)")
    if total > MAX_DIGEST_LINES:
        print(f"WARNING: DIGEST is {total} lines (limit: {MAX_DIGEST_LINES})", file=sys.stderr)


if __name__ == '__main__':
    main()

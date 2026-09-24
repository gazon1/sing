#!/usr/bin/env python3
"""refresh-decisions-digest.py — generate DIGEST.md from ADR entries.

Run via: ./scripts/refresh-decisions-digest.sh
Or directly: python3 ./scripts/refresh-decisions-digest.py

This script writes ONLY between <!-- AUTO-GENERATED-START --> and
<!-- AUTO-GENERATED-END --> markers in DIGEST.md. Content outside those
markers (e.g. hand-written narrative explanations) is preserved.
"""

import re
import sys
from pathlib import Path

DECISIONS_DIR = Path(__file__).parent.parent / 'docs' / 'decisions'
DIGEST = DECISIONS_DIR / 'DIGEST.md'
MAX_DIGEST_LINES = 2000
MARKER_START = "<!-- AUTO-GENERATED-START -->"
MARKER_END = "<!-- AUTO-GENERATED-END -->"


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
    per_tag: dict[str, list[tuple[str, str]]] = {}
    for (t, b, s) in corpus:
        if not CRITICAL_RE.search(b):
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

    generated: list[str] = []
    generated.append("")
    generated.append("## Critical")
    generated.append("")

    if critical:
        seen_b: set[str] = set()
        for b, s in critical:
            if b.lower() not in seen_b:
                seen_b.add(b.lower())
                generated.append(f"- {fmt_bullet(b, s)}")
    else:
        generated.append("_No critical markers. Add **Always**, **Never**, or **MUST** to Consequences._")

    generated.append("")
    generated.append("## Per-tag")
    generated.append("")

    for tag in sorted(per_tag.keys()):
        generated.append(f"### `{tag}`")
        generated.append("")
        seen: set[tuple[str, str]] = set()
        for b, s in sorted(per_tag[tag]):
            key = (b.lower(), s)
            if key not in seen:
                seen.add(key)
                generated.append(f"- {b}")
        generated.append("")

    generated.append("## Open / Deferred")
    generated.append("")
    if open_deferred:
        generated.append(f"_{len(open_deferred)} entries need attention._")
        generated.append("")
        for slug in sorted(open_deferred.keys()):
            st, ti = open_deferred[slug]
            generated.append(f"- `{slug}` — **{st}** — {ti}")
    else:
        generated.append("_No open or deferred entries._")

    generated.append("")
    if recent_superseded:
        generated.append("## Recently superseded")
        generated.append("")
        for slug, ti in recent_superseded:
            generated.append(f"- `{slug}` — {ti}")
        generated.append("")

    generated.append("## Index (slug -> tags)")
    generated.append("")
    active = [s for s in titles if s not in superseded]
    for slug in sorted(active):
        tag_str = tags_raw.get(slug, '').strip('[]')
        generated.append(f"- `{slug}` — {tag_str or '_untagged_'}")
    generated.append("")
    generated.append("## Active entries")
    generated.append("")
    for path in entries:
        slug = path.stem
        if slug not in superseded:
            generated.append(f"- `{slug}` — {titles.get(slug, '') or '_(no title)_'}")
    generated.append("")

    # Read existing DIGEST and preserve content outside markers
    preamble: list[str] = []
    postamble: list[str] = []

    if DIGEST.exists():
        text = DIGEST.read_text()
        marker_s_idx = text.find(MARKER_START)
        marker_e_idx = text.find(MARKER_END)

        if marker_s_idx != -1 and marker_e_idx != -1:
            # Preserve everything before MARKER_START and after MARKER_END
            preamble = text[:marker_s_idx].rstrip('\n').splitlines()
            postamble_lines = text[marker_e_idx + len(MARKER_END):].lstrip('\n').splitlines()
            # Find the last blank-line-separated block to keep (narratives)
            postamble = postamble_lines
        else:
            # No markers found — treat entire file as preamble (first-run migration)
            preamble = text.rstrip('\n').splitlines()
            # Find where the last "## " section starts (## Active entries is last)
            last_section_line = -1
            for i, line in enumerate(preamble):
                if re.match(r'^## ', line):
                    last_section_line = i
            if last_section_line >= 0:
                preamble = preamble[:last_section_line]

    # Build final output
    out: list[str] = []
    out.extend(preamble)
    out.append(MARKER_START)
    out.extend(generated)
    out.append(MARKER_END)
    if postamble:
        if out and out[-1] != '':
            out.append('')
        out.extend(postamble)

    DIGEST.write_text('\n'.join(out) + '\n')
    total = len(out)
    print(f"refreshed {DIGEST} ({total} lines, {len(entries)} entries)")
    if total > MAX_DIGEST_LINES:
        print(f"WARNING: DIGEST is {total} lines (limit: {MAX_DIGEST_LINES})", file=sys.stderr)


if __name__ == '__main__':
    main()

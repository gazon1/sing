#!/usr/bin/env python3
"""normalize-adr-frontmatter.py — normalize YAML frontmatter in all ADR files.

Operations:
  - Add `status: accepted` if status is missing
  - Rename `created:` key to `date:`
  - Strip quotes from tag values (both " and ')
  - Lowercase tag values
  - Backfill `date:` from the filename when missing
  - Backfill `title:` from the first H1 heading, or from the filename slug
  - Preserve all other frontmatter keys

Usage:
  ./scripts/normalize-adr-frontmatter.sh          # in-place, dry-run first
  ./scripts/normalize-adr-frontmatter.sh --apply  # actually write changes
"""

import re
import sys
from pathlib import Path

DECISIONS_DIR = Path(__file__).parent.parent / 'docs' / 'decisions'

# Frontmatter key order — `title` first so the digest and listings read naturally.
KEY_ORDER = ['title', 'date', 'status', 'tags', 'deciders', 'supersedes',
             'superseded-by', 'epic', 'deciders']

DATE_RE = re.compile(r'^(\d{4}-\d{2}-\d{2})-')
H1_RE = re.compile(r'^#\s+(.+?)\s*$', re.M)


def slug_to_title(slug: str) -> str:
    """`2026-09-27-some-slug` -> `Some slug`."""
    stem = DATE_RE.sub('', slug)
    return stem.replace('-', ' ').strip().capitalize()


def parse_frontmatter(lines: list[str]) -> tuple[dict[str, str], list[str], int | None]:
    """Returns (fm_dict, body_lines, dash_close_line_index)."""
    fm: dict[str, str] = {}
    body_start: int | None = None
    in_fm = False
    fm_lines: list[str] = []

    for i, line in enumerate(lines):
        if line.strip() == '---':
            if not in_fm:
                in_fm = True
                continue
            else:
                body_start = i + 1
                break
        if in_fm:
            if ':' in line:
                key, _, val = line.partition(':')
                fm[key.strip()] = val.strip()
                fm_lines.append(line)

    body = lines[body_start:] if body_start is not None else lines
    return fm, body, body_start


def normalize_frontmatter(fm: dict[str, str], body_text: str = '', path: Path | None = None) -> dict[str, str]:
    """Apply normalization rules to frontmatter dict."""
    result = dict(fm)

    # created: -> date:
    if 'created' in result and 'date' not in result:
        result['date'] = result.pop('created')

    # Add status: accepted if missing
    if 'status' not in result:
        result['status'] = 'accepted'

    # Backfill date from the filename
    if 'date' not in result and path is not None:
        m = DATE_RE.match(path.stem)
        if m:
            result['date'] = m.group(1)

    # Backfill title from the first H1, else from the filename slug
    if 'title' not in result and not result.get('title'):
        title = ''
        m = H1_RE.search(body_text)
        if m:
            title = m.group(1).strip()
        elif path is not None:
            title = slug_to_title(path.stem)
        if title:
            result['title'] = title

    # Normalize tags: strip quotes, lowercase, deduplicate
    if 'tags' in result:
        raw = result['tags'].strip()
        # Remove surrounding [] if present
        raw = raw.strip('[]')
        tags = []
        for t in raw.split(','):
            t = t.strip().strip('"\'').lower()
            if t and t not in tags:
                tags.append(t)
        result['tags'] = '[' + ', '.join(tags) + ']'

    return result


def format_frontmatter(fm: dict[str, str]) -> list[str]:
    """Format frontmatter dict as YAML lines, in a stable key order."""
    lines = ['---']
    seen: set[str] = set()
    for key in KEY_ORDER:
        if key in fm:
            lines.append(f'{key}: {fm[key]}')
            seen.add(key)
    for key, val in fm.items():
        if key not in seen:
            lines.append(f'{key}: {val}')
    lines.append('---')
    return lines


def process_file(path: Path, dry_run: bool = True) -> tuple[bool, str]:
    """Process one ADR file. Returns (changed, message)."""
    original = path.read_text()
    lines = original.splitlines()

    fm, body, body_start = parse_frontmatter(lines)
    original_fm = dict(fm)
    fm = normalize_frontmatter(fm, body_text='\n'.join(body), path=path)

    # Check if anything changed
    if fm == original_fm:
        return False, 'no changes'

    new_lines = format_frontmatter(fm) + body
    new_text = '\n'.join(new_lines) + '\n'

    if not dry_run:
        path.write_text(new_text)

    # Show diff summary
    changes = []
    for k in set(list(original_fm.keys()) + list(fm.keys())):
        old = original_fm.get(k, '<missing>')
        new = fm.get(k, '<missing>')
        if old != new:
            changes.append(f'{k}: {old!r} -> {new!r}')

    return True, '; '.join(changes)


def main() -> None:
    dry_run = '--apply' not in sys.argv

    entries = sorted(DECISIONS_DIR.glob('[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]-*.md'))

    changed = []
    unchanged = 0

    for path in entries:
        did_change, msg = process_file(path, dry_run=dry_run)
        if did_change:
            changed.append((path.stem, msg))
        else:
            unchanged += 1

    if dry_run:
        print(f"[DRY RUN] {len(changed)} files need changes, {unchanged} unchanged")
        print("Run with --apply to write changes:")
        for slug, msg in changed:
            print(f"  {slug}: {msg}")
    else:
        print(f"Applied: {len(changed)} files changed, {unchanged} unchanged")
        for slug, msg in changed:
            print(f"  {slug}: {msg}")

    if changed and dry_run:
        sys.exit(2)  # indicate changes needed


if __name__ == '__main__':
    main()

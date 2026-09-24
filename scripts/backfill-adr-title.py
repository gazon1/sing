#!/usr/bin/env python3
"""backfill-adr-title.py — extract or derive title for ADR files missing it.

For each ADR file in docs/decisions/ that lacks a `title:` field in frontmatter:
  1. Try to extract from first `# Heading` in body (before any ## section)
  2. Fall back to filename slug → title-cased human readable

Usage:
  python3 scripts/backfill-adr-title.py          # dry-run
  python3 scripts/backfill-adr-title.py --apply   # write changes
"""

import re
import sys
from pathlib import Path

DECISIONS = Path(__file__).parent.parent / 'docs' / 'decisions'


def extract_frontmatter(text: str) -> tuple[dict[str, str], list[str], int | None]:
    """Returns (fm_dict, body_lines, dash_close_line_index)."""
    fm: dict[str, str] = {}
    body_start: int | None = None
    in_fm = False
    fm_lines: list[str] = []

    for i, line in enumerate(text.splitlines()):
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

    body = text.splitlines()[body_start:] if body_start is not None else text.splitlines()
    return fm, body, body_start


def has_title(fm: dict[str, str]) -> bool:
    """Case-insensitive check for title key."""
    return any(k.lower() == 'title' for k in fm)


def slug_to_title(slug: str) -> str:
    """Convert filename slug to title-case string."""
    # Remove date prefix YYYY-MM-DD-
    parts = slug.split('-', 3)
    if len(parts) >= 4 and re.match(r'\d{4}-\d{2}-\d{2}', '-'.join(parts[:3])):
        rest = parts[3]
    else:
        rest = '-'.join(parts[1:]) if len(parts) > 1 else parts[-1]
    # Replace hyphens/underscores with spaces, title-case each word
    words = re.sub(r'[-_]+', ' ', rest).split()
    return ' '.join(w.capitalize() for w in words if w)


def extract_heading_from_body(body: list[str]) -> str | None:
    """Find first # Heading in body (before any ## section)."""
    for line in body:
        stripped = line.strip()
        if stripped.startswith('## '):
            # Stop at first section header
            break
        m = re.match(r'^#\s+(.+)$', stripped)
        if m:
            return m.group(1).strip()
    return None


def process_file(path: Path, dry_run: bool = True) -> tuple[bool, str]:
    """Add title to frontmatter if missing. Returns (changed, message)."""
    original = path.read_text()
    fm, body, body_start = extract_frontmatter(original)

    if has_title(fm):
        return False, 'has title'

    # Try to derive title
    title: str | None = None

    # Source 1: first # Heading in body
    heading = extract_heading_from_body(body)
    if heading:
        title = heading

    # Source 2: use slug
    if not title:
        title = slug_to_title(path.stem)

    if not title:
        return False, 'could not derive title'

    # Build new frontmatter
    new_fm_lines = ['---']
    for k, v in fm.items():
        new_fm_lines.append(f'{k}: {v}')
    # Insert title before status if present, else at end
    title_inserted = False
    new_fm_lines_new = []
    for line in new_fm_lines:
        new_fm_lines_new.append(line)
        if not title_inserted and line.strip().startswith('status:'):
            new_fm_lines_new.insert(-1, f'title: "{title}"')
            title_inserted = True
    if not title_inserted:
        new_fm_lines_new.append(f'title: "{title}"')
    new_fm_lines_new.append('---')

    new_lines = new_fm_lines_new + body
    new_text = '\n'.join(new_lines) + '\n'

    if not dry_run:
        path.write_text(new_text)

    return True, f'added title: "{title}"'


def main() -> None:
    dry_run = '--apply' not in sys.argv

    entries = sorted(DECISIONS.glob('[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]-*.md'))

    changed = []
    unchanged = 0

    for path in entries:
        did_change, msg = process_file(path, dry_run=dry_run)
        if did_change:
            changed.append((path.stem, msg))
        else:
            unchanged += 1

    if dry_run:
        print(f"[DRY RUN] {len(changed)} files need title, {unchanged} unchanged")
        print("Run with --apply to write changes:")
        for slug, msg in changed:
            print(f"  {slug}: {msg}")
    else:
        print(f"Applied: {len(changed)} files changed, {unchanged} unchanged")
        for slug, msg in changed:
            print(f"  {slug}: {msg}")

    if changed and dry_run:
        sys.exit(2)


if __name__ == '__main__':
    main()

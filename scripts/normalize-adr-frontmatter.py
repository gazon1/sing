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

# The only keys this script is allowed to carry through. Derived from the keys actually
# present in well-formed ADRs under docs/decisions, not invented. Anything else inside
# the frontmatter block means the block is not frontmatter at all — the usual cause is a
# body that was never closed, so prose containing ':' parses as a key. Emitting those
# keys is how a body gets duplicated into the file, so unknown keys are a hard error
# rather than something to round-trip. See scripts/tests/test_normalize_adr_frontmatter.py.
KNOWN_KEYS = frozenset({
    'title', 'date', 'created', 'updated', 'last_updated', 'status', 'tags', 'Tags',
    'deciders', 'decider', 'authors', 'author', 'reviewedBy', 'owner', 'profile',
    'epic', 'description', 'summary', 'id', 'slug', 'type', 'impact', 'review',
    'supersedes', 'superseded-by', 'superseded_by', 'doesNotSupersede', 'replaces',
    'follows', 'decides', 'adr', 'adr-number', 'end-date', 'labels',
    'issuesRelated', 'related', 'references', 'skills', 'context', 'Context',
    'Decision', 'Rationale', 'Consequences', 'Links', 'deprecated-by',
})

# Capitalized section names used as keys. These are the signature of a body written
# *inside* the frontmatter block: the block parses as valid YAML, so nothing else
# catches it, but the file has no real body and cannot be rendered.
SECTION_KEYS = frozenset({'Context', 'Decision', 'Rationale', 'Consequences', 'Links'})


class StructuralError(Exception):
    """Raised when a file's frontmatter block cannot be trusted. Never write on this."""


class UnrepresentableValue(StructuralError):
    """The key's value cannot survive this script's flat str->str round-trip.

    A YAML list or block scalar would be flattened to an empty string and its items
    dropped on write, so the file is reported and left alone instead.
    """

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
    """Returns (fm_dict, body_lines, dash_close_line_index).

    Raises StructuralError if the block is unterminated or carries a key outside
    KNOWN_KEYS. Both mean the 'frontmatter' is really body text, and re-emitting it
    would duplicate the body and promote prose to metadata.
    """
    if not lines or lines[0].strip() != '---':
        raise StructuralError('file does not start with a frontmatter delimiter')

    fm: dict[str, str] = {}
    body_start: int | None = None
    multiline: set[str] = set()

    for i, line in enumerate(lines[1:], start=1):
        stripped = line.strip()
        if stripped == '---':
            body_start = i + 1
            break
        if not stripped or stripped.startswith('#'):
            continue
        # A key with no inline value opens a list or block scalar. This script models
        # frontmatter as flat str->str, so those cannot be re-emitted without losing
        # their items — record the key and refuse to rewrite the file.
        if line.startswith((' ', '\t', '-')) or stripped in ('|', '>', '|-', '>-'):
            if line.startswith((' ', '\t', '-')) and fm and not multiline:
                multiline.add(next(reversed(fm)))
            continue
        if ':' in stripped:
            key, _, val = stripped.partition(':')
            key = key.strip()
            if key in SECTION_KEYS:
                raise StructuralError(
                    f'section key {key!r} at line {i + 1} — the body is written inside '
                    'the frontmatter block, so the file has no real body'
                )
            if key not in KNOWN_KEYS:
                raise StructuralError(
                    f'unknown frontmatter key {key!r} at line {i + 1} — '
                    'the frontmatter block is probably unterminated body text'
                )
            fm[key] = val.strip()
            if val.strip() in ('|', '>', '|-', '>-'):
                multiline.add(key)

    if body_start is None:
        raise StructuralError('frontmatter block is never closed with ---')

    if multiline:
        raise UnrepresentableValue(
            f'key(s) {sorted(multiline)} have list or block-scalar values, which this '
            'normalizer cannot round-trip without dropping items'
        )

    return fm, lines[body_start:], body_start


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
    """Process one ADR file. Returns (changed, message).

    Raises StructuralError without touching the file if the frontmatter is untrusted.
    """
    original = path.read_text()
    lines = original.splitlines()

    try:
        fm, body, body_start = parse_frontmatter(lines)
    except StructuralError as exc:
        # Preserve the specific subclass so callers/tests can distinguish an
        # untrusted block from a value this normalizer simply cannot round-trip.
        raise type(exc)(f'{path.name}: {exc}') from exc
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
    errors = []
    unrepresentable: list[str] = []

    for path in entries:
        try:
            did_change, msg = process_file(path, dry_run=dry_run)
        except UnrepresentableValue as exc:
            # Valid ADR, but a value this flat str->str model cannot round-trip. The file
            # is left byte-identical and the corpus is fine — this is a limitation of the
            # normalizer, not a defect, so it must not fail the build.
            unrepresentable.append(str(exc))
            unchanged += 1
            continue
        except StructuralError as exc:
            # The frontmatter itself is untrustworthy. That is a real defect.
            errors.append(str(exc))
            continue
        if did_change:
            changed.append((path.stem, msg))
        else:
            unchanged += 1

    label = f'{len(changed)} files need changes' if dry_run else f'{len(changed)} files changed'
    print(f"[{'DRY RUN' if dry_run else 'APPLIED'}] {label}, {unchanged} unchanged, "
          f"{len(errors)} structurally broken, {len(unrepresentable)} not normalizable")
    for err in errors:
        print(f"  SKIPPED (not written): {err}")
    for msg in unrepresentable:
        print(f"  SKIPPED (valid YAML, not normalizable): {msg}")
    if changed and dry_run:
        print("Run with --apply to write changes:")
        for slug, msg in changed:
            print(f"  {slug}: {msg}")
    elif changed:
        for slug, msg in changed:
            print(f"  {slug}: {msg}")

    # A structural error means an ADR is silently unparseable — a real corpus defect.
    # Exit non-zero so CI sees it, but only after writing the files that are safe.
    if errors:
        sys.exit(3)
    if changed and dry_run:
        sys.exit(2)  # indicate changes needed


if __name__ == '__main__':
    main()

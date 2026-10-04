#!/usr/bin/env python3
"""check_skill_frontmatter.py — verify every SKILL.md frontmatter actually parses.

The shell predecessor of this check grepped for `^name:` and `^description:`. That
validates key *presence* but not *structure*, so 15 skills whose description
contained an unquoted ": " passed the gate while being unparseable YAML — the
metadata was silently dropped by every skill loader.

Checks, per file:
  - the file starts with a `---` delimiter and the block is closed
  - the block parses as a YAML mapping
  - `name` and `description` are present and non-empty
  - `name` matches the containing directory name (catches copy/rename drift)

Usage:
  ./scripts/check-skill-frontmatter.sh
  ./scripts/check-skill-frontmatter.sh --fix      # quote descriptions in place
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SKILLS_DIR = ROOT / '.agents' / 'skills'
REQUIRED_KEYS = ('name', 'description')

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit('check_skill_frontmatter.py: PyYAML is required '
             '(pip install pyyaml) — without it this check cannot parse frontmatter')


def split_frontmatter(text: str) -> tuple[str | None, str]:
    """Return (frontmatter_text, body). frontmatter_text is None if there is no block."""
    lines = text.splitlines()
    if not lines or lines[0].strip() != '---':
        return None, text
    for i, line in enumerate(lines[1:], start=1):
        if line.strip() == '---':
            return '\n'.join(lines[1:i]), '\n'.join(lines[i + 1:])
    return None, text  # unterminated


def needs_fix(path: pathlib.Path) -> bool:
    """True only if the file currently fails to parse or lacks a required key.

    Deliberately narrow: a description that already parses is correct YAML whether or
    not it is quoted, so --fix must leave it alone. An earlier version of --fix quoted
    every unquoted description in the repo (107 files) when only 15 were broken.
    """
    fm_text, _ = split_frontmatter(path.read_text())
    if fm_text is None:
        return False  # missing/unterminated block: needs a human, not a quote
    try:
        data = yaml.safe_load(fm_text)
    except yaml.YAMLError:
        return True
    if not isinstance(data, dict):
        return True
    return any(not str(data.get(k) or '').strip() for k in REQUIRED_KEYS)


def quote_descriptions(text: str) -> str | None:
    """Rewrite `description: <value>` as `description: '<value>'` when the value is
    unsafe unquoted. Returns the new text, or None if nothing needed changing.

    Only touches the description line of the frontmatter block; the body is never
    modified. A value that already parses is left exactly as it is.
    """
    lines = text.splitlines()
    if not lines or lines[0].strip() != '---':
        return None
    end = next((i for i, l in enumerate(lines[1:], 1) if l.strip() == '---'), None)
    if end is None:
        return None

    changed = False
    for i in range(1, end):
        m = re.match(r'^description:(.*)$', lines[i])
        if not m:
            continue
        value = m.group(1).strip()
        if not value or value.startswith(('"', "'", '>', '|')):
            continue
        # Single-quote, escaping any embedded single quote by doubling it.
        escaped = value.replace("'", "''")
        lines[i] = f"description: '{escaped}'"
        changed = True
    return '\n'.join(lines) + '\n' if changed else None


def check_file(path: pathlib.Path) -> list[str]:
    text = path.read_text()
    errors: list[str] = []

    fm_text, _ = split_frontmatter(text)
    if fm_text is None:
        return [f'{rel(path)}: no frontmatter block (skill loaders cannot see it)']

    try:
        data = yaml.safe_load(fm_text)
    except yaml.YAMLError as exc:
        first = str(exc).splitlines()[0]
        return [f'{rel(path)}: frontmatter is not valid YAML — {first}']

    if not isinstance(data, dict):
        return [f'{rel(path)}: frontmatter is not a YAML mapping']

    for key in REQUIRED_KEYS:
        if key not in data:
            errors.append(f'{rel(path)}: missing required key {key!r}')
        elif not str(data[key] or '').strip():
            errors.append(f'{rel(path)}: {key!r} is present but empty')

    name = data.get('name')
    if isinstance(name, str) and name.strip() and name.strip() != path.parent.name:
        errors.append(
            f'{rel(path)}: name {name.strip()!r} does not match directory '
            f'{path.parent.name!r}'
        )
    return errors


def rel(path: pathlib.Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--fix', action='store_true',
                        help='quote unquoted description values in place')
    args = parser.parse_args()

    files = sorted(SKILLS_DIR.rglob('SKILL.md'))
    if not files:
        sys.exit(f'check_skill_frontmatter.py: no SKILL.md found under {SKILLS_DIR}')

    fixed = 0
    if args.fix:
        for path in files:
            if not needs_fix(path):
                continue
            new_text = quote_descriptions(path.read_text())
            if new_text is None:
                continue
            # Only accept the rewrite if it now parses and both keys survived.
            fm_text, _ = split_frontmatter(new_text)
            try:
                data = yaml.safe_load(fm_text or '')
            except yaml.YAMLError:
                continue
            if isinstance(data, dict) and all(str(data.get(k) or '').strip() for k in REQUIRED_KEYS):
                path.write_text(new_text)
                fixed += 1
        print(f'check_skill_frontmatter: quoted {fixed} description value(s)')
        if fixed:
            return

    errors: list[str] = []
    for path in files:
        errors.extend(check_file(path))

    if errors:
        for err in errors:
            print(f'ERROR: {err}')
        print('')
        print(f'check_skill_frontmatter.sh: {len(errors)} error(s) in {len(files)} skill file(s)')
        print('Fix: quote the value (e.g. description: "text: with a colon") '
              'or run ./scripts/check-skill-frontmatter.sh --fix')
        sys.exit(1)

    print(f'check_skill_frontmatter.sh: OK — {len(files)} skill file(s) parse as YAML '
          f'with non-empty name and description')


if __name__ == '__main__':
    main()

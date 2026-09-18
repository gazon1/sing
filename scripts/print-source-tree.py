#!/usr/bin/env python3
"""print-source-tree.py — emit core/ and feature/ package trees as markdown.

Usage:
  python3 ./scripts/print-source-tree.py              # writes to /tmp/source-tree.md
  python3 ./scripts/print-source-tree.py --output FILE # writes to FILE
  python3 ./scripts/print-source-tree.py --core       # core only
  python3 ./scripts/print-source-tree.py --feature   # feature only
"""

import re
import sys
from pathlib import Path

SRC = Path(__file__).parent.parent / 'shared' / 'src' / 'commonMain' / 'kotlin' / 'com' / 'singularity' / 'todo'

KDOC_RE = re.compile(r'^\s*/\*\*\s*(.+?)\s*\*/', re.DOTALL)


def extract_kdoc_first_kt(dir_path: Path) -> str:
    """Find first .kt file in dir and extract its leading KDoc comment."""
    kt_files = sorted(dir_path.rglob('*.kt'))
    if not kt_files:
        return ''
    for kt in kt_files:
        text = kt.read_text(encoding='utf-8', errors='replace')
        m = KDOC_RE.search(text)
        if m:
            doc = m.group(1).strip()
            first_line = doc.split('\n')[0].strip()
            first_line = re.sub(r'^\*+\s*', '', first_line)
            if first_line:
                return first_line
    return ''


def generate_core() -> list[str]:
    lines = []
    core_dir = SRC / 'core'
    if not core_dir.exists():
        return ['| _(core/ not found)_ | |']

    lines.append("### `core/` — infrastructure (ports & adapters)")
    lines.append('')
    lines.append('| Package | Purpose |')
    lines.append('|---|---|')

    for pkg_dir in sorted(core_dir.iterdir()):
        if not pkg_dir.is_dir():
            continue
        pkg = f'core/{pkg_dir.name}'
        kdoc = extract_kdoc_first_kt(pkg_dir)
        kdoc_cell = f' {kdoc}' if kdoc else ''
        lines.append(f'| `{pkg}/`|{kdoc_cell}|')
    return lines


def generate_feature() -> list[str]:
    lines = []
    feat_dir = SRC / 'feature'
    if not feat_dir.exists():
        return ['| _(feature/ not found)_ | | | |']

    lines.append("### `feature/` — UI features (verticals)")
    lines.append('')
    lines.append('| Package | Screen | ViewModel | Repository |')
    lines.append('|---|---|---|---|')

    def cell(x: str | None) -> str:
        return f' `{x}` |' if x else ' — |'

    for pkg_dir in sorted(feat_dir.iterdir()):
        if not pkg_dir.is_dir():
            continue
        pkg = f'feature/{pkg_dir.name}'

        vm_files = list((pkg_dir / 'presentation' / 'viewmodel').glob('*ViewModel.kt'))
        vm = vm_files[0].stem if vm_files else None

        repo_files = list((pkg_dir / 'domain' / 'port').glob('*Repository.kt'))
        repo = repo_files[0].stem if repo_files else None

        screen_files = list((pkg_dir / 'presentation' / 'screen').glob('*.kt'))
        screen = screen_files[0].stem if screen_files else None

        lines.append(f"| `{pkg}/` |{cell(screen)}{cell(vm)}{cell(repo)}|")
    return lines


def main() -> None:
    output_file: Path | None = None
    mode = 'all'

    args = sys.argv[1:]
    if '--help' in args or '-h' in args:
        print(__doc__)
        return
    if '--core' in args:
        mode = 'core'
        args.remove('--core')
    elif '--feature' in args:
        mode = 'feature'
        args.remove('--feature')
    if '--output' in args:
        idx = args.index('--output')
        output_file = Path(args[idx + 1])
        args = args[:idx] + args[idx + 2:]

    if not output_file:
        output_file = Path('/tmp/source-tree.md')

    lines: list[str] = []
    if mode in ('core', 'all'):
        lines.extend(generate_core())
    if mode in ('feature', 'all'):
        if mode == 'all' and lines:
            lines.append('')
        lines.extend(generate_feature())

    content = '\n'.join(lines) + '\n'
    output_file.write_text(content, encoding='utf-8')
    print(f"Wrote {len(content)} chars to {output_file}", file=sys.stderr)


if __name__ == '__main__':
    main()

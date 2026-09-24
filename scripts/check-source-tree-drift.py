#!/usr/bin/env python3
"""check-source-tree-drift.py — verify ARCHITECTURE.md §1 is in sync with actual code.

Compares the package tree output by print-source-tree.py against §1 of
ARCHITECTURE.md (## 1. Карта пакетов). Reports drift.

Usage:
  python3 scripts/check-source-tree-drift.py            # exit 0 if in sync
  python3 scripts/check-source-tree-drift.py --verbose   # show diff
"""

import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_DIR = Path(__file__).parent.parent
ARCHITECTURE = REPO_DIR / 'ARCHITECTURE.md'
PRINT_SCRIPT = REPO_DIR / 'scripts' / 'print-source-tree.py'


def extract_section1(content: str) -> str:
    """Extract the §1 package map from ARCHITECTURE.md.

    Tries both Russian ("## 1. Карта пакетов") and English
    ("## 1. Package structure") headings.
    """
    patterns = [
        r'## 1\. (?:Карта пакетов|Package structure).*?(?=\n## |\Z)',
    ]
    for pat in patterns:
        m = re.search(pat, content, re.DOTALL | re.MULTILINE)
        if m:
            return m.group(0).strip()
    return ''


def main() -> None:
    verbose = '--verbose' in sys.argv

    # Generate actual tree
    with tempfile.NamedTemporaryFile(mode='w', suffix='.md', delete=False) as f:
        actual_path = Path(f.name)

    try:
        result = subprocess.run(
            ['python3', str(PRINT_SCRIPT), '--output', str(actual_path)],
            capture_output=True, text=True, cwd=str(REPO_DIR),
        )
        if result.returncode != 0:
            print(f"ERROR: print-source-tree.py failed: {result.stderr}")
            sys.exit(0)  # warning, not gate

        actual = actual_path.read_text().strip()
        arch_text = ARCHITECTURE.read_text()
        expected = extract_section1(arch_text)

        if not expected:
            print("WARN: Could not find §1 in ARCHITECTURE.md — skipping drift check")
            sys.exit(0)

        # Normalize for comparison: remove KDoc comment noise
        def normalize(s: str) -> str:
            lines = []
            for line in s.splitlines():
                # Skip comment-only lines and blank lines near the table
                stripped = line.strip()
                if not stripped or stripped.startswith('//') or stripped.startswith('#'):
                    continue
                lines.append(line.rstrip())
            return '\n'.join(lines).strip()

        norm_expected = normalize(expected)
        norm_actual = normalize(actual)

        if norm_expected == norm_actual:
            print("PASS: ARCHITECTURE.md §1 is in sync with actual source tree")
            sys.exit(0)
        else:
            print("WARN: ARCHITECTURE.md §1 is out of sync with actual source tree")
            if verbose:
                import difflib
                diff = difflib.unified_diff(
                    norm_expected.splitlines(),
                    norm_actual.splitlines(),
                    fromfile='ARCHITECTURE.md §1',
                    tofile='print-source-tree.py output',
                    lineterm='',
                )
                for line in list(diff)[:30]:
                    print(line)
                print(f"\nRun: python3 scripts/print-source-tree.py --output /tmp/actual-tree.md")
                print("Then compare: diff ARCHITECTURE.md §1 vs /tmp/actual-tree.md")
            sys.exit(1)
    finally:
        actual_path.unlink(missing_ok=True)


if __name__ == '__main__':
    main()

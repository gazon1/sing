#!/usr/bin/env python3
"""check-broken-links.sh — verify all markdown links in docs resolve.

Fast Python implementation — processes all files in one pass.

Usage:
  python3 scripts/check-broken-links.sh             # check all docs/*.md
  python3 scripts/check-broken-links.sh --verbose    # show broken links
"""

import re
import sys
from pathlib import Path

DOCS_DIR = Path(__file__).parent.parent / 'docs'
MARKDOWN_FILES = list(DOCS_DIR.glob('**/*.md'))

broken = []
checked = 0

for md_file in MARKDOWN_FILES:
    text = md_file.read_text()
    for lineno, line in enumerate(text.splitlines(), 1):
        # Find markdown links: [text](url)
        for m in re.finditer(r'\[([^\]]*)\]\(([^\)]+)\)', line):
            link = m.group(2)
            # Skip external URLs, anchors, mailto
            if re.match(r'https?://|#|mailto:', link):
                continue
            checked += 1
            # Resolve relative path
            if link.startswith('/'):
                target = DOCS_DIR.parent / link.lstrip('/')
            else:
                target = (md_file.parent / link).resolve()
            # Strip anchor
            target = Path(str(target).rstrip('#'))
            if not target.exists():
                broken.append((str(md_file.relative_to(DOCS_DIR.parent)), lineno, link))

if broken:
    print(f"WARN: {len(broken)} broken links found")
    if '--verbose' in sys.argv:
        for path, lineno, link in broken:
            print(f"  [BROKEN] {path}:{lineno}: {link}")
else:
    print(f"PASS: No broken links found ({checked} links checked)")

sys.exit(0)  # always exit 0 — this is a warning check, not a gate

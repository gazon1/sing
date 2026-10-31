#!/usr/bin/env python3
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

PATTERNS = [
    (re.compile(r'\.size\(\s*(\d+(?:\.\d+)?)\s*\.dp\s*\).*?\.clickable\('), 'size before clickable'),
    (re.compile(r'\.size\(\s*(\d+(?:\.\d+)?)\s*\.dp\s*\).*?\.toggleable\('), 'size before toggleable'),
]

def main():
    bad = []
    for path in ROOT.rglob('*.kt'):
        if '/build/' in str(path) or '/test/' in str(path):
            continue
        text = path.read_text()
        if 'touchTarget' in text or 'minimumInteractiveComponentSize' in text:
            continue
        for pat, msg in PATTERNS:
            for m in pat.finditer(text):
                try:
                    val = float(m.group(1))
                    if val < 48.0:
                        bad.append((str(path.relative_to(ROOT)), msg, val))
                except Exception:
                    pass
    if bad:
        for b in bad:
            print(f'{b[0]}: {b[1]} ({b[2]}dp)')
        sys.exit(1)
    sys.exit(0)

if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Cheap migration-naming checks: partial indexes must be named index_<table>_<cols>."""
import re, sys, pathlib

bad = []
for p in pathlib.Path("shared/src").rglob("*.kt"):
    text = p.read_text()
    for m in re.finditer(r"CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?", text, re.I):
        name = m.group(1)
        if not name.startswith("index_"):
            line = text[:m.start()].count("\n") + 1
            bad.append(f"{p}:{line}: index name '{name}' does not start with index_")
if bad:
    print("\n".join(bad))
    sys.exit(1)
print("OK: migration index names follow index_<table>_<cols>")

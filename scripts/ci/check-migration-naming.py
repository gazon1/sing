#!/usr/bin/env python3
"""Cheap migration-naming checks: partial indexes must be named index_<table>_<cols>.

The scan covers every Kotlin source in the tree, not one module. It used to
read `shared/src`, and the migrations moved to `core-database/` when the
modules were split: the gate kept running, kept finding nothing, and kept
reporting a clean tree. A gate that reads a directory the migrations are not
in is not a gate — it checks nothing and can fail on nothing.

So the scan is over the whole tree, and a scan that finds no index statement
at all is a finding of its own: it means the tree moved again and the gate
did not follow.
"""
import re, sys, pathlib

bad = []
seen = 0
for p in pathlib.Path(".").rglob("*.kt"):
    if "build" in p.parts or ".git" in p.parts:
        continue
    text = p.read_text(errors="replace")
    for m in re.finditer(r"CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?", text, re.I):
        seen += 1
        name = m.group(1)
        if not name.startswith("index_"):
            line = text[:m.start()].count("\n") + 1
            bad.append(f"{p}:{line}: index name '{name}' does not start with index_")
if seen == 0:
    print("no CREATE INDEX statement found in any Kotlin source — the scan does not reach the migrations")
    sys.exit(1)
if bad:
    print("\n".join(bad))
    sys.exit(1)
print(f"OK: {seen} migration index name(s) follow index_<table>_<cols>")

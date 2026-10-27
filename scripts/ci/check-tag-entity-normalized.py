#!/usr/bin/env python3
"""Fail if a TagEntity(...) construction omits nameNormalized."""
import re, sys, pathlib

bad = []
for p in pathlib.Path(".").rglob("*.kt"):
    if "build" in p.parts:
        continue
    text = p.read_text()
    for m in re.finditer(r"\bTagEntity\(", text):
        # walk to matching close paren
        i = m.end() - 1  # at '('
        depth = 0
        start = m.start()
        j = i
        while j < len(text):
            c = text[j]
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        block = text[i:j+1]
        # skip data class definition
        if "data class TagEntity" in text[max(0,start-20):start+20]:
            continue
        if "nameNormalized" not in block:
            line = text[:start].count("\n") + 1
            bad.append(f"{p}:{line}")

if bad:
    print("TagEntity constructions missing nameNormalized:")
    for b in bad:
        print("  " + b)
    sys.exit(1)
print("OK: all TagEntity constructions include nameNormalized")

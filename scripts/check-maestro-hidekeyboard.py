#!/usr/bin/env python3
"""check-maestro-hidekeyboard.py — hideKeyboard must follow an input, not precede one.

In this app, hideKeyboard triggers autosave-on-dismiss and closes the editor.
Placing it before inputText saves an empty draft and navigates away. This gate
catches flows where hideKeyboard appears without a preceding inputText.
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FLOWS = ROOT / "Maestro" / "flows"

errors = []
for f in sorted(FLOWS.rglob("*.yaml")):
    lines = f.read_text().splitlines()
    for i, line in enumerate(lines):
        if "hideKeyboard" not in line or line.strip().startswith("#"):
            continue
        # Look back for the nearest inputText (non-comment, non-blank)
        found_input = False
        for j in range(i - 1, max(i - 20, -1), -1):
            stripped = lines[j].strip()
            if not stripped or stripped.startswith("#"):
                continue
            if "inputText" in stripped:
                found_input = True
                break
        if not found_input:
            errors.append(f"{f.relative_to(ROOT)}:{i+1}: hideKeyboard without preceding inputText")

if errors:
    for e in errors:
        print(e)
    sys.exit(1)
print(f"check-maestro-hidekeyboard: OK ({len(list(FLOWS.rglob('*.yaml')))} flows checked)")
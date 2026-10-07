#!/usr/bin/env python3
"""Find persisted settings that are collected, displayed, and read by no feature.

The defect this catches
-----------------------
A setting can be fully wired and still do nothing. `reminderDefault` is the live
instance: it has a radio group in `NotificationSettingsScreen.kt:71`, a preference
key, a `SettingsStore`, a DI binding and a `Contributor` — and nothing under
`feature/tasks/` ever asks for it. `TaskCreateViewModel`'s KDoc even claims it
"owns the new task draft with ... reminders". The user picks a default, sees it
survive a restart, and no task is ever given it.

The work-schedule group is a larger instance of the same thing: `dayStartMinutes`,
`dayEndMinutes`, `lunchStart/EndMinutes`, `weekendSat/Sun` have a complete settings
screen (day start, lunch, weekends) and **no feature anywhere reads them**. The
store assembles them and the ViewModel passes them to a screen, which is where the
chain ends.

`find-unwired-surfaces.py` cannot see this: its `orphan-binding` check asks whether
a Koin binding has an injector, and this chain has one at every step.

Three revisions, and why each was wrong
--------------------------------------
The first version matched preference-key names and reported 40+ dead settings —
every one a false positive. A key declared in `SettingsRepository` is read there, by
the `Flow` its own repository builds from the same `Preferences` map it writes to. A
tautology, not a consumption.

The second version followed `XSettingsStore` -> `XSettingsContributor` and excluded
the DI layer wholesale, reporting **nothing** — because the DI layer
(`CoreDiModule.kt:403`) is precisely where a contributor is resolved.

The third version counted `getOrNull<NotificationsContributor>()` and stopped there,
which was correct for *wiring* and wrong for *use*: a section can be wired into the
SettingsViewModel and still read by nothing outside `feature/settings/`. That is
the shape of this bug, so the third version was green on `reminderDefault` — the one
known instance — and on all seven work-schedule fields.

**So the check descends to the individual field.** A persisted field is dead when
no file outside the settings plumbing names it. The plumbing is precisely the set of
directories that can only pass a value along without acting on it.

What does not count
-------------------
`EphemeralState` (`SettingsBundle.kt:89`) is deliberately not persisted — `isExporting`,
`exportedPath`, `savedViews` are UI state, and calling them dead settings would be a
finding about a different concept. They are excluded by the interface they implement.

Exemptions
----------
`scripts/check-dead-settings-baseline.txt` lists one `Section.field` per line with a
`#` reason. A bare `Section` exempts every field in it. Every line is a claim that a
human chose not to act, which is the thing worth being able to grep for later.

Usage:
    scripts/check-dead-settings.py           # human-readable report
    scripts/check-dead-settings.py --quiet   # findings only, for CI

Exit code is 1 when anything is reported, so it can gate a check.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE_SETS = ("shared/src/commonMain", "shared/src/androidMain", "shared/src/jvmMain")
BASELINE = ROOT / "scripts/check-dead-settings-baseline.txt"

COMMON = "com/singularity/todo"

# `data class Notifications(` ... `val enabled: Boolean = ...`
SECTION_DECL = re.compile(r"\bdata class (\w+)\s*\(([^)]*)\)\s*:\s*SettingsSection", re.DOTALL)
# `data class Ai(` ... `) : EphemeralState`
EPHEMERAL_DECL = re.compile(r"\bdata class (\w+)\s*\(([^)]*)\)\s*:\s*EphemeralState", re.DOTALL)
FIELD_DECL = re.compile(r"\bval (\w+)\s*:")

# Where a field reference does NOT count as consumption.
#
# This list is the whole point of the rewrite, so each entry is here for a stated
# reason rather than convenience:
#
# - `core/settings/`   — declares the keys, builds the flows, and is the writer.
# - `core/<domain>/`   — `XSettingsStore` and `XSettingsContributor` read a field to
#                       hand it to the next layer. That is the plumbing this bug
#                       hides behind; counting it made the earlier version green on
#                       every dead field in the codebase.
# - `feature/settings/`— renders and writes every section unconditionally. This is
#                       why `reminderDefault` looks alive on screen.
# - fakes and tests    — do not consume anything in production.
PLUMBING = (
    "core/settings/",
    "core/notifications/",
    "core/schedule/",
    "core/appearance/",
    "feature/settings/",
    "test/fakes/",
    "Test.kt",
    "DiModule.kt",
)


def sources() -> dict[pathlib.Path, str]:
    out: dict[pathlib.Path, str] = {}
    for s in SOURCE_SETS:
        root = ROOT / s
        if root.is_dir():
            for p in root.rglob("*.kt"):
                text = p.read_text(encoding="utf-8", errors="replace")
                text = re.sub(r"/\*.*?\*/", " ", text, flags=re.DOTALL)
                text = re.sub(r"//[^\n]*", " ", text)
                out[p] = text
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--quiet", action="store_true", help="findings only")
    args = ap.parse_args()

    files = sources()
    if not files:
        print("check-dead-settings: no Kotlin sources found — refusing to pass silently", file=sys.stderr)
        return 1

    bundle = ROOT / f"shared/src/commonMain/kotlin/{COMMON}/core/settings/SettingsBundle.kt"
    if not bundle.is_file():
        print(f"check-dead-settings: {bundle.relative_to(ROOT)} is missing — the section "
              f"inventory cannot be read, refusing to pass silently", file=sys.stderr)
        return 1

    text = bundle.read_text(encoding="utf-8", errors="replace")

    persisted: dict[str, list[str]] = {}
    for m in SECTION_DECL.finditer(text):
        persisted[m.group(1)] = FIELD_DECL.findall(m.group(2))

    ephemeral = {m.group(1) for m in EPHEMERAL_DECL.finditer(text)}

    if not persisted:
        print("check-dead-settings: found zero persisted sections — refusing to pass silently",
              file=sys.stderr)
        return 1

    # Exempt entries: a bare section name covers all of its fields.
    sections_exempt: set[str] = set()
    fields_exempt: set[tuple[str, str]] = set()
    if BASELINE.is_file():
        for line in BASELINE.read_text(encoding="utf-8").splitlines():
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            if "." in line:
                sec, _, fld = line.partition(".")
                fields_exempt.add((sec, fld))
            else:
                sections_exempt.add(line)

    findings: list[str] = []
    total = 0
    for section, fields in sorted(persisted.items()):
        if section in sections_exempt:
            continue
        for field in fields:
            if (section, field) in fields_exempt:
                continue
            total += 1
            needle = re.compile(rf"\b{re.escape(field)}\b")
            consumers = [
                str(p.relative_to(ROOT))
                for p, src in files.items()
                if needle.search(src)
                and not any(x in str(p) for x in PLUMBING)
                and p.name != "SettingsBundle.kt"
            ]
            if not consumers:
                findings.append(
                    f"{section}.{field} — persisted, rendered in Settings, and read by no "
                    f"feature outside the settings plumbing"
                )

    if not args.quiet:
        exempt_count = sum(
            1
            for section, fields in persisted.items()
            if section not in sections_exempt
            for field in fields
            if (section, field) in fields_exempt
        )
        skipped = sum(len(v) for k, v in persisted.items() if k in sections_exempt)
        print(f"check-dead-settings: {len(persisted)} persisted sections, "
              f"{total + exempt_count + skipped} fields "
              f"({total} scanned, {exempt_count} baselined individually, "
              f"{skipped} exempted by section)")
        for f in findings:
            print(f"  [dead-setting] {f}")
        if ephemeral:
            print(f"  (not scanned, not persisted: {', '.join(sorted(ephemeral))})")

    if findings:
        if not args.quiet:
            print()
            print(f"{len(findings)} setting field(s) written but never read by a feature.")
            print("Either wire the value into the feature that needs it, hide the control,")
            print("or record why in scripts/check-dead-settings-baseline.txt.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

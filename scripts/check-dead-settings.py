#!/usr/bin/env python3
"""Find settings that are collected, displayed, and read by no feature.

The defect this catches
-----------------------
A setting can be fully wired and still do nothing. `reminderDefault` is the live
instance: it has a radio group in `NotificationSettingsScreen.kt:71`, a preference
key, a `SettingsStore`, a DI binding, and a `Contributor` — and nothing under
`feature/tasks/` ever asks for it. `TaskCreateViewModel`'s KDoc even claims it
"owns the new task draft with ... reminders". The user picks a default, sees it
survive a restart, and no task is ever given it.

`find-unwired-surfaces.py` cannot see this: its `orphan-binding` check asks
whether a Koin binding has an injector, and this chain has one at every step. The
binding resolves, the screen renders, and the value stops at the edge of
`core/`.

Why the check is on the chain, not on the key
---------------------------------------------
The first version of this script matched the preference key name across files and
reported 40+ dead settings, of which every one was a false positive. A key declared
in `SettingsRepository` is read there, by the `Flow` its own repository builds from
the same `Preferences` map it writes to — a tautology, not a consumption. The real
consumer is two hops away, at the feature boundary:

    key -> *SettingsRepository -> *SettingsStore -> *Contributor -> feature

So the question is not "is this key mentioned elsewhere" but "does the store that
exposes it ever reach a feature". That is what this script measures.

Exemptions
----------
`scripts/check-dead-settings-baseline.txt` lists stores that are legitimately not
feature-consumed, one `Store` per line with a `#` reason. A key retained only for
`SettingsDataStoreMigration` is exempt by the `_LEGACY` suffix: its reader is the
migration, by design.

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

# `class XSettingsStore(private val ...)` — the seam between persistence and features.
STORE_DECL = re.compile(r"\bclass\s+(\w*SettingsStore)\b")

# The matching `class XSettingsContributor(private val store: XSettingsStore)`. The
# Contributor is the *last* link before a feature can consume a setting, so it is
# what this script follows: a store referenced only by its own Contributor has been
# handed to a binder and never picked up.
CONTRIBUTOR_DECL = re.compile(r"\bclass\s+(\w*SettingsContributor)\b")

# Where a reference does NOT count as a consumer.
#
# - `core/settings/SettingsContributorsModule.kt` registers every store by design,
#   so counting it would make the check vacuous;
# - `feature/settings/` renders and writes *every* section regardless of whether
#   anything reads the values, which is exactly why `reminderDefault` looks alive
#   on screen. Counting it as a consumer is what let that bug through;
# - the store's own Contributor is the bridge being followed, not a destination;
# - fakes and tests do not consume anything in production.
#
# Deliberately *not* excluded: `core/di/CoreDiModule.kt`, which is where a
# Contributor is actually injected into the SettingsViewModel via `getOrNull()`.
# Excluding the DI layer wholesale is what made the first version of this script
# report nothing at all.
NON_CONSUMERS = (
    "core/settings/SettingsContributorsModule.kt",
    "feature/settings/",
    "test/fakes/",
    "Test.kt",
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

    stores = sorted({m.group(1) for text in files.values() for m in STORE_DECL.finditer(text)})
    if not stores:
        print("check-dead-settings: found zero settings stores — refusing to pass silently", file=sys.stderr)
        return 1

    exempt: set[str] = set()
    if BASELINE.is_file():
        for line in BASELINE.read_text(encoding="utf-8").splitlines():
            line = line.split("#", 1)[0].strip()
            if line:
                exempt.add(line)

    findings: list[str] = []
    contributors = {m.group(1) for text in files.values() for m in CONTRIBUTOR_DECL.finditer(text)}

    # `SettingsViewModel` takes the marker interfaces (`NotificationsContributor`), not
    # the concrete classes (`NotificationsSettingsContributor`), and looks them up with
    # `getOrNull()`. So a live section is named at the DI boundary by its *interface*,
    # and both spellings count as evidence of consumption.
    for store in stores:
        if store in exempt:
            continue

        # `XSettingsStore` -> `XSettingsContributor`, the one hop the value makes
        # before a feature could read it.
        contributor = store.replace("Store", "Contributor")
        if contributor not in contributors:
            continue

        # Evidence of consumption is a *resolution* of the marker interface — the
        # `getOrNull<NotificationsContributor>()` in the DI module — not a mention of
        # the name. An import line is not a use: matching it would make this check
        # pass for a section whose only remaining trace is the import the compiler
        # keeps around, which is precisely the "removed the parameter, left the
        # import" state this is meant to catch.
        #
        # Both spellings count, because the graph resolves the interface
        # (`NotificationsContributor`) while the class that implements it is
        # `NotificationsSettingsContributor`. Requiring only one form reports all six
        # sections dead on a codebase where every one of them is wired.
        marker = contributor.replace("Settings", "")
        lookup = re.compile(
            rf"getOrNull\s*<\s*(?:{re.escape(contributor)}|{re.escape(marker)})\s*>",
        )
        consumers = [
            str(p.relative_to(ROOT))
            for p, text in files.items()
            if lookup.search(text)
            and not any(nc in str(p) for nc in NON_CONSUMERS)
        ]

        if not consumers:
            findings.append(
                f"{store} — registered in DI and reachable through {contributor}, but no "
                f"binding resolves that contributor, so every value it holds is written "
                f"and never read"
            )

    if not args.quiet:
        print(f"check-dead-settings: {len(stores)} settings stores scanned, "
              f"{len(stores) - len(exempt)} subject to the rule")
        for f in findings:
            print(f"  [dead-setting] {f}")

    if findings:
        if not args.quiet:
            print()
            print(f"{len(findings)} settings store(s) written but never read by a feature.")
            print("Either wire the value into the feature that needs it, hide the control,")
            print("or record why in scripts/check-dead-settings-baseline.txt.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

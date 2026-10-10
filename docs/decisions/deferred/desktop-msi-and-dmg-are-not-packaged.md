---
title: "Desktop Msi And Dmg Are Not Packaged"
date: 2000-01-01
status: PARTIALLY
tags: ["deferred"]
---

**Found in:** 2026-10-06, while writing `release.yml`.

**Tracking:** `docs/decisions/2026-10-06-ci-single-gate-registry-and-leaf-split.md` —
the what-this-does-not-do list is there.

**Status: PARTIALLY CLOSED (Deb + RPM done; MSI/DMG remain OPEN).**

**Deb + RPM — DONE (2026-10-09):**
`desktopApp/build.gradle.kts` now declares `targetFormats(TargetFormat.Deb, TargetFormat.Rpm)`
and `release.yml` has both matrix legs. `packageVersion` is injected from the tag.
The RPM `VERSION` field is verified against the tag at upload time.

**MSI + DMG — OPEN.** `packageMsi` and `packageDmg` do not exist and there is no
`main-release` directory — Compose Desktop has no build variants. jpackage cannot
cross-compile, so each format needs its own runner (`windows-2025` and `macos-15`).
Unsigned installers also trip SmartScreen and Gatekeeper.

**Try next:** add `Msi` and `Dmg` to `targetFormats`, add the two runner legs, then
add signing and notarization — in that order, because an unsigned installer is
strictly worse than no installer.

**Not to do:** guess the task names from a different Compose version. The
authoritative list is `./gradlew :desktopApp:tasks --all`, and
`main-release` does not exist in this tree.

---

---
title: "No Direct Clock System Kdoc Claims Tests Are Exempt"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED**

**Tracked as:** #463

**Found in:** `refactor/tag-registry-and-robots`, while fixing the
`NoDirectClockSystem` violation that shipped in `2e99b1d0`.

**Symptom:** the KDoc on `NoDirectClockSystemRule` states "Test sources are
exempt (detekt's standard path filters handle patterns in test directories)".
They are not exempt. Both `shared/build.gradle.kts` and `desktopApp/build.gradle.kts`
put `src/jvmTest/kotlin` in `source.setFrom`, and the rule has no path filter of
its own — so any test helper touching `Clock.System` is a finding, exactly like
production code.

**Already checked:** `isAllowedFile()` in the rule whitelists only
`core/platform/Clock.kt` and `core/di/CoreDiModule.kt`. The exemption the KDoc
describes does not exist anywhere in the implementation.

**Try next:** decide which is true, then make the code match. If tests should be
exempt, add a test-path check to `isAllowedFile()` and a `RuleTest` case proving
a `jvmTest` file no longer fires — that is the cheap reading, and it matches what
`NotesScreenTest` and `TagsRenameUiTest` already do (fixed `Instant`, not
`Clock.System`). If tests should be held to the same standard, delete the
sentence and treat the 47 existing suppressions as the real backlog. Do not
change this while the "47 suppressions" item from
`2026-09-30-test-infra-known-gaps` is still open — the two decisions interact.

---

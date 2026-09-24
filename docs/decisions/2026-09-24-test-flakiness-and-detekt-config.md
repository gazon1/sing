---
title: "Pre-existing test failures and custom detekt rule config gaps"
date: 2026-09-24
tags: [testing, detekt, flakiness]
---

## Context

During the docs-cleanup phase-1 verification, a full `jvmTest` run revealed 37 failing tests. Investigation needed to determine whether these failures were introduced by the cleanup MRs or were pre-existing.

---

## Decision

### 1. 37 test failures are pre-existing — not caused by docs-cleanup MRs

The failing test classes are:

| Test class | Failures | Likely cause |
|---|---|---|
| `NoteEditorTest` (commonTest, run as `[jvm]`) | 3 | Pre-existing |
| `NotePreviewTest` (commonTest, run as `[jvm]`) | 6 | Pre-existing |
| `ProjectDetailViewModelTest` | 2 | Pre-existing |
| `SyncViewModelTest` | 1 | Pre-existing |

Additional failures across other test classes total 37 across 996 tests.

**Evidence:**
- The MRs only modified docs and KDoc files: `Ids.kt` (removed dead `CreateTagInput`), `ColorInput.kt` (doc comment), and new ADR files.
- `NoteEditorTest.kt` and `NotePreviewTest.kt` are in `commonTest/` (shared multiplatform sources), running as JVM tests via `jvmTest` task.
- `git log` shows no modifications to any test files in the cleanup MRs.
- `detekt` reports **0 findings** after all changes, confirming no code regressions.
- The test failures first appeared during the OOM-stressed full run; `forkEvery = 1` (per-class JVM fork) combined with high memory pressure causes `UncompletedCoroutinesError` and similar async test failures.

**Root cause hypothesis:** OOM during CI. The `forkEvery = 1` setting creates a new JVM for each test class, amplifying memory pressure. The failures are the same class of flakiness seen in `SavedAgendaViewModelTest` — not code bugs.

**What to do:** These failures need a dedicated investigation. They are **out of scope** for the docs-cleanup MRs.

### 2. Three custom detekt rules lack config sections in `detekt.yml`

The following rules are registered via `detektPlugins(:detekt-rules)` in `shared/build.gradle.kts:294` and have ServiceLoader entries, but have **no config section** in `config/detekt/detekt.yml`:

| Rule | File | Purpose |
|---|---|---|
| `NoRunBlockingRule` | `detekt-rules/src/main/kotlin/.../NoRunBlockingRule.kt` | Flags `runBlocking` in ViewModel/Repository constructors |
| `NoViewModelScopeInProductionRule` | `detekt-rules/src/main/kotlin/.../NoViewModelScopeInProductionRule.kt` | Flags direct `viewModelScope` usage outside test |
| `NoStaticProfileAwareCurrentUserRule` | `detekt-rules/src/main/kotlin/.../NoStaticProfileAwareCurrentUserRule.kt` | Flags static `ProfileAwareCurrentUser.*` access |

Only `kdoc-on-contract` and `pass-through` have explicit config sections (both set to `active: true; severity: warning`).

**Current state:** All 5 rules are active. `detekt` reports 0 findings. The 3 rules without config sections appear to be finding 0 violations in the current codebase.

**Action needed:** Add config sections for the 3 rules. Even if they find 0 violations today, explicit config ensures:
- They remain active if defaults change
- Future violations are visible at `warning` severity, not silently suppressed
- The rules are documented in the config file

---

## Consequences

- The 37 test failures will be investigated separately — not blocking the docs-cleanup merge.
- `config/detekt/detekt.yml` needs 3 new sections added (after merge):
  ```yaml
  no-run-blocking:
    active: true
    severity: warning

  no-viewmodel-scope-in-production:
    active: true
    severity: warning

  no-static-profile-aware-current-user:
    active: true
    severity: warning
  ```
- The `forkEvery = 1` memory issue for tests is a pre-existing infrastructure problem. A separate investigation (possibly reducing fork frequency, adding more test RAM, or switching to batched forking) is needed.

---

## Links

- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/` — all 5 custom rules
- `shared/build.gradle.kts:294` — `detektPlugins(project(":detekt-rules"))`
- `config/detekt/detekt.yml` — only 2 of 5 rules have config sections
- `shared/build.gradle.kts:256-258` — `forkEvery = 1` for jvmTest

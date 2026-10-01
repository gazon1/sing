---
title: "Maestro flow tag contract — JVM test gate"
date: 2026-10-01
status: accepted
tags: [testing, maestro, test-tags, ci-gates]
---

# Maestro flow tag contract — JVM test gate

## Context

Every `id:` selector in a Maestro flow must resolve to a known entry in
`TestTags.kt`. An unknown id means the flow will time out waiting for a UI
element that does not have that testTag — a runtime failure that is caught only
when the flow runs on a device.

The CI pipeline previously ran `Maestro/scripts/check-tags.sh` (bash + Python
regex) as a `continue-on-error: true` step, so regressions silently passed CI.
The script was also hand-maintained Python logic that duplicated the parsing
already present in `TestTagsCatalog` (JVM).

## Decision

Introduce `com.singularity.todo.arch.MaestroFlowTagsTest` — a blocking `@Tag("fast")`
JVM test that runs as part of `:shared:jvmTest`.

```kotlin
// MaestroFlowTagsTest.kt
// 1. Scans Maestro/flows/**/*.yaml and Maestro/helpers/**/*.yaml for id: selectors
// 2. Verifies each id is in TestTagsCatalog (static const OR dynamic prefix)
// 3. Guards against a broken collector with assertTrue(ids.size > 40)
// 4. Accepts calendar_day_* and profile_item_* as known exceptions
//    (both functions are excluded from dynamicFunctions() by design — see §Known limitations)
```

The CI step `Check Maestro test tags` remains as `continue-on-error: true` for
ad-hoc local use, but is no longer the canonical gate. The JVM test is.

### Known limitations

1. **`calendar_day_*`** — `calendarDay(isoDate)` does not use `slug()`, so it is
   excluded from `TestTagsCatalog.dynamicFunctions()`. The expanded form
   `calendar_day_YYYY_MM_DD` is accepted as a special-case regex in the test.

2. **`profile_item_*`** — `profileItem(name)` uses
   `"${PROFILE_ITEM_PREFIX}${slug(name)}"` (prefix interpolation, not suffix), so
   the `DYNAMIC_FUN_REGEX` in `TestTagsCatalog` (`...\$\{slug\("$")`) does not
   match it. The expanded form `profile_item_<slug>` is accepted as a
   special-case regex in the test.

   Both should eventually be derivable from the catalog without a special-case
   regex — this is tracked in Consequences.

## Rationale

- **Single source of truth**: `TestTagsCatalog` reads `TestTags.kt` directly via
  regex; the JVM test reuses it without duplication.
- **No new dependencies**: regex-based YAML parsing, zero new `testImplementation`
  deps.
- **Blocking**: any unknown id now fails the build; `continue-on-error: true` on
  the shell script is retired from the CI critical path.
- **Guard against vacuous green**: `ids.size > 40` assertion fires if the
  collector breaks silently.

## Consequences

- **Positive:** Unknown tag ids are now a build failure in `:shared:jvmTest`.
- **Roadmap — special-case elimination**: `calendarDay` and `profileItem` should
  be derivable from `TestTagsCatalog.dynamicFunctions()` without regex special
  cases. Fix `DYNAMIC_FUN_REGEX` to handle prefix interpolation
  (`${PREFIX}${slug(name)}`) and add both functions to `dynamicFunctions()`.
  Then remove the corresponding regex patterns from `MaestroFlowTagsTest`.
  This is a refactor, not a bug fix.
- **Roadmap — delete `check-tags.sh`**: after the special-case elimination above,
  the shell script is fully redundant. Delete it after one sprint of clean JVM
  test runs.
- **`saved-views-crud-flow-selects-a-snackbar-that-does-not-exist`** (deferred
  backlog) is a runtime UI bug, not a tag-contract bug. `SNACKBAR_SAVED` exists
  in `TestTags.kt`, so this test does not catch it.

## Links

- `Maestro/scripts/check-tags.sh` (legacy — see §Roadmap)
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/MaestroFlowTagsTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/ui/TestTagsCatalog.kt`
- `docs/decisions/deferred-backlog.md#saved-views-crud-flow-selects-a-snackbar-that-does-not-exist`

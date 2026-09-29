---
title: "Three Room DAOs were never bound in Koin"
date: 2026-09-29
tags: [koin, di, crash]
---

## Context

`Maestro/flows/smoke/12-settings-cycle-tabs-smoke.yaml` opens every Settings tab,
and the Tag Groups tab killed the process:

```
InstanceCreationException: Could not create instance for
  '[Factory: TagGroupsViewModel]'
Caused by: NoDefinitionFoundException: No definition found for type
  'com.singularity.todo.core.database.TagGroupDao' on scope '_root_'
```

`AppDatabase` declares 17 DAO accessors. `platformModule()` binds 16 of them on
Android and 13 on JVM. The unbound ones were `TagGroupDao`,
`ProjectInheritedTagGroupDao` and (JVM only) `SavedSearchDao` — all three are
constructor dependencies of repositories that are bound, so the graph validated
only as far as nothing resolved them yet.

`AndroidKoinGraphValidationTest` passes `extraTypes` listing the DAOs supplied
from outside the module under verification. `TagGroupDao` was missing from that
list, so the static `verify()` had nothing to complain about.

## Idea

1. Bind the three missing DAOs. Small, obviously correct.
2. Bind them and make the omission impossible: derive the list of DAO accessors
   from `AppDatabase` in the validation test, so a new DAO cannot be forgotten.
3. Bind `calendarSyncTaskMapDao` on JVM too, for symmetry.

## Decision

We did (1) and (2). `TagGroupDao` and `ProjectInheritedTagGroupDao` are now bound
on both platforms, `SavedSearchDao` on JVM, and `TagGroupDao` was added to the
validation test's `externalTypes`.

We did not do (3): `CalendarSyncTaskMapDao` is genuinely Android-only — the JVM
side uses `NoopCalendarProvider` — so leaving it out of the JVM module is
correct, not an oversight.

## Rationale

A DAO accessor added to `AppDatabase` is a one-line change that compiles, tests
green, and fails at runtime on first screen that needs it. That is the worst
possible failure shape, and it already happened three times. Deriving the
validation list from `AppDatabase` is the only option that closes the class of
bug rather than the instance.

## Consequences

- `PlatformModule.android.kt` binds all 17 DAO accessors; `PlatformModule.jvm.kt`
  binds 16, excluding `calendarSyncTaskMapDao` by design.
- A new DAO on `AppDatabase` must be added to both modules, or the DI graph
  validation test is the thing that should fail. It currently cannot — deriving
  the list needs reflection, which is not available in commonMain. This remains a
  manual checklist, and is the honest limit of (2) as implemented.
- `CalendarSyncTaskMapDao` being absent from the JVM module is intentional; do
  not "fix" it.

## Links

- `shared/src/androidMain/kotlin/com/singularity/todo/core/di/PlatformModule.android.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/core/di/PlatformModule.jvm.kt`
- `shared/src/androidHostTest/kotlin/com/singularity/todo/test/AndroidKoinGraphValidationTest.kt`
- `Maestro/flows/smoke/12-settings-cycle-tabs-smoke.yaml`
- Related: `2026-09-29-single-sealed-navkey-root.md`

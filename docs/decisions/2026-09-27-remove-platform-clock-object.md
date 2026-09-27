---
title: Remove `core.platform.Clock` — use `kotlin.time.Clock` everywhere
status: accepted
date: 2026-09-27
authors: ZCode Agent
deciders: Singularity Developer
tags: [clock, architecture, migration, kotlin-stdlib]
epic: tech-debt/repair-broken-build
---

# Remove `core.platform.Clock` — use `kotlin.time.Clock` everywhere

## Context

Commits `d8a7e164` and `14507c51` (both committed with empty messages) began a
migration off the project's own clock abstraction, but landed only halfway. The
resulting tree did not compile.

`shared/src/commonMain/.../core/platform/Clock.kt` ended up declaring **both**:

```kotlin
actual fun todayInSystemZone(): LocalDate   // 'actual' with no matching 'expect'
...
@Deprecated("Use kotlin.time.Clock directly.")
object Clock { fun now(): Instant = Clock.System.now() }   // collides with the import above
```

with matching `actual object Clock` in `jvmMain` and `androidMain`. The
`actual`-without-`expect` pair produced *"Conflicting overloads"*, and the
`object Clock` shadowed the very `kotlin.time.Clock` the file imports.

Three signals showed where the migration was meant to land:

1. DI already bound **both** clocks —
   `single<kotlinx.datetime.Clock> { kotlin.time.Clock.System }` *and*
   `single { com.singularity.todo.core.platform.Clock }`.
2. `kotlinx.datetime.Clock` is, in kotlinx-datetime 0.8.0, nothing but a
   deprecated typealias for `kotlin.time.Clock`.
3. `test/fakes/FakeClock.kt` already declared `class FakeClock(...) : Clock`
   against `kotlin.time.Clock`, and every repository already called
   `clock.now()` — the interface method, not the removed object's.

So the intended end state was clear, and the half-migration was the only
problem.

## Idea

Delete the project clock entirely and let `kotlin.time.Clock` be the single
abstraction. There is no remaining capability the object provided: `now()` was
the only member, and it delegated to `Clock.System` anyway.

## Decision

1. **Remove `object Clock`** from `core/platform/Clock.kt`, and the matching
   `actual object Clock` from `Clock.jvm.kt` / `Clock.android.kt`.
2. **Retarget imports.** All 65 files importing
   `com.singularity.todo.core.platform.Clock` now import `kotlin.time.Clock`.
   Because the type is spelled the same, every constructor parameter
   (`private val clock: Clock`) keeps compiling untouched.
3. **Call sites.** `Clock.now()` → `Clock.System.now()`. Injected instances
   (`clock.now()`) were already correct and are unchanged — `kotlin.time.Clock`
   exposes `now()` as a method.
4. **DI.** Keep one binding, `single<Clock> { Clock.System }`, and drop the
   binding for the deleted object.
5. **`todayInSystemZone()`** is pure (`Clock.System.now().toLocalDateTime(zone).date`),
   so it is now a plain `commonMain` function with no `expect`/`actual` and no
   platform file.
6. **`isDesktop`** is removed: it had no `expect`, no usages, and no callers.

### Consequence: the singleton-vs-interface trap

`kotlin.time.Clock` is an *interface* whose `System` is a companion **extension
property**. A bare `Clock` in expression position therefore resolves to
`Clock.Companion`, not to a clock instance. Every former `= Clock` default
argument and DI argument had to become `= Clock.System`:

```kotlin
private val clock: Clock = Clock            // ✗ Clock.Companion
private val clock: Clock = Clock.System     // ✓
```

This is a *value*-position trap only — the type annotation `clock: Clock`
is still correct and needs no change.

## Rationale

The project clock added a layer with no behaviour, a name that shadows the
standard-library type, and an `actual object` shape that has to be mirrored in
every source set. `kotlin.time.Clock` is the stdlib's answer to the same
problem, it is what Koin already had a binding for, and it is what the test
fakes already implement. One abstraction, no shadowing, no platform files.

## Consequences

- `core/platform/Clock.kt` no longer declares anything `expect`/`actual`; the
  only platform-specific piece left is `systemTimeZone` (in
  `TimeZoneProvider.kt`), which genuinely differs per platform.
- `Clock.jvm.kt` / `Clock.android.kt` shrink to the single `actual val
  systemTimeZone`.
- Injecting a `Clock` still gives full test control — `FakeClock` implements
  the same interface, so nothing about testability regressed.
- Anything still passing a bare `Clock` as a value will silently bind
  `Clock.Companion` and fail to compile, which is the intended loud failure.

## Links

- `docs/decisions/2026-09-27-draft-mvi-single-state-source.md` — the other
  contract repair in the same sweep
- Kotlin stdlib: `kotlin.time.Clock`

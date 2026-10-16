---
title: "Koin singleOf does not apply Kotlin default arguments"
date: 2026-10-16
status: accepted
deciders: max
tags: ["di", "koin", "testing", "desktop", "compose"]
---

## Context

`:desktopApp:test` reported 82 failures out of 135 tests. 73 of them shared a single
root cause that reached the surface as:

```
org.koin.core.error.InstanceCreationException: Could not create instance for
  '[Factory: 'com.singularity.todo.feature.agenda.presentation.viewmodel.AgendaViewModel']'
    ...
  Caused by: NoDefinitionFoundException: No definition found for type 'java.lang.Long'
```

The resolution chain is `AgendaViewModel` → `TaskRepository` → `SyncRepository` →
`SyncEngine` → `PatchRetryPolicy`. The failing definition was
`SyncDiModule.kt:219`:

```kotlin
singleOf(::PatchRetryPolicy)
```

and the class it names (`core/sync/SyncOutbox.kt:152`) is:

```kotlin
open class PatchRetryPolicy(
    private val baseDelayMs: Long = 30_000L,
    private val maxDelayMs: Long = 60 * 60 * 1000L,
    val maxAttempts: Int = 10,
)
```

Koin's `singleOf`/`factoryOf`/`viewModelOf` resolve the primary constructor
**reflectively** and do not apply Kotlin default arguments. Every constructor parameter
therefore has to be resolvable from the graph, whether or not it has a default. Here
`baseDelayMs: Long` has no `Long` in the graph, so resolution fails — and it fails at
**runtime**, at the first `get<AgendaViewModel>()`, not at compile time.

## Decision

1. `singleOf(::PatchRetryPolicy)` becomes an explicit `single { PatchRetryPolicy() }`,
   with a comment stating why the reflection-based shorthand cannot be used. The now
   unused `singleOf` import is removed from `SyncDiModule.kt`.

2. The repository is swept for the same defect class. All `*Of(::Class)` registrations
   (99 call sites, 57 unique target classes across `shared/`, `androidApp/`,
   `desktopApp/`) were checked for Kotlin defaults in the primary constructor. Exactly
   three classes have them:

   | Class | Registration | Status |
   |---|---|---|
   | `PatchRetryPolicy` | `SyncDiModule.kt:219` | broken — fixed here |
   | `AccountSettingsViewModel` | `Modules.kt:80` | latent, currently resolvable |
   | `ChangePasswordViewModel` | `Modules.kt:81` | latent, currently resolvable |

   The two ViewModels default to `CrashReportingPort` and `AutoCloseableCoroutineScope`.
   Neither default is ever applied — Koin resolves both from the graph, because
   `CrashReportingPort` is bound and consumed at `Modules.kt:69,76`. They work today by
   coincidence of the graph, not by design, and are left unchanged: rewriting them is a
   separate, wider change than this defect.

   Four targets could not be resolved by the sweep and were inspected by hand:
   `SavedAgendaSeedStore`, `StubAttachmentUploadService` and `StubRemoteBackupService`
   have no-arg constructors, and `Impl` is a match inside a comment at
   `AiToolsModule.android.kt:92`. All four are safe.

## Rationale

The explicit lambda is one line instead of one, and it is the only form that states the
intended values. `single { PatchRetryPolicy() }` calls the Kotlin constructor, so the
defaults are applied by the language; `singleOf(::PatchRetryPolicy)` asks Koin to
reconstruct the constructor by reflection, where those defaults do not exist.

Why `koin-compiler-plugin` did not catch it: the constructor reference `::PatchRetryPolicy`
is well-typed, so the compile-time graph check sees every parameter as satisfied by the
reference itself. The missing definition only becomes visible when the reference is
reflectively expanded at resolution time. Compile-time verification cannot cover this
defect class — which is why the sweep is a sweep and not a compiler flag.

Why the sweep is worth having at all given it found one real defect in 99 sites: the
failure mode is invisible until a screen resolves the definition, and it presents as a
DI wiring error at some unrelated composition root, far from the line that is wrong. The
cost of the sweep is one script; the cost of the class is a red test suite that looks
like 34 independent bugs.

## Consequences

- `:desktopApp:test` goes from **82 failures / 135 tests to 9**. The 73 removed failures
  are gone because `AgendaViewModel` resolves again.
- Of the 9 remaining, **7 test cases (in 7 classes) were masked by this bug**, not caused
  by it: they crashed during Koin resolution before ever reaching their own assertion, and
  now fail on their real defect. Six `CalendarSync*` scenario classes fail with
  `Tag 'settings_tab_calendar' is not in the semantics tree` (0 tags in the tree at all),
  and `NavigationFlowTest` fails waiting for a `Settings` content description. These are
  newly visible, not newly broken.
- The 2 `SearchPresentationUiTest` failures are genuinely independent: they failed for
  their own reason before this change and still do (`is not enabled` on the
  Rename/Save buttons of the search dialogs).
- Any future `*Of(::Class)` against a class with constructor defaults is the same latent
  `NoDefinitionFoundException`, waiting for the first screen that resolves it.

## Follow-up: the 9 that surfaced were three defects, not nine

With the Koin crash gone, the remaining 9 were diagnosed separately and resolved to
three distinct roots. `:desktopApp:test` is now **135 tests, 0 failures**.

### 1. The desktop drawer could not open Settings at all (product defect)

Six `CalendarSync*` classes plus `NavigationFlowTest` shared one cause: the navigation
drawer is a fixed `Column` — six tabs, a divider, eight menu entries — and on the
1024x768 test window it is taller than the viewport, so **Settings, the last entry, was
clipped off the bottom and unreachable**. A user on a short window could not open
Settings from the drawer at all.

This is the same defect that was already found and fixed for the settings nav rail
(`SettingsNavRail` in `SettingsScreen.kt`, whose comment describes two settings being
simply untappable). The drawer had it too and nobody looked, because the test asserting
it was crashing earlier for an unrelated reason.

Fix: the drawer's column scrolls (`DesktopShellNav3.kt`), matching the rail.

### 2. Clicking a clipped node is a silent no-op (test-harness defect)

The second half of the same story is why the six `CalendarSync*` failures reported
`settings_tab_calendar` missing rather than reporting a broken drawer. With the drawer
open, `awaitContentDescription("Settings")` **succeeded** — the node is composed, just
off-screen — and `performClick()` on it also succeeded, because Compose dispatches to
the node's coordinates, which were outside the window. The navigation it named never
happened, and the failure surfaced at the *next* assertion, thousands of lines away from
the click that did nothing.

So the failure mode is not "the test fails near the defect" but "the test fails somewhere
else, naming an unrelated tag". Fixed by adding scroll-aware helpers rather than by
loosening assertions:

- `clickContentDescriptionScrolled(desc)` — for anything addressed by its label;
- `tapTab(label)` now does `performScrollTo().performClick()`;
- `assertContentDescriptionReachable(desc)` — new, and used by
  `NavigationFlowTest.drawer_exposes_every_destination`.

That last one is a deliberate change of claim. The test asserted every drawer entry was
*displayed*; in a scrolling column that asserts the absence of scrolling, so it would fail
the day someone made the drawer scrollable while passing on the drawer that cannot open
Settings at all. "Reachable" is what the test's name means and it is the claim that keeps
failing if the scroll is removed.

### 3. A disabled-button assertion that could not fail (test defect)

`assertTextNotEnabled("Rename")` matched the `Text` **inside** the `TextButton`. That node
carries no `Disabled` property — `enabled = false` decorates the button node, not its
label. The assertion therefore failed against a correctly disabled button, and would have
passed against a button that was never disabled: it said nothing about the control it
named. `assertButtonNotEnabled` / `assertButtonEnabled` now select `hasText(text) and
hasClickAction()`, which is the control. A disabled `Modifier.clickable` keeps its click
semantics, so the matcher still resolves while the control is off.

The text-level helpers are unchanged and still used for text fields, where the text node
is the right target.

### The transferable lesson

Fixing the Koin binding did not fix 73 tests; it revealed 9 more. Two of the three roots
found afterwards were invisible for the same reason the Koin bug was: a test failing for a
reason unrelated to the defect it was written to catch. The counting that made this
tractable — classify every failure by its actual root cause in the XML, rather than
assuming the count of red tests is the count of bugs — is what separated "82 failures" into
"one bug, plus seven masked, plus two genuine, plus a drawer nobody could open".

## Links

- PR #657 — gate registry strangler; the working CI that made this measurable
- `shared/src/commonMain/kotlin/com/singularity/todo/core/di/SyncDiModule.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncOutbox.kt`
- ADR `2026-09-06-koin-vm-viewmodelof-koinviewmodel` — the `viewModelOf` half of the same family
- Skill `singularity-todo-di-graph-testing`
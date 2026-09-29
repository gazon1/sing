---
title: "One SavedStateConfiguration per graph — a shared one silently dropped nested screens"
date: 2026-09-29
status: accepted
tags: [nav3, android, regression, serialization]
---

## Context

The sealed-`AppNavKey` unification in
`2026-09-29-single-sealed-navkey-root` fixed the Settings/Search crash but, in
doing so, replaced each NavGraph's own `SavedStateConfiguration` with a **single
shared `val`**. That regressed navigation-state restoration, and the regression
was already on `main` before it was found.

The symptom is not a serialization error. Rotating the device in a nested screen
returns the app to the agenda with the detail screen gone, and everything still
compiles, still passes `:shared:jvmTest`, and still looks correct in every
existing test. It reads as "the app reset" rather than as a state bug, which is
why it survived a merge and was only caught by
`Maestro/flows/lifecycle/03-rotate-in-editor.yaml` — a flow written for an
unrelated purpose.

## What it was not

Two plausible explanations were checked and ruled out before touching code:

- **Missing serializer registration.** `subclassesOfSealed(AppNavKey.serializer())`
  does register every route, including leaves of nested sealed hierarchies
  (`TasksGraph`, `ProjectsGraph`, …) and the lone `data object` routes. Confirmed
  by a scratch test that built the app's exact module and round-tripped twelve
  route types; the current `NavKeyRegistrationTest` keeps that guard.
- **A pre-existing bug.** An APK built from the commit before the epic
  (`38e754b3`) keeps the detail screen across rotation. The regression is
  therefore this refactor's, not something inherited.

The cause is the `SavedStateConfiguration` instance itself: it carries the saved
payload of the graph that uses it. One instance shared by the outer graph and
every nested graph lets them overwrite each other's entries, so on restore the
outer stack comes back and the nested entry on top of it does not.

## Decision

`navSavedStateConfig()` is a **function again**, so every graph builds its own
instance. What stays common is the *registration* — all routes hang off
`AppNavKey`, so one `subclassesOfSealed` call still covers every graph, including
the lone `data object` routes that motivated the sealed root in the first place.

## Consequences

- Rotation and process-death restore keep the nested screen again; verified on
  device, not just in a test.
- A future change that re-shares the configuration will show up as
  `03-rotate-in-editor` failing, which is the intended alarm. Keep that flow.
- `NavKeyRegistrationTest` guards the other half: a route added outside
  `AppNavKey` fails there rather than at restore time.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/Nav3SavedState.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/nav/NavKeyRegistrationTest.kt`
- `Maestro/flows/lifecycle/03-rotate-in-editor.yaml`
- Related: `2026-09-29-single-sealed-navkey-root`, `2026-09-29-emulator-crash-recovery-runner`

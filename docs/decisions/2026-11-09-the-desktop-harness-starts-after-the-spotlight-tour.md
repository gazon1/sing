---
title: "The desktop harness starts as a user who has already seen the spotlight tour"
date: 2026-11-09
status: accepted
tags: ["ui", "compose", "tests", "onboarding"]
---

# The desktop harness starts as a user who has already seen the spotlight tour

## Context

`2026-11-09-the-spotlight-tour-publishes-its-step.md` made the spotlight tour
publish its step, and the tour became observable: it now appears as soon as its
target is measured, instead of only when a later layout pass happened to
re-publish the state.

That change had a consequence nobody measured at the time. The desktop UI
harness (`desktopApp/src/jvmTest/.../DesktopAppHarness.kt`) mounts the whole
production `App` with a fresh, empty onboarding record — a user who has never
seen the tour. The tour is a first-run modal: its scrim
(`SpotlightOverlay.kt`) fills the whole screen and is `clickable`, so it
swallows every tap. Every flow in the harness that clicks an agenda row, a
checkbox or a top-bar button was clicking the scrim.

The symptom was 45 unique desktop test failures — "Tag 'X' is not in the
semantics tree", "still not satisfied after 5000 ms" — in CI's
`JVM tests` job, and the `CI gate` job red because of it.

The product behaviour is not a defect: a first-run tour is supposed to cover
the screen until the user finishes it. The defect was in the test environment,
which mounted the app in a state no flow was about.

## Idea

- Fix the test environment, not the product.
- Make the harness's onboarding state explicit and shared, instead of copying
  the anonymous fake into the harness.
- Leave the tour's own tests alone: they bring their own record.

## Decision

- `FakeOnboardingSettingsRepository` becomes a named class in
  `shared/src/commonMain/.../test/fakes/FakeRepositories.kt`, with an
  `initialSeenVersion` parameter. `FakeSettingsRepository` uses it too, so
  there is one implementation of the in-memory onboarding record.
- The desktop harness binds that fake with `initialSeenVersion =
  SpotlightContent.VERSION`: the harness starts as a user who has finished the
  tour, so no overlay covers the screen a flow is about.
- The harness's module is loaded after `domainModule()`, so it wins Koin's
  last-definition-wins rule over the real DataStore-backed binding in
  `CoreDiModule`.
- `SpotlightTourTest` is untouched: it has its own `RecordingOnboardingStore`
  and asserts the tour's behaviour directly.

## Rationale

**The product is right and the environment was wrong.** Making the tour
non-modal, or letting taps through the scrim, would change a deliberate
first-run experience to satisfy a test environment — and would need its own
decision record. The flows under test are about the agenda, the tasks and the
saved views; none of them is about the tour.

**One fake, two callers.** The anonymous onboarding object inside
`FakeSettingsRepository` and the harness's binding were the same record with
the same behaviour. Naming it and parameterising the starting version is
smaller than copying it, and it keeps the "0 means never seen" default that
the settings tests rely on.

**Last-wins is the harness's established convention.** The AI model, the
platform bindings and the clock are all overridden the same way, each with a
comment saying why it is loaded last. The onboarding record follows that
pattern instead of introducing a new mechanism.

**The tour still has coverage.** `SpotlightTourTest` drives the tour with its
own store, so the first-run path is tested — just not through a harness that
mounts the entire app.

## Consequences

- All 330 desktop tests pass again, with 0 skipped.
- A flow that wants the tour to be on screen has to ask for it: the harness
  mounts as a user who has seen it, and the tour's own tests set up their own
  record.
- `FakeSettingsRepository.onboarding` and the harness's onboarding record are
  now the same implementation, so a change to the in-memory record's behaviour
  reaches both.
- The first-run overlay is no longer covered incidentally by unrelated flows.
  It is covered by `SpotlightTourTest` alone — that test is now load-bearing,
  not decorative.

## Links

- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/helpers/DesktopAppHarness.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRepositories.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/onboarding/SpotlightOverlay.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/onboarding/SpotlightTourController.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/core/ui/onboarding/SpotlightTourTest.kt`
- `docs/decisions/2026-11-09-the-spotlight-tour-publishes-its-step.md`

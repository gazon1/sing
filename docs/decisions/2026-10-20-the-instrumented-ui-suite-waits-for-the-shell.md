---
title: "The instrumented UI suite waits for the shell, and reports what was there"
date: 2026-10-20
status: accepted
tags: ["testing", "android", "ci", "instrumentation"]
---

## Context

`android-device-tests` was red on every run of `main` from `15b16e93` on:

```
NavigationSmokeTest.app_launches_and_all_tabs_are_reachable
  java.lang.AssertionError: Today tab should be visible after cold start
NavigationSmokeTest.fab_visibility_matches_tab_contract
CreateTaskFlowTest × 3
  java.lang.NullPointerException: ... UiObject2.click() on a null object reference
```

The message named the shell, and the cause was a screen in front of it. Two
candidate causes were on the table, and both had been measured before:

- the app opening on the **sign-in screen** — the APK under test assembled
  without the auth bypass, written up in
  `2026-10-16-android-instrumentation-runs-through-the-emulator-module.md`;
- the app being **slow to start** on a loaded CI runner — the Maestro helper
  that reaches the same screen already waits 30 s and says why
  (`Maestro/helpers/launch-clean.yaml`: *"8 s is not always enough on a loaded
  CI runner; 20 s covers cold-start variance including migration
  deferred-startup on first launch"*).

The instrumented tests used one wait for the shell, in every UI class:

```kotlin
device.wait(Until.findObject(By.res("nav_tab_today")), 8_000)
```

and treated a miss as `findObject(...)!!.click()` — a `NullPointerException`
with nothing in it: no cause, no screen, no budget.

### The wait, and what it costs

`UiDevice.wait` does not spend its budget on attempts. Its first check calls
`UiDevice.findObject`, which waits for the app to be **idle** first, and the
deadline is only compared *after* a check has run. On a runner where the app's
main thread is busy for seconds after a cold start — the failing run's own
logcat says `Choreographer: Skipped 34 frames! The application may be doing
too much work on its main thread` — the first lookup happens after the budget
is gone, and `wait` returns null.

The shell was there all along. In the green run of the same code the first
lookup succeeded **1.35 s** after the activity went RESUMED, and the run's
per-test logcat shows the app's main thread idle by then.

### What ruled the sign-in screen out

`AppStartupTest.the_build_under_test_carries_the_auth_bypass` reads
`BuildConfig.TEST_AUTH_BYPASS` from the APK under test, and
`the_app_is_not_asking_for_a_sign_in` reads the session from the app's own
Koin graph. Both pass in CI: the bypass arrives, the session is the stub's
`SignedIn`, and the shell is reachable. The screen in front of the shell was
never a screen — it was the budget.

## Idea

- **A.** Raise the budget from 8 s to 30 s and leave the wait as it is.
- **B.** Wait for the shell with a deadline loop that checks the deadline
  *after* every attempt, report the screen that was there when it did not
  arrive, and assert the preconditions — the bypass, the session, the test
  tags — by name.
- **C.** Make the app's cold start fast enough that 8 s is enough.

## Decision

**B**, and C only where it is not the test's job.

- One wait for the shell, in `ShellProbe.kt`: a deadline loop with its own
  clock, a 30-second budget (the budget the Maestro layer already uses for the
  same screen), and a failure that carries the accessibility tree, the session
  the app is in, and whether the APK under test carries the auth bypass. A
  stalled `waitForIdle` then costs one attempt instead of the whole budget.
- `AppStartupTest` asserts the preconditions by name, so a build that lost the
  bypass fails as *"the app is on the sign-in screen"* rather than as *"the
  shell was not visible"*.
- `UiDevice.getInstance` is waited for too: it needs the accessibility service
  to be bound, and the first call in a process can be made before that binding
  exists. The failure it produces — `UiAutomation not connected` — takes the
  whole UI suite with it, because every later class asks for the same handle in
  the same broken state.
- No product change. The app reached the shell in 1.35 s in CI; the test was
  the thing that gave up.

## Rationale

- **A test that fails on a busy runner has measured the runner.** The Maestro
  layer had already learned this and moved to 30 s; the instrumented layer kept
  the old number and failed on exactly the runner the other layer had learned
  to wait for.
- **A failure that names a cause is a finding; a failure that names a symptom
  is a chore.** "Today tab should be visible after cold start" cost a full
  diagnostic round-trip — the CI logcat, the per-test timeline, the
  accessibility-service behaviour, the auth bypass, the version gate — to
  establish that the shell was visible 1.35 s after the activity resumed. The
  screen dump and the session in the failure message are the end of that.
- **The preconditions are worth asserting on their own.** "The APK under test
  was assembled without `-PTEST_AUTH_BYPASS=true`" is a fact about the build,
  and it is now a test instead of a paragraph in a workflow file.

## Consequences

- The instrumented suite is 19 tests (was 16) and is green on `main`:
  `android-device-tests` run `39328411476` on this branch, 19/19.
- The UI suite takes about 20 s longer per class on a busy runner, and that is
  the point: it waits for the app instead of guessing.
- A UI failure now carries a screen dump, so the artifact answers the question
  the logcat used to have to answer.
- The `UiAutomation not connected` race is gone, and a class that runs first in
  the process no longer takes the suite with it.

## Links

- `androidApp/src/androidTest/kotlin/com/singularity/todo/ui/ShellProbe.kt`,
  `androidApp/src/androidTest/kotlin/com/singularity/todo/ui/AppStartupTest.kt`
- `Maestro/helpers/launch-clean.yaml` — the same screen, waited for with the
  same budget, on the layer that had already learned the number
- [2026-10-16-android-instrumentation-runs-through-the-emulator-module.md](2026-10-16-android-instrumentation-runs-through-the-emulator-module.md)
- [2026-10-13-ensure-device-acquires.md](2026-10-13-ensure-device-acquires.md)

---
title: "The debug build starts signed in"
date: 2026-10-27
status: accepted
tags: ["architecture", "e2e", "maestro", "tooling"]
---

## Context

The Maestro tier keeps failing the same way: the APK on the device was built
without `-PTEST_AUTH_BYPASS=true`, so the cold start opened **"Connect your
server"** — the Supabase sign-in screen — and every flow waited for
`nav_tab_today` until it gave up. 45 seconds per flow, then

    Assertion is false: id: nav_tab_today is visible

The suite looked broken. It was measuring a different application than the one
the flows describe.

The flag was **opt-in at every call site**, and one call site forgot it:
`tests::gate-maestro` installed the APK with

    ./gradlew :androidApp:installDebug -Pandroid.device=<serial> --quiet

— no property, and the wrong wrapper besides. ADR
`2026-10-20-the-local-e2e-run-measures-the-binary-ci-measures.md` fixed the
*runner's* install (`gradle_install_debug` passes the property) and said the
right thing — "the property belongs to the build the tier needs, not to the
invocation" — but it left the build itself opt-in. So the developer's own
install, the Maestro gate's install and the instrumented tier's `assembleDebug`
could each produce a binary that no flow can run, and the fix was always the
same manual step: rebuild it by hand and say so again.

## Idea

- **A.** Keep the flag opt-in and remember it at every call site.
- **B.** Make a debug build carry the bypass by default; the flag turns it off.
- **C.** Make the binary declare the bypass in its `versionName`, and let the
  runner read the declaration off the device and replace a binary without it.

## Decision

**B** — `BuildConfig.TEST_AUTH_BYPASS` is declared per build type: the debug
build carries it unless `-PTEST_AUTH_BYPASS=false` says otherwise, and the
release build never carries it.

The two build types now disagree about the field, so it moved out of
`defaultConfig` and into `buildTypes`: one declaration for both cannot say
"a debug build starts signed in and a release build does not".

`tests::gate-maestro` also installs through `./gw` and states the property at
the call site, so the tier that needs the bypass says so where it installs.

## Rationale

- **The wrong binary was the default one.** A flag nobody has to type is a
  flag nobody forgets; a flag every call site has to remember is remembered by
  the call sites that were written last. The default is the place where the
  right thing stops depending on memory.
- **A debug build that opens on the sign-in screen cannot be tested.** It is
  not a build with a missing option — it is a build that cannot run a single
  flow. The default must be the build that can.
- **C buys a contract for a case the default closes.** A versionName token
  would let the runner detect a binary without the bypass and replace it. But
  every path that runs the suite installs first — the runner installs by
  default, and `tests::gate-maestro` installs before it sets `SKIP_INSTALL=1`
  — so the binary that reaches a flow is one the run just built. The token
  would be a second contract to keep in sync, for a case the default does not
  leave open.
- **The release build is unaffected.** The bypass is a test affordance, and a
  release build that starts signed in would be a shipped app with no auth.

## Consequences

- **Every debug install lands on the agenda.** `just android:install`,
  `just android:run`, `./gw :androidApp:installDebug` and the Maestro gate's
  install all produce a binary that starts signed in.
- **The instrumented tier needs nothing extra.** `scripts/run-android-ui-tests.py`
  assembles a debug APK, which now carries the bypass; `GRADLE_ARGS` is still
  the way to pass a class filter, not the way to make the run possible.
- **A developer who wants the real auth screen asks for it**:
  `-PTEST_AUTH_BYPASS=false`. That is the only way to turn the bypass off.
- **The Maestro gate states the property at the call site as well.** The
  default and the call site are two independent mechanisms: a build that stops
  carrying the bypass by default must not silently produce a suite that
  measures a sign-in screen.
- **The old ADR's consequence is superseded.** It said a developer assembles
  the APK themselves to get the real auth stack; now the default is the other
  way round, and the developer asks for the exception.

## Links

- `androidApp/build.gradle.kts` — the field, per build type
- `.just/tests/mod.just` — `tests::gate-maestro` and `tests::ui-maestro`
- `docs/testing/android-instrumentation-runbook.md` — the local runs
- `2026-10-20-the-local-e2e-run-measures-the-binary-ci-measures.md` — the
  decision this one completes

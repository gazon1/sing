---
title: "The local E2E run measures the binary CI measures"
date: 2026-10-20
status: accepted
tags: ["architecture", "e2e", "maestro", "tooling"]
---

## Context

The local Maestro runner installed the debug APK with

    ./gw :androidApp:installDebug -Pandroid.device=<serial> --quiet

while the E2E workflow assembles the same APK with

    ./gradlew :androidApp:assembleDebug -PTEST_AUTH_BYPASS=true

The property is not a detail. `BuildConfig.TEST_AUTH_BYPASS` is what makes
`SingularityApp` load `testAuthModule()` instead of the real auth stack
(`androidApp/src/main/kotlin/com/singularity/todo/SingularityApp.kt:106`). Without
it the app opens on **"Connect your server"** — the Supabase sign-in screen —
and no shell is behind it.

So the local run installed a binary whose first screen is a server
configuration form, and then asserted about the agenda behind it. Every flow
that reached the shell waited for `nav_tab_today` until it gave up: 45 seconds
per flow, then

    Assertion is false: id: nav_tab_today is visible

The suite looked broken. It was not broken — it was measuring a different
application than the one CI measures, and the difference showed up as a
timeout on the first assertion of every flow.

The E2E flows are written *for* the bypass build, and say so: `auth/02` starts
with "the app is signed out", reached through the stub's own sign-out, because
"the stub starts every process signed in". A build without the stub cannot run
those flows at all; it can only fail them.

## Idea

- **A.** Leave the install as it is and remember to pass the property by hand.
- **B.** Make the local install carry the same property the workflow's build
  does, so the two assemble the same binary.
- **C.** Make the property opt-in through an environment variable, and default
  it off.

## Decision

**B** — `gradle_install_debug` passes `-PTEST_AUTH_BYPASS=true`.

The local runner is a different entry point to the *same* tier, and a tier that
runs two binaries is not one measurement. The property belongs to the build the
tier needs, not to the invocation that happens to trigger it.

## Rationale

- **A measurement that depends on a flag someone remembers to type measures
  the person, not the code.** The runner already owns "which device", "which
  APK", and "is the installed APK this checkout's build" — the last of them is
  checked against the git SHA and refuses to run otherwise. The auth bypass
  belongs in the same place: with the build, not with the invocation.
- **The flows themselves require it.** `auth/02`, `auth/06` and `auth/12` are
  written against the stub. There is no build of these flows that works
  without it, so a runner that installs a build without it cannot run the
  suite it was asked to run.
- **C makes the wrong thing the default.** An environment variable would let
  the local runner install a build that cannot run the suite, which is exactly
  the defect this removes. If a future tier needs the real auth stack, it
  needs its own runner, not a flag that silently changes what the suite
  measures.
- **CI is the reference.** The workflow's `build-apk` job is the one place the
  E2E APK is assembled, and every shard installs that artifact. The local
  runner assembling the same thing is the smallest change that makes the two
  comparable.

## Consequences

- **A local run and a CI run now measure the same binary.** The property is on
  the install command, so there is no way to run the local suite against a
  sign-in screen by accident.
- **The install is a different build than the one a developer has on their
  device.** A developer who wants the real auth stack still assembles the APK
  themselves, without the property; the runner's install is only what the
  E2E tier uses.
- **The provenance check still applies.** The property does not change the
  git SHA the APK carries, so "is the installed binary this checkout's build"
  remains a question with an answer.
- **`scripts/tests/test_maestro_device_failure.py` asserts the property is on
  the command line**, because a flag that silently disappears is a suite that
  silently stops measuring anything.

## Links

- `scripts/src/emulator/maestro/_device.py` — `gradle_install_debug`
- `.github/workflows/e2e.yml` — the `build-apk` job and its property
- `androidApp/src/main/kotlin/com/singularity/todo/SingularityApp.kt` — where
  the property is read
- `Maestro/flows/auth/02-signin-wrong-password.yaml` — a flow written for the
  stub
- `2026-10-20-a-dead-device-is-written-down-in-the-report.md` — the other
  measurement defect of the same run

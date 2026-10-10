# ADR: AppTracer upload policy — release builds must not upload from developer machines

**Date:** 2026-10-10
**Status:** OPEN
**Issue:** #387

## Context

The AppTracer SDK integration had an uncontrolled upload condition: a release build assembled
on a developer machine that happened to hold `TRACER_APP_TOKEN` and `TRACER_PLUGIN_TOKEN`
would upload to the production backend, including mapping files, with no gate or opt-in.

Debug builds were intentionally always-on for local verification of the #134 integration
checklist. Release builds had no equivalent control — they simply inherited the default
token-based `isDisabled` check from `defaultConfig`, which only disables when tokens are
completely absent.

## Decision

**Release builds must not upload from developer machines. Release uploads are only acceptable
in CI (GitHub Actions, where `CI=true` is set).**

The structural enforcement is:

```kotlin
create("release") {
    isDisabled = !tokensPresent || !isCi
}
```

Where `isCi` is `providers.environmentVariable("CI").orElse("").get() == "true"`.

Debug builds remain always-on when tokens are present:

```kotlin
create("debug") {
    isDisabled = !tokensPresent
}
```

## Rationale

- The first crash report from a user's device would otherwise be the first time anyone
  inspects this configuration — which is precisely the risk #134 describes.
- A comment in the build file is not a gate: decisions encoded as comments get silently
  reverted by the next person tidying up the build. Structural enforcement cannot be
  reverted without a deliberate code change.
- `CI` is set by GitHub Actions for every workflow job. This repository uses only GitHub
  Actions (no other CI providers appear in `.github/workflows/`), so it is a reliable
  sentinel for "this is a CI run".
- The `assertTracerBuildUuid` task (#383) runs before any upload and fails the build if
  the SDK is misconfigured, so the CI upload is also gated on correct SDK initialisation.

## Consequences

- A developer with tokens on their machine will see release builds that do not upload.
  This is correct behaviour — release uploads must be a deliberate CI act.
- Debug builds on the same machine will still upload. This is also correct: debug is the
  pre-release verification path (#134) and is the documented intent.
- If the repository is ever migrated to a different CI provider, the `isCi` check must be
  updated to that provider's equivalent environment variable.

## Next step

Only after `assertTracerBuildUuid` is green does the #134 verification mean anything.
An emulator cannot prove delivery to the backend — #134's three checks need a real device
or a real network path to the AppTracer service, which is a separate measurement to
schedule.

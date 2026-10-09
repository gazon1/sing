---
status: accepted
date: 2026-10-10
deciders: mavis
gh: https://github.com/gazon1/sing/issues/364
---

# Emulator device-sharing between e2e and instrumented test workflows [#364](https://github.com/gazon1/sing/issues/364)

## Context

`e2e.yml` (Maestro flows) and `android-device-tests.yml` (Espresso instrumented tests) both
launch their own Android emulators on `ubuntu-24.04` runners via `reactivecircus/android-emulator-runner`.
Both workflows were in separate concurrency groups:

```
e2e.yml:          e2e-${{ github.event.pull_request.number || github.ref }}-${{ github.event_name }}
android-device-tests.yml:  android-device-${{ github.event.pull_request.number || github.ref }}-${{ github.event_name }}
```

Different concurrency groups = no mutual exclusion. Both workflows could be scheduled on the
same GitHub Actions runner simultaneously, causing fights over `/dev/kvm`, adb device access,
and adb install conflicts. The result was 16 false failures as the Gradle build reinstalled
the APK while Maestro was mid-execution.

## Decision

Introduce a shared concurrency group `emulator-${{ github.event.pull_request.number || github.ref }}`
that spans both `e2e.yml` and `android-device-tests.yml`. Both workflows use the same group key,
so GitHub Actions guarantees at most one of them runs at a time for a given branch/PR.

The `android` job in `ci.yml` only compiles (`:androidApp:assembleDebug`), it does not use an
emulator, so it is not included in this group.

## Consequences

- `e2e.yml` and `android-device-tests.yml` can no longer run concurrently on the same ref.
- A push to main that triggers both (smoke on push for e2e, smoke on push for android-device-tests)
  will have them run sequentially instead of fighting over the runner's KVM device.
- The sequential ordering is determined by GitHub Actions queue order; no explicit priority
  is set. If ordering matters, a workflow_dispatch trigger for one of them can be used.

## References

- `e2e.yml` concurrency block (modified)
- `android-device-tests.yml` concurrency block (modified)
- `reactivecircus/android-emulator-runner@v2`
- [GitHub Actions concurrency docs](https://docs.github.com/en/actions/using-workflows/workflow-syntax-for-github-actions#concurrency)

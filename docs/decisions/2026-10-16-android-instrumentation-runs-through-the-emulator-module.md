---
created: 2026-10-16
tags: [ci, testing, emulator, android]
status: accepted
---

# The Android instrumentation job runs its device through `scripts/src/emulator`

## Context

`android-device-tests.yml` — the only job that ever ran
`:androidApp:connectedDebugAndroidTest` — drove the device through
`reactivecircus/android-emulator-runner@v2`, while every other surface in the
repository drives it through `scripts/src/emulator`. Two device lifecycles, one
repository.

The split was not cosmetic. The action owns the emulator's whole lifetime: it
boots inside its own `script:` step and kills it the moment that step returns,
so the workflow could only ask it to *run* a command. Everything the repository
knows about acquiring a device — `acquire_device`'s startup lock, the stale-qemu
cleanup, the boot-readiness probe, the three start-up attempts, the distinct exit
codes for crashed / timed-out / locked — was unreachable from CI. The workflow's
own diagnostics show the cost: `Test idle failed … PERSISTENT`, `adb … exit code
1`, a `default_boot` snapshot that never appeared. Each is a workflow waiting on
a device whose lifecycle it cannot observe, on a boot the action performs with
its own timeout.

The reverse split was worse and was already shipped. `scripts/run-android-ui-tests.py`
— the repo's single entry point for this run, and what `just` invokes locally —
asked the *device* for provenance:

```python
installed_sha = apk_sha_from_device(device)   # dumpsys package versionName
```

On a fresh AVD nothing is installed, so the probe reads nothing and the script
fails with "the installed binary is unidentifiable" about a binary that does not
exist yet. The property worth asserting is that the file about to be handed to
`connectedDebugAndroidTest` is this checkout's build, and that is a property of
the file.

## A failure that was never a device failure

Moving the job onto one command made it runnable locally, and the first local
run answered a question the workflow had been asking for weeks with
`Test idle failed … PERSISTENT`. All three UI classes failed:

```
NavigationSmokeTest.app_launches_and_all_tabs_are_reachable
  java.lang.AssertionError: Today tab should be visible after cold start
CreateTaskFlowTest  → NullPointerException on UiObject2.click()
```

The device was fine, the app was fine, and the message named the shell. The app
was opening on the **sign-in screen**: `SingularityApp` installs the real
repository unless `BuildConfig.TEST_AUTH_BYPASS` is set, and the workflow never
set it. `e2e.yml` sets `-PTEST_AUTH_BYPASS=true` and writes placeholder Supabase
credentials into `local.properties` for exactly this reason; the instrumentation
workflow had neither, so every assertion was made against a screen in front of
the one it described. A device-level gate can be green for a week while the
thing it is a gate for is unreachable, and "the emulator is flaky" is the
diagnosis that survives being wrong.

The workflow now does what `e2e.yml` does — `local.properties`, then
`GRADLE_ARGS=-PTEST_AUTH_BYPASS=true`, applied to both the APK under test and the
test task through one place.

## Idea

- **A.** Keep the action for booting, keep the script for running.
- **B.** Create the AVD with `sdkmanager`/`avdmanager` and let
  `scripts/src/emulator` own booting, readiness and recovery. Read provenance from
  the APK file. Put the zero-tests assertion where the report is already parsed.
- **C.** Drop the action from both `android-device-tests.yml` and `e2e.yml` at
  once.

## Decision

**B**, for the instrumentation job.

- The workflow creates the AVD itself (`sdkmanager` for the image,
  `avdmanager create avd --device pixel_6`) and puts `emulator`,
  `platform-tools` and `build-tools` on `PATH`. Then one command runs the whole
  run: `python3 scripts/run-android-ui-tests.py`, which calls
  `python3 -m emulator` to acquire a device and only then builds and tests.
- `EMULATOR_EXTRA_FLAGS` carries the flags the action used to pass
  (`-no-window -gpu swiftshader_indirect -no-audio -prop persist.sys.locale=ru-RU`),
  so the rendering configuration is unchanged and
  ADR `2026-09-28-emulator-gfxstream-colorbuffer-segv` still holds: software
  rendering is the supported configuration, not a flag to tune.
- Provenance is read from the APK with `aapt dump badging`.
- The "at least one test executed" check moves from the workflow into the script.
  `connectedDebugAndroidTest` exits 0 when the app dies before the first
  assertion; two shell implementations of that rule in two files would drift.
- The device is re-checked after the build and after the run. A device that dies
  mid-run and a failing assertion produce the same Gradle exit code, and the
  emulator log tail is printed instead of a test tail describing the wrong cause.

The AVD snapshot cache is dropped. It existed to shorten a cold boot that the
action performed with its own flags; the image download now happens through
`sdkmanager`, and a cache whose contents the action created is not evidence about
anything the workflow does.

## Rationale

- **One device primitive, one set of failure modes.** `ensure-emulator` already
  distinguishes crashed / timed-out / locked / not-ready, and it is what every
  local run and every agent goes through. CI having a second, weaker path is how
  the same symptom gets diagnosed two ways.
- **The verification ran where the run happens.** A rule that is only enforced in
  CI and not locally is enforced nowhere: the local run took a different script
  path from the workflow, and the workflow's step could not be executed by hand.
- **A failing probe must not be a crash.** `try_run` caught `OSError` and let
  `subprocess.TimeoutExpired` escape, so a wedged `adb` ended the run with a
  traceback naming a subprocess instead of a verdict naming the device — on
  exactly the runs where the unresponsive device *is* the finding.

## Consequences

- `android-device-tests.yml` no longer depends on a third-party action to boot a
  device. `e2e.yml` still does: Maestro's own device-recovery loop is written
  against the action's lifecycle, and changing it is a separate change with its
  own risk.
- The job is longer on a cold runner: `sdkmanager` downloads the system image
  rather than restoring a cached AVD.
- `run-android-ui-tests.py` gains a dependency on `aapt` from the newest installed
  build-tools. Without it the provenance check fails loudly rather than being
  skipped, which is the same property every other gate here has.

## Links

- `.github/workflows/android-device-tests.yml`, `scripts/run-android-ui-tests.py`,
  `scripts/src/emulator/_startup.py`
- [2026-10-13-ensure-device-acquires.md](2026-10-13-ensure-device-acquires.md)
- [2026-09-29-emulator-launch-recipe.md](2026-09-29-emulator-launch-recipe.md)
- [2026-09-28-emulator-gfxstream-colorbuffer-segv.md](2026-09-28-emulator-gfxstream-colorbuffer-segv.md)
- [2026-09-29-emulator-crash-recovery-runner.md](2026-09-29-emulator-crash-recovery-runner.md)
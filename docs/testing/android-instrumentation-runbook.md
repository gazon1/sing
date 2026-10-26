# Android instrumentation runbook — the device tier

The device tier is `:androidApp:connectedDebugAndroidTest`: the only verification
surface in this repository that runs the app on a real Android runtime. It is also
the slowest and the flakiest, which is why it does not gate a pull request.

This page is the work order for that tier. The Maestro side of the Android story
is a different file — `android-tier-runbook.md` — because it answers a different
question: not "does the app run" but "did the scenario matrix record a result".

## The two tiers

| Tier | What runs | When | Blocks a merge? |
|---|---|---|---|
| **smoke** | one class (`AppStartupTest`) | every push to a pull request | **no** — advisory |
| **full** | the whole instrumented suite | nightly (03:00 UTC), push to `main`, tag `v*`, manual dispatch, and any pull request labelled `run-device-tests` | it fails the job, but the job is not a required check |

The smoke tier exists because "the tier only runs at night" is a defect wearing the
costume of a decision: between two nightlies, a pull request that breaks the app on
startup is indistinguishable from one that does not. The smoke tier answers that
question on every push, in the time it takes to boot an emulator, and it is
advisory because the emulator is the flakiest thing in this CI — a flaky gate is
worse than no gate, because people learn to ignore it.

The full tier is the one a human asks for. It is the one whose report the floor
(`config/docs/instrumented-test-floor.txt`) is measured against.

## The `run-device-tests` label

The full tier on a pull request is requested by adding a label. It is a label
rather than a `/run` comment because GitHub Actions can trigger on a label
(`pull_request: types: [labeled]`) and cannot trigger on a comment.

**Apply it:**

```bash
gh pr edit <number> --repo gazon1/sing --add-label "run-device-tests"
```

**Remove it when you are done** — a pull request that keeps the label starts the
full suite on every push to it.

### The label is repository metadata, and metadata can go missing

A label is not a file in this repository. It lives in the repository's settings, it
is not in any commit, and it can be deleted by anyone with write access — or by
an export/import of the repository settings, or by a fresh clone of the same
project into a different account. When it is gone, the workflow does not fail: the
`plan` job computes the tier from the labels it can see, finds none, and runs the
smoke tier. A pull request can therefore be waiting for a full run that will never
start, and the only symptom is that the full tier never appears.

**Check that it exists:**

```bash
gh label list --repo gazon1/sing --search "run-device-tests"
```

**Recreate it if it does not:**

```bash
gh label create "run-device-tests" --repo gazon1/sing \
  --description "Run the full Android instrumented suite on this pull request" \
  --color "0E8A16"
```

The colour is the one the label was created with; it is not meaningful, but a
recreated label with a different colour looks like a different label in the
pull request's sidebar, and a person deciding whether to add it will notice that
before they notice that it is missing.

## Reading a failure

A red device run leaves three artifacts, and they are uploaded unconditionally —
not only when something broke. A report that exists only after a failure cannot
answer "did `NavigationSmokeTest` pass on `abc1234`", which is exactly the
question a nightly run gets asked the next morning.

| Artifact | What it holds |
|---|---|
| `android-instrumentation-reports-<run_id>` | the JUnit XML and the HTML report of the run |
| `android-failure-diagnostics-<run_id>` | the failure bundle: screenshot, screen tree, step log, environment |
| `android-failure-logcat-<run_id>` | logcat, written when the run failed or executed zero tests |

The failure bundle is the one that answers "what was on the screen". It is
written by the test that failed, travels through the shell's directory on the
device (AGP uninstalls the app under test when the run ends, and an app's private
directory is deleted with it), and is pulled into `androidApp/build/diagnostics/`
by `scripts/run-android-ui-tests.py`.

The step log inside it is the record of what the test *did*, not only of what it
waited for: every click, every text entry and every back press is written before
the action is taken, so a failure names the last thing the test did rather than
leaving it to be guessed from the screenshot.

### "exited 0 having run 0 tests"

`connectedDebugAndroidTest` exits 0 when the app under test dies before the first
assertion: no test ran, nothing failed, and the report is a green line. The
script `run-android-ui-tests.py` treats that as a failure and says so, and it is
the reason the step refuses to call a run a pass on the strength of an exit code
alone.

## Reproducing a run locally

```bash
ensure-emulator                                  # the repo's single recovery primitive
ANDROID_SERIAL=emulator-5554 python3 scripts/run-android-ui-tests.py
```

The auth bypass is not optional. Without `-PTEST_AUTH_BYPASS=true` the app opens
on the sign-in screen, and every assertion about the shell is made against a
screen in front of the one it describes:

```bash
GRADLE_ARGS="-PTEST_AUTH_BYPASS=true" python3 scripts/run-android-ui-tests.py
```

The same property has to reach the *build* as well as the test, which is why the
script appends `GRADLE_ARGS` to both halves of the run instead of letting the
caller pass them separately.

To run one class — the smoke tier, locally:

```bash
GRADLE_ARGS="-PTEST_AUTH_BYPASS=true -Pandroid.testInstrumentationRunnerArguments.class=com.singularity.todo.ui.AppStartupTest" \
  python3 scripts/run-android-ui-tests.py
```

## The floor

The full tier's size is gated: `scripts/check-instrumented-test-floor.py` reads
the run's report and fails when the suite is smaller than the floor records. A
suite that runs on a schedule is a suite whose size nobody watches — a deleted
test class leaves the run green, because the report lists the tests that are left.

The floor is a floor, not a target. It is raised in the same commit that adds
tests, and it is never lowered to make a run pass.

The smoke tier is not measured against it: the smoke ran one class on purpose,
so its report is below the floor by construction.

## What this tier does not prove

- **Nothing about the scenario matrix.** The device tier produces instrumentation
  reports, not Maestro scenario results. The android column of the matrix stays
  `not-run` until the flows carry a `scenario:` tag — see
  `docs/testing/android-tier-runbook.md`.
- **Nothing about the product on a real device.** The tier runs on an emulator.
  A real device has a real screen, real sensors and a real Play Store, and none
  of that is covered here.
- **Nothing about a run that did not happen.** A skipped workflow, a runner that
  never got a machine, a job that was cancelled — the artifacts say what ran.
  An empty artifact set is a measurement of "nothing ran", not of "everything
  passed".

## Real-device Maestro run with auth bypass

For running Maestro flows on a physical device with `TEST_AUTH_BYPASS=true`:

1. Build & install the bypass APK:
   `./gw :androidApp:assembleDebug -PTEST_AUTH_BYPASS=true` then
   `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
2. Wipe stale state (safe on a bypass build — no real session needed):
   `adb shell pm clear com.singularity.todo`.
3. Seed content: `adb shell am start -a android.intent.action.VIEW -d "todo-debug://seed?reset=content" com.singularity.todo`.
4. Unlock the screen (`adb shell input keyevent 82`) — the lock screen / notification
   shade otherwise covers the app and uiautomator/Maestro see only system UI.
5. Run: `maestro test <flow> --env RUN_ID=<id>`.

Gotcha: `launch-clean.yaml` uses `clearState: false` (for real-auth runs). On a bypass
build the persisted session is unnecessary and can mask the bypass, hence the `pm clear`.

## Links
- `docs/decisions/2026-10-07-the-instrumentation-tier-ran-in-no-ci-job.md` — why
  the tier is a separate workflow rather than a registry gate
- `docs/decisions/2026-10-20-android-instrumented-smoke-on-every-pr.md` — why
  the smoke tier is advisory and what it buys
- `docs/decisions/2026-10-20-android-failure-bundle-travels-through-the-shell.md`
  — why the bundle travels through the shell
- `.github/workflows/android-device-tests.yml` — the tier decision itself

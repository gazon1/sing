# Tasks — apptracer-integration

## Build wiring

- [x] Add the crash SDK version to the version catalog and declare the runtime dependency.
      No BOM: `platform()` is deprecated in a KMP source set (KT-58759) and only one module
      is consumed, so there is nothing to align.
- [x] Apply the SDK's Gradle plugin to the application module only — it injects resource
      values and uploads mappings, so it must target the application.
- [x] Read tokens through `providers.gradleProperty` / `providers.environmentVariable`, never
      a raw environment read, which the configuration cache snapshots and turns stale.
- [x] Keep the build green with no token: disable the SDK when the token is blank and tolerate
      upload failure.
- [x] Enable the resource-value build feature. The SDK embeds resources at build time and the
      current Android Gradle Plugin disables that feature by default; omitting it fails at
      runtime, not at build time.
- [x] Add the runtime dependency to the shared module's Android source set, and repeat it in
      the application module, which declares the SDK's configuration interface.
- [x] Confirm no hardcoded coordinates: run the version-catalog gate.

## The port

- [x] Declare the reporting port in shared common code with exactly two operations: report a
      failure under a grouping key, and add a breadcrumb.
- [x] Provide the inert implementation used as the default parameter value.
- [x] Implement the Android adapter. Wrap every SDK call: this runs on the error path, so an
      SDK failure that escaped would escalate a handled error into a process crash.
- [x] Implement the inert desktop adapter. The binding must still exist — a missing definition
      throws inside composition, which Compose retries every frame.
- [x] Bind the port as a singleton in both platform modules, following the convention every
      other port uses, and add it to the platform module's documented binding list.

## The funnel

- [x] Give the MVI base view model a trailing defaulted reporter parameter and report every
      failure that already passes through its error path. This is what covers all 34 existing
      call sites without touching them.
- [x] Prefer the error's own domain code as the grouping key, falling back to the fixed
      call-site label.
- [x] Declare the parameter explicitly in every view model that owns error call sites, and
      pass the resolved dependency in each one's binding. A defaulted parameter would restore
      the silent-drop failure mode with no compiler error.
- [x] Add a build-time check that fails when a component handling failures stops declaring the
      dependency, and a positive control so the check cannot pass by scanning nothing.
- [x] Report the same failures from the background paths: the sync engine's push and pull, the
      event-apply step, the outbox worker, the alarm receiver, and draft autosave and save.
- [x] Record the device and build context once at startup so each report carries it.

## Errors carry a code and a cause

- [x] Give the domain error type a stable code and an optional cause, both defaulted on every
      subtype so existing construction sites are untouched.
- [x] Stop the catch helper flattening exceptions to their message — it was discarding the
      stack trace at exactly the point where it would be read.
- [x] Test that the cause survives, and that every subtype still constructs from a bare
      message.

## Bundled defects

- [x] Redact the whole cause chain, and copy the original's frames onto the redacted copy so
      the log records where the failure happened rather than where it was redacted.
- [x] Extend the redaction test to assert on the frames and on a redacted cause — it asserted
      only on string rendering, which is why both defects shipped.
- [x] Guard each iteration of the calendar-sync collector, and make its start idempotent to
      match what its documentation already claimed.
- [x] Connect the previously dead device-info helper so reports carry build context.
- [x] Share one constant for the rolling-log file count between the writer and the exporter, so
      raising retention cannot silently truncate exported bundles.
- [x] Guard the writer's failure path against re-entering its own logging, which could
      deadlock the single write thread at process exit.
- [x] Correct the application module's static-analysis source set, add the module to the lint
      command, and make it enforcing with a baseline.
- [x] Replace references to a crash dashboard and a log field that do not exist, in the
      debugging skill and the observability record.

## Background failure floor and the guard behind it

- [x] Add a `CoroutineExceptionHandler` to every `createBackgroundScope()` actual, with a
      replaceable target and a logged (never silent) uninstalled default.
- [x] Install the target from `SingularityApp.onCreate` with the Koin-resolved
      `CrashReportingPort`; skip `CancellationException`; never rethrow.
- [x] Cover the handler with tests: scope composition, report-and-survive, cancellation, the
      uninstalled default, and the issue key.
- [x] Widen the wiring check from "uses the funnel" to "can fail", and add a check that no
      component builds a scope that bypasses the handler.
- [x] Connect the 15 components the widened check found, replacing hand-rolled failure handling
      with the funnel where the interface state allows it.
- [x] Record the decision, including the refactor that would make the global handler
      unnecessary.

## Verification

- [x] Every module compiles with the change.
- [x] The full shared test suite passes.
- [x] The unwired-surface audit passes with the new no-op adapter allowlisted.
- [x] Static analysis passes across the shared module, the desktop module, and the now-enabled
      application module.
- [x] An Android build succeeds with no credentials configured.
- [ ] On a device with credentials: a handled failure arrives grouped under the expected key.
- [ ] On a device with credentials: a forced crash arrives *and* the final lines of the local
      log are on disk, confirming the uncaught handler delegates rather than swallows.
- [ ] On a device: inspect a report payload and confirm no task title, note body, token, or
      email is present.
- [ ] Turn debug-build uploading off when release traffic starts.

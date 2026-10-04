# apptracer-integration

## What

Report Android crashes, main-thread stalls, and handled failures to AppTracer, and route the
error paths the app already has into it.

The app had no crash reporting at all. A fatal crash surfaced as a one-star review; a handled
failure — a sync that gave up, a save that did not happen — left no trace anywhere, so the app
degraded quietly and nobody knew. The documentation made this worse: two decision records and
an agent skill instructed readers to open a Firebase Crashlytics console that was never
configured, and to filter logs by a field that has never existed.

## Why

The error surface already existed and was well built. What was missing was somewhere for it to
go. Every handled failure in the UI already funnels through a single point in the shared MVI
base, and background failures already log consistently in the sync chain. Both were reachable
with a small, surgical change rather than an audit of the whole codebase.

Three defects found alongside it are bundled because they sit in the same subsystems and would
each have cost a second review later:

- **A credential leak in log redaction.** A failure's cause chain was passed through
  unredacted, so a secret nested in a cause message reached the device log and the system log
  in the clear. The same code path also discarded the original stack trace — every recorded
  failure pointed at the redaction code rather than the failure. The existing test asserted
  only on string rendering, so it passed green throughout.
- **The application module was never linted.** Its static-analysis configuration pointed at a
  source directory that does not exist, and the lint command did not invoke it at all. The
  module this change edits most was the one nobody was checking.
- **A background collector could kill the process.** Calendar sync ran on a scope with no
  failure handler and made database reads inside its loop, so a storage error reached the
  global handler — or, more quietly, ended sync for the rest of the session with no symptom.

## How

A crash-reporting port is introduced, following the existing convention for every other
platform port: an interface in shared code, one implementation per platform, bound as a
singleton in both platform dependency-injection modules. The desktop implementation is inert —
the crash SDK is Android-only, and desktop keeps its file log.

The MVI base view model takes the reporter as a trailing defaulted parameter and reports every
failure that passes through its error path. All thirty-four existing call sites are covered by
that one change. The ten view models that own those call sites declare the parameter explicitly
and receive it from the graph, so the dependency is visible in the constructor and in the
wiring rather than hidden behind a default; a build check fails if one of them stops doing so.

Errors carry a stable machine-readable code, and a caught exception keeps its cause. The second
of those fixes a live defect: the catch helper flattened every exception to its message, losing
the stack trace at exactly the point where it would be read.

Credentials never enter a report. The grouping key is always a fixed code or a fixed call-site
label, never an error message.

## Out of scope

Desktop reporting, heap dumps, disk usage, profilers, native crash collection. The silent
failure sites in the data layer — around a hundred and forty of them — need a separate
per-site review. The reported exception's message is not sanitised; the sites being instrumented
produce identifiers and labels rather than user prose, and that should be revisited if a report
is ever seen carrying content.

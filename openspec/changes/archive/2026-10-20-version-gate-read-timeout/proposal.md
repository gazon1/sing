# version-gate-read-timeout

Issue: — · Related: `docs/decisions/2026-10-20-version-gate-read-timeout.md`

## What

The version gate is a full-screen splash in front of the whole app. Its state stays
`Checking` until the remote-config read resolves, and every user waits in front of it.

The gate already had a policy for a read that **fails**: report it, breadcrumb the bypass,
and evaluate the default snapshot — so a broken config server cannot brick every install
behind a gate nobody can pass.

A read that never **returns** was the same failure with a longer fuse, and the policy did
not name it. Nothing throws, so nothing reports, so the app never gets past the splash and
nothing anywhere says why. The read is `remoteConfigPort.snapshot()` — a cached snapshot read
out of Room — so the culprit does not have to be the network at all: a database created on
first launch, or a locked multi-instance database, is enough.

This change gives the read a deadline and treats a missed deadline exactly like a failed
read: reported, breadcrumbed, and admitted on the defaults.

1. The read is bounded by a deadline (`readTimeoutMs`, 5 s by default).
2. A read that misses the deadline is a failure with its own machine-shaped code
   (`gate.remote_config_read_timeout`), so it stays a separate row in the report.
3. The bypass breadcrumb names the reason: a read that *threw* and a read that *timed out*
   are two different investigations.
4. The `Check again` path gets the same deadline as the first check.

## What not to do

- **Do not give the gate a second failure path.** The timeout goes through the same funnel
  as a thrown read: one decision point, one ordering guarantee (the breadcrumb is written
  before the report, so it rides with it), one fail-open outcome. A parallel path would mean
  a second place where a bypass can be recorded — the exact drift the `onBeforeReport`
  design was built to prevent.
- **Do not block the user on a slow config.** The trade-off is recorded honestly in the ADR:
  a user below the minimum version is admitted while the read is slow, and the window is the
  timeout and nothing longer.
- **Do not report the timeout as `error.unknown`.** `runCatchingResult` flattens any
  non-`AppError` throwable, and the timeout would then be indistinguishable from a bug in
  the report.

## Impact

- Affected capability: `version-gate` (new).
- Affected code: `feature/gate/presentation/viewmodel/AppVersionGateViewModel.kt`.
- No behaviour change for a read that answers in time: the gate admits exactly as before.

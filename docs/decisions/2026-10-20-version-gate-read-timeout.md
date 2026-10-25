---
title: "The version gate fails open on a broken config server, and hung on a slow one"
date: 2026-10-20
status: accepted
tags: [reliability, remote-config, observability, architecture]
---

## Context

The version gate is a full-screen splash in front of the whole app. Its state stays
`Checking` until the remote-config read resolves, and every user waits in front of it.

The gate already had a policy for a read that **fails**: report it, breadcrumb the
bypass, and evaluate the default snapshot — so a broken config server cannot brick
every install behind a gate nobody can pass
(`2026-10-05-background-failure-handler-and-the-guard-it-behind.md`).

A read that never **returns** is the same failure with a longer fuse, and the policy
did not name it. Nothing throws, so nothing reports, so the app never gets past the
splash and nothing anywhere says why. The read is `remoteConfigPort.snapshot()` — a
cached snapshot read out of Room — so the culprit does not have to be the network at
all: a database created on first launch, a locked multi-instance database, is enough.

This is the same class of defect that made the Android instrumented suite red on `main`
for days: the UI tests waited for the shell and the app never got there
(`2026-10-20-the-instrumented-ui-suite-waits-for-the-shell.md`).

## Idea

- **A.** Leave the read unbounded. The status quo: the app waits for the config, and
  waits forever if the config does not come.
- **B.** Give the read a deadline. A read that misses it is a failed read: reported,
  breadcrumbed, evaluated against the defaults — the existing policy, applied to the
  failure mode it did not name.
- **C.** Stop gating the startup. Read the config in the background, open the app on the
  defaults, and block later if the config says the version is too old. The gate stops
  being a gate.
- **D.** Fail **closed** on a deadline. A version gate that cannot read its config
  blocks everyone.

## Decision

**B** — a deadline on the remote-config read, and a timeout that fails open.

- `AppVersionGateViewModel` takes a `readTimeoutMs`, defaulting to 5 seconds. The read
  runs under it; a read that misses it is turned into a failure and handled by the
  existing funnel: reported, breadcrumbed, admitted on the defaults
  (`Allowed(defaults)`).
- The timeout is a failure the gate raises **itself**, so it carries its own
  machine-shaped code, `gate.remote_config_read_timeout`. That is what keeps it a row of
  its own in the report instead of one line inside the general "the config could not be
  read" bucket.
- The breadcrumb names the reason. "The gate was let through" and "the gate was let
  through because the read never came back" are two different investigations: the first
  points at the config server, the second at whatever the read was waiting on. The
  thrown read keeps its existing wording.
- The `CheckAgain` path gets the same deadline. That screen is a full-screen splash
  too, and a refresh that never returns leaves the user on `Checking` with nothing left
  to press but the button they already pressed.

## Rationale

- **The gate's job is to stop a user on a too-old version. A hang stops everyone, and
  stops them from using the app at all.** Failing open costs a bounded amount — a
  client below the minimum is admitted while the read is slow — while failing closed
  costs everything. The asymmetry is the whole argument.
- **The policy already existed; the deadline extends it rather than inventing a second
  one.** A timeout and a thrown read are the same event from the gate's point of view:
  the config is not available, so the defaults are the answer. Treating them differently
  would mean two failure paths, two records and two decisions, for one outcome.
- **The record has to name the reason.** Failing open is invisible by construction — the
  end state is byte-for-byte what a healthy read produces. A record that says only
  "bypassed" makes a slow disk indistinguishable from a broken config server, and the
  two are fixed in opposite places.
- **C is a product decision, not a reliability fix.** Letting a user on a too-old
  version into the app is what the gate exists to prevent; an advisory gate is a
  different product, and it belongs in a change that says so.
- **D inverts the asymmetry** for a failure that a healthy install never sees.

## Consequences

- The gate can no longer hold the app indefinitely. After at most `readTimeoutMs` the
  read is given up on, the failure is reported under
  `gate.remote_config_read_timeout`, and the gate evaluates the defaults — the app
  opens.
- **The trade-off, stated honestly:** a user whose version is below the configured
  minimum is *admitted* when the read is slow. The window is the timeout and nothing
  longer. That is the deliberate price of never leaving the whole app behind a splash,
  and it is the same price the existing fail-open policy already pays for a broken
  server.
- A timed-out read is cancelled. A read that ignores cancellation — a blocking driver
  call — still delays the gate past the deadline, because the deadline can only end a
  cooperative read.
  - **Superseded the same day, by the port's own bound.** The limit above is real for
    the gate's deadline, and it is the reason the bound was moved into the port
    (`RemoteConfigPort.READ_TIMEOUT_MS`): the port runs the read in a scope of its own
    and the caller waits on a promise, which can be given up on whether or not the work
    behind it finishes. The gate's deadline is now the *outer* of two layers, and both
    use the same number. See `openspec/specs/version-gate/spec.md`, REQ-3, and
    `openspec/changes/archive/2026-10-20-android-test-hardening/`.
- The timeout is a constructor parameter rather than a constant, so a test injects a
  small one instead of waiting five seconds, and the production value stays a single
  named constant.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/gate/presentation/viewmodel/AppVersionGateViewModel.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/gate/presentation/viewmodel/AppVersionGateViewModelTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt` — the same
  deadline shape, for a session restore that must not leave the app on a spinner
- `2026-10-05-background-failure-handler-and-the-guard-it-behind.md` — the fail-open policy
  this extends
- `2026-10-20-the-instrumented-ui-suite-waits-for-the-shell.md` — the same defect, seen
  from the test side

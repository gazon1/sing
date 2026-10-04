---
title: CI action versions and a pinned runner image
date: 2026-10-05
status: accepted
tags: [ci, tooling, process]
---

# CI action versions and a pinned runner image

## Context

Four jobs on PR #31 emitted deprecation warnings on every run:

- `Node.js 20 is deprecated ... forced to run on Node.js 24`, naming
  `actions/checkout@v4`, `actions/setup-java@v4`, `actions/setup-python@v5`,
  `actions/upload-artifact@v4` and `gradle/actions/setup-gradle@v4`.
- `setup-java v4 is deprecated and will no longer receive updates. Please migrate
  to actions/setup-java@v5.`
- `"The ubuntu-latest label will migrate to Ubuntu 26 beginning October 19, 2026"`

A fourth warning in the same list, `No files were found with the provided path:
shared/build/reports/kover/xml-report.xml`, was already fixed on this branch — it
predates it.

The three real decisions were which major to jump to, whether the same fix applies
to the two Maestro workflows, and the runner image.

## Idea

Read each action's release notes before bumping, to find out whether the version
that removes the warning is safe for this repository or whether the interesting
change is several majors further on.

## Decision

**Action versions — go to the current major, not the minimum that silences the
warning.** `checkout@v7`, `setup-java@v6`, `setup-python@v7`,
`upload-artifact@v7`, `gradle/actions/setup-gradle@v6` in all four workflows.

Checked rather than assumed. For each family, the first Node 24 major is a pure
runtime bump with nothing else in it:

- `checkout` v5.0.0: "Update actions checkout to use node 24" — the entire release.
- `setup-java` v5.0.0: "Upgrade to node 24" under a Breaking Changes heading that
  lists nothing else. v6 adds `jdkFile` → `jdk-file` (deprecated alias, unused
  here) and an ESM migration its own release notes call not user-facing breaking.
- `setup-python` v6.0.0: "Upgrade to node 24", again the whole release.
- `upload-artifact` v5.0.0: "supports Node v24.x ... not a breaking change per-se
  but we're treating it as such". v6 requires Actions Runner ≥ 2.327.1, which
  GitHub-hosted runners satisfy. v7 adds an opt-in `archive: false` direct-upload
  mode; the default zipping behaviour is unchanged, and nothing here sets it.
- `gradle/actions` v5.0.0: "Upgrade to node 24", the whole release.

So the later majors carry no behavioural change for this repository, and stopping
at the first Node 24 major would mean repeating this task next quarter, when v5
and v6 get their own deprecation notices. `reactivecircus/android-emulator-runner`
stays on `@v2` — v2 is already its current major.

**All four workflows, not just the two that warned.** The Maestro workflows pin
nothing and share the same deprecated actions, so leaving them would reproduce
every warning on their next run. They are scheduled rather than per-PR, which is
why the warnings went unnoticed there.

**Runner image — pin `ubuntu-24.04`, deliberately.** This is the one choice with
an asymmetric downside, so it is a decision rather than a detail. GitHub migrates
`ubuntu-latest` to Ubuntu 26.04 between 2026-10-19 and 2026-11-19, and pinning is
one of the two mitigations the notice offers. The alternative is switching to
`ubuntu-26.04` now and testing the migration in the same motion that silences the
warning about it.

26.04 is not a small step: kernel 6.17 → 7.0, systemd 255 → 259. The JVM is not
involved — `setup-java` installs Zulu 21 on both images, and the image's default
Java stays 17 either way. The difference is entirely in the system layer, which is
where this repository runs `:androidApp:assembleDebug` and two emulator workflows
against Reactive Circus. `2026-09-29-emulator-crash-recovery-runner.md` already
records that the host's graphics stack fails periodically under the emulator and
has no cure, so an unexplained kernel-level regression there would be expensive to
attribute. The known-good image is the one these paths were actually validated on.

Each workflow carries a comment saying the pin is deliberate and will not come
back on its own, because `ubuntu-latest` looks like a stale label rather than a
choice, and the next person to see it will read it as an oversight.

## Rationale

Warning-clean is not the goal; a warning that describes a real deprecation is the
goal to remove, and it is removed by the same edit in both cases. For the actions,
the notes show the current major is a drop-in, so there is nothing to gain by
stopping early. For the runner, the notes show the migration is a real platform
change landing in under a month, and there is a documented cost to being an early
adopter on the one surface that cannot be exercised locally.

Pinning also has a known expiry that needs stating plainly: it is a deferral, not
a fix. The migration proceeds on GitHub's schedule regardless, and when this pin
is eventually lifted the Android and emulator paths get validated for the first
time at that moment.

## Consequences

- All four warnings go away; no workflow references a deprecated major.
- `ubuntu-24.04` stops receiving image updates. Nothing in this repository can
  discover the date the pin must be revisited, so the ADR is the record and
  2026-11-19 is the date by which the question comes back.
- Lifting the pin to `ubuntu-26.04` is a real migration and should be verified on
  the Android assemble and both emulator workflows before it is treated as routine.
- The warnings in the original report were all from one point in time. A new
  deprecation will not be noticed until a run happens to emit it, and the two
  Maestro workflows only run on a schedule, so they need checking by hand after an
  action release.

## Links

- actions/runner-images#14748 — the migration notice and its mitigations
- `scripts/fetch-ci-failures.sh` — reads a run's artifacts; added on this branch
  because these warnings were found by reading a run's logs rather than its UI

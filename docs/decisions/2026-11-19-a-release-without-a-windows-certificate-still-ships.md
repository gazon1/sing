---
title: "A release without a Windows signing certificate ships without the MSI, instead of not shipping at all"
date: 2026-11-19
status: accepted
tags: ["release", "ci", "packaging"]
---

# A release without a Windows signing certificate ships without the MSI, instead of not shipping at all

## Context

`release.yml` builds four things: the Android APK, the Linux `.deb` and `.rpm`,
and the Windows `.msi`. `publish` collects their artifacts into the GitHub
release, and it declared:

```yaml
publish:
  needs: [meta, android, desktop, desktop-windows]
```

This repository has **no Windows signing certificate**: the secret list holds
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`, `GRADLE_ENCRYPTION_KEY`, `TRACER_APP_TOKEN` and
`TRACER_PLUGIN_TOKEN`, and nothing named `WINDOWS_CERT_BASE64` or
`WINDOWS_CERT_PASSWORD`. There are no environment secrets and no organisation
secrets either.

On a tag push, the `desktop-windows` job therefore ran and failed its
`Validate signing secrets` step:

```
::error::WINDOWS_CERT_BASE64 or WINDOWS_CERT_PASSWORD is not set; signing will be skipped
```

The step is fail-closed on purpose — an unsigned installer must never be
shipped — and it is right to be fail-closed. But the job's failure was also
the `publish` job's failure, because a failed `needs` skips the job that
depends on it. So a tag push produced **no release at all**: the Android APK,
the `.deb` and the `.rpm` were built, uploaded as workflow artifacts, and
then dropped on the floor.

This is not a hypothetical. `v0.2.1` — the release that exists — contains
`MANIFEST.txt`, `SHA256SUMS.txt`, the APK, the `.deb` and the `.rpm`, and **no
MSI**: it was cut before `desktop-windows` joined `publish`'s `needs`. Nothing
has been releasable since. On 2026-11-19 the attempt to cut `v0.2.2` for
RuStore (issue #3007) stopped here.

The Windows packaging is not what anyone was waiting for: the RuStore upload
needs the Android APK, and the desktop packages are secondary. The absence of
a Windows certificate — a credential nobody has provisioned — was holding the
whole release hostage.

## Decision

- **The Windows MSI job is skipped on a tag push when the repository has no
  Windows signing certificate.** `meta` decides it once — the `secrets`
  context is not available in a job-level `if`, so a step reads the two
  secrets and hands the answer over as the `windows_cert` output — and
  `desktop-windows` reads that output.
- **`publish` runs on `always()` plus explicit result checks** for `meta`,
  `android` and `desktop`. A *skipped* MSI job no longer skips the release;
  a *failed* Android build or a *failed* `.deb`/`.rpm` still stops it, and
  `needs.desktop.result` is `success` only when every matrix leg passed.
- **The dry run is unchanged.** On `workflow_dispatch` the MSI job still runs
  and still produces an unsigned MSI, so the Windows packaging stays exercised
  by the dry run even when a release cannot sign it.
- **The fail-closed signing check stays.** Nothing here ships an unsigned
  MSI: the job that would have signed it is not run at all, so the installer
  is absent rather than present and unsigned.

## Rationale

The alternative — leaving `publish` strict — means the repository cannot cut
a release at all until someone provisions a Windows certificate. That is a
credential-shaped problem being solved by a packaging-shaped change: the
absence of an optional platform artifact should not decide whether the
Android artifact ships.

The choice between "ship an unsigned MSI" and "ship no MSI" is decided by
the repository's own existing behaviour rather than by taste: `v0.2.1` shipped
with no MSI, and `scripts/release_version.py manifest` already derives the
file list from what is actually in `dist/`, so a release without an MSI is a
shape the pipeline already knows how to describe. `MANIFEST.txt` and
`SHA256SUMS.txt` are computed from the staged files, so they will name the
files that are actually attached and not a file that is not there.

The `always()` condition is written with the result checks spelled out rather
than as a bare `always()`. A bare `always()` would publish a release even
when the Android build failed — the exact failure mode the strict `needs`
was there to prevent.

## Consequences

- **A release can be cut from this repository again**, with the Android APK
  and the Linux packages. That is what issue #3007 needed.
- **The release's asset set depends on the repository's secrets, not only on
  its code.** The day `WINDOWS_CERT_BASE64` and `WINDOWS_CERT_PASSWORD` are
  provisioned, the MSI job runs again and the next release carries the MSI —
  with no change to this workflow.
- **The release no longer fails as a whole when one platform is missing.**
  A reader of the release has to look at `MANIFEST.txt` to see which
  platforms it covers; the manifest is the record, and it was already the
  source of truth for the file list.
- **The dry run and the release can now disagree**: a dispatch builds an MSI
  that the release does not carry. That is deliberate — the dry run's job is
  to exercise the packaging, the release's job is to ship what can be signed.
- **The Windows smoke test does not run on a release**, because the job that
  runs it is skipped. It still runs on every dry run, so a broken Windows
  package is still caught before it would matter.

## Links

- `.github/workflows/release.yml` — `meta.outputs.windows_cert`,
  `desktop-windows.if`, `publish.if`
- `scripts/release_version.py` — the manifest, which names the files that are
  actually attached
- Issue #3007 — the RuStore release that was waiting for this

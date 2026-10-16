---
title: "The desktop package version has to satisfy rpm and deb at the same time"
date: 2026-10-16
status: accepted
deciders: max
tags: ["release", "packaging", "ci", "rpm", "deb"]
---

## Context

`v0.2.1-rc.1` was the first tag in this repository's history to reach the `publish`
job. Getting there took six defects, each one hiding the next:

1. a tag off `main` was correctly rejected by `meta` — proven with a probe tag,
   since no tag had ever been on `main` with a green CI for its own commit;
2. `-PpackageVersion` was never passed, so Gradle's `0.1.0` fallback was embedded in
   every package and the version check could never pass;
3. the signing check used `keytool -printcert -jarfile`, which reads `META-INF/*.RSA` —
   written only by a v1 signature, which `minSdk = 26` means AGP does not produce — so
   the fingerprint came back empty and a correctly signed APK failed;
4. rpm's default `RELEASE=1` was read as part of the version, so `0.2.1` verified as
   `0.2.1-1` against its own tag;
5. `MANIFEST.txt` computed `files` inside a heredoc, where the lines are text rather
   than commands, so the file listed shell source and an empty `files=`;
6. the version string itself.

The sixth is this one. Packaging failed at *configuration* time, in both matrix jobs,
including the one packaging only the deb:

```
Illegal version for 'Rpm': '0.2.1-rc.1' is not a valid version.
  rpm package version must not contain a dash '-'
```

## Idea

`nativeDistributions` declares `targetFormats(Deb, Rpm)` and carries **one**
`packageVersion`. Compose validates it against *every* declared format while
configuring the project, not when it builds a particular target. So the question is
not "what does each format want" but "what string do both accept".

Measured with the tool that fails — `./gradlew :desktopApp:packageRpm --dry-run`,
which is where the check happens:

| version | result |
|---|---|
| `0.2.1-rc.1` | rejected — rpm: must not contain a dash |
| `0.2.1_rc.1` | rejected — deb: `UPSTREAM_VERSION` may contain only alphanumerics and `.` `+` `-` `~` |
| `0.2.1rc.1` | **accepted** |
| `0.2.1.rc.1` | accepted |
| `0.2.1+rc.1` | accepted |
| `0.2.1~rc.1` | accepted |

Every replacement for the dash is a character one of the two formats forbids.

Options considered and rejected:

- **A version per format.** Not expressible: the property is shared, and validation
  runs against both formats regardless of which target is being packaged. This was
  tried (#672) and both jobs failed identically.
- **Map the dash to an underscore.** Also rejected, from the other side (#674's
  first attempt). deb forbids `_`.
- **Refuse prerelease tags on desktop.** Would work, and would make the constraint
  permanent rather than documented.
- **Set a per-format version in the Gradle DSL.** The failure message mentions
  `nativeDistributions.linux.rpmPackageVersion`; those names are jpackage's, and
  whether this Compose Multiplatform version exposes them was **not verified**. It is
  the natural thing to revisit if the constraint ever becomes a problem.

## Decision

The dash is dropped. Packages carry `0.2.1rc.1`; the release is titled after the tag,
`v0.2.1-rc.1`.

`release.yml` derives the package version once, as `pkg_version="${VERSION//-/}"`, and
passes the same string to both `packageDeb` and `packageRpm`. The verify step
recomputes it independently and compares the built package against it, so packaging
and verification cannot drift apart.

Both values are printed in the verify step. The tag and the package disagree, and
that disagreement is visible in the run log rather than something a reader has to
discover from a `.rpm`.

## Rationale

The rule was written twice before it was measured. The first attempt reasoned that
rpm splits a dashed version into VERSION and RELEASE, so the full tag string could be
passed and reconstructed at verification — plausible, and wrong: rpm refuses the input
before any splitting happens. The second attempt took the error's own advice ("must not
contain a dash") and mapped the dash to an underscore, which satisfied rpm and broke
deb.

Both attempts were "verified" by transcribing the workflow's bash into a local script
and checking the arithmetic. That kind of check cannot see a Gradle configuration
error — it tests the reasoning, not the tool. The real Gradle invocation settled it in
under a minute each, and a negative control (re-run the dashed form, confirm it is
still rejected) is what makes the acceptance meaningful rather than merely true today.

## Consequences

- **The tag name and the package version differ.** `v0.2.1-rc.1` ships
  `0.2.1rc.1`. Anything that compares a tag to a package version has to map it; the
  mapping is `${VERSION//-/}` and it is printed by the verify step.
- **The Android APK is unaffected** and still carries `0.2.1-rc.1` as its
  `versionName`, because Android has no such restriction. So the same release
  legitimately contains two spellings of its version, in two different formats.
- **Version suffixes must be dash-free to begin with.** `meta` validates
  `X.Y.Z[-suffix]` where the suffix may not contain another dash, which is what makes
  the single substitution sufficient.
- **The constraint is invisible until packaging.** A plain release (`0.2.1`) never
  exercised it; only a prerelease tag reaches it. Any future change to the version
  scheme should be checked against a prerelease, not a plain release.
- **If Compose exposes a per-format version property**, this decision can be revisited
  and the tag and package version could agree again.

## Links

- Run that first reached `publish`: https://github.com/gazon1/sing/actions/runs/38912309085
- PR #669 — the drawer fix, the Koin binding, and the `packageVersion` / `apksigner` /
  `RELEASE` fixes
- PR #672 — the per-format split, wrong
- PR #673 — `MANIFEST.txt` and the checksum ordering
- PR #674 — one dash-free version for both formats
- ADR `2026-10-16-koin-singleof-ignores-kotlin-defaults.md` — the same day, the same
  shape: a check that reports confidently about something it never ran
- `desktopApp/build.gradle.kts` — `nativeDistributions`, one `packageVersion` for both
  target formats
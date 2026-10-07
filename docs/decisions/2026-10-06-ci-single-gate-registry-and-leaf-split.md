---
title: "One gate registry, four CI leaves, one E2E workflow"
date: 2026-10-06
status: accepted
tags: [ci, process, testing, tooling]
supersedes: 2026-10-05-ci-checks-run-in-parallel.md
---

# One gate registry, four CI leaves, one E2E workflow

## Context

Four problems, measured rather than assumed.

**A gate lived in one place and ran in another.** `docs-audit.yml` was invalid
YAML, so a gate inside it reported nothing. Separately, several gates ran only in
`ci.yml` and several only in `check.sh`. Both halves were real; the duplication was
the defect, because the two lists drifted apart silently.

**Two workflows were incapable of green.** `maestro-nightly.yml` read
`FLOWS="${!FLOWS_REF}"` where `FLOWS_REF=shard${SHARD_NUM}`. Job outputs do not
become environment variables, so the list was empty, the loop iterated over nothing,
and the job printed `passed=0 failed=0` and exited 0 — a passing run that ran no
flow. The same file booted `android-emulator-runner` with no `script:`, and the
action kills the emulator when that step returns, so the following
`adb wait-for-device` addressed a device that no longer existed.

**The column that looked measured was not.** 18 of 19 scenario specs claim the
android target. Exactly one Maestro flow carries a `scenario:` tag, `--maestro` was
passed by no workflow, and `:androidApp:connectedDebugAndroidTest` runs in no CI job
at all. So `traceability results --targets android,desktop` rendered an android column
that was permanently `not-run` — indistinguishable, in the artefact, from a real
measurement.

**The release pipeline asked for things the build cannot do.** There is no
`signingConfigs` block anywhere, `isMinifyEnabled = false`, there are no
`appVersionName`/`appVersionCode` properties, and `targetFormats` declares only
`Deb`.

## Idea

- **A.** Fix the individual workflows, leave the structure alone.
- **B.** Move every gate into one registry file both surfaces call.
- **C.** Re-split `test-and-check` into six parallel leaves.

## Decision

**B and a four-leaf variant of C**, in that order, with B landing first.

`.github/workflows/` ends up as three files — `ci.yml`, `e2e.yml`, `release.yml` —
plus one composite action and two scripts under `scripts/ci/`. `docs-audit.yml`,
`maestro-smoke.yml` and `maestro-nightly.yml` are deleted.

`scripts/ci/static-gates.sh` is the single registry of every gate that needs no JVM
and no build output. `ci.yml`'s `static` job and `check.sh` both call it, so a gate
cannot exist on one surface and be forgotten on the other. `gate advisory` is the
only non-blocking mode, and `check-gate-wiring.py` rejects `|| true` on a `gate`
invocation so the mode cannot be faked with an idiom.

`ci.yml` has four jobs: `static`, `tests`, `android` (a two-leg matrix), and
`ci-gate`. There are no path filters. `ci-gate` is the only required check and
asserts that every leaf's `result` is `success` under `if: always()`.

`tests` stays monolithic on purpose. The earlier six-leaf split was measured at
24.25m → 9.2m and then withdrawn, because `main` had meanwhile coupled that job in
three places: the `$RUN_STARTED` stamp feeding two count and coverage floors, a flake
comparison against the previous run's artifact, and kover's report having to be
generated from a test run rather than from a cache. This shape keeps all three
inside one job, and splits only what has no coupling to move.

`e2e.yml` replaces both Maestro workflows. The APK is built once and shared by all
shards, sharding is arithmetic over a sorted flow list, an empty shard fails rather
than reporting green, and a red nightly opens an issue.

`release.yml` builds what the tree can actually build: an unsigned, unminified APK
and a Linux `.deb`. Both are checked against the tag's version before publication, so
a `v1.2.3` tag cannot ship a binary that declares `0.1.0`.

## Rationale

**Why the registry went first.** `check-gate-wiring.py` derives its registry from the
surfaces a gate can be invoked from (`GATE_FILES`, and `ci.yml` + `check.sh` for
Part E). Moving gates into a file it does not read does not fail any part — it makes
every gate in that file silently unverified, which is the defect class this file
exists to catch, one level up. Teaching it the registry first is what makes the move
safe rather than merely tidy.

**Why the meta-gate stayed out of the registry.** Part B proves `check-test-runs.py`
can fail by sabotaging its baseline and re-running it, and that gate reads JUnit XML
which exists only after the test tasks have run. A registry is static by definition;
in the `static` job there is no build output at all. Calling it from there
reproduces #206 — "already fails on a clean tree", a true statement that reads as a
false alarm. A gate that proves another gate works inherits that gate's preconditions
and runs them at its own time, so the meta-gate stays where the results are.

**Why android is declared rather than fixed.** The honest fix is 18 tagged flows or
a device-backed instrumentation job, and neither is a CI change. Declaring the
limitation in `config/docs/traceability-ratchet.json`'s `known_gaps` makes the
silence a recorded decision that a reader can find, instead of a column that looks
measured and is not.

**Why R8 is not in this change and signing is not either.** Minification is what
breaks Koin, Room and kotlinx-serialization, on their reflection, and nothing in CI
exercises a minified build today. The question "does the shipped binary work" comes
before "who receives the file", so R8 lands first and signing second.

## Consequences

- Branch protection requires exactly one check, `CI gate`. Old job names must be
  removed from the protection rule by hand.
- `GRADLE_ENCRYPTION_KEY` becomes the secret that makes the configuration cache
  persistable, which is what lets the coverage report be proven to come from the
  current run. Three hand-written `actions/cache` blocks keyed on `run_id` are gone.
- `OPENSPEC_VERSION` and `MAESTRO_VERSION` become repository variables. The first CI
  run after this lands fails until they are set: an unpinned `npx` is a gate that
  changes under you, so the pin is required rather than defaulted.
- `check-openspec-stale` and `check-doc-dead-refs --skill-symbols` now run locally
  as well as in CI, because the registry runs in both places. Two declared
  asymmetries in Part E flipped from `ci` to `both` as a result.
- `:mcp-server:test` runs once instead of twice, and `:mcp-server:jar` is now in the
  same invocation as its tests. Without that jar, `McpServerEndToEndTest` skips
  itself and reports success — a silent pass that the count floor is what catches.
- The debug APK is still built twice: once in `e2e.yml`'s `build-apk` and once in
  `ci.yml`'s `android` free leg. Sharing it would couple two workflows, which is the
  coupling that cost the previous split its validity. Trimming it is an owner's call.
- Part E was red on `main` before this change — `check-publication-hygiene.py` and
  `check-readme-claims.py` were declared `both` and ran only in CI. Routing them
  through the registry is what turns the declaration true.

## Links

- `2026-10-05-ci-checks-run-in-parallel.md` — the measured, built and withdrawn
  six-leaf split this shape is derived from
- `2026-10-04-one-gate-recipe.md` — why `check.sh` was deliberately not the one
  place for everything
- `scripts/ci/static-gates.sh` — the registry
- `scripts/check-gate-wiring.py` — Parts A–G, including the registry's own control
---
title: "The CI Gradle step cannot fail: a pipeline's exit code is tee's"
date: 2026-10-16
status: accepted
tags: ["ci", "measurement-integrity", "detekt", "bash"]
---

## Context

Chasing a question that looked small — why does `:shared:detekt` report 109
findings locally while CI is green? — turned up the fact that CI has not been
able to report on lint, tests, or compilation at all.

`ci.yml` ran its whole Gradle phase as:

```yaml
run: >
  ./gradlew --continue --no-build-cache --no-parallel … :shared:detekt …
  2>&1 | tee build.log
```

GitHub Actions executes `run:` with `bash -e {0}`. `-e` is errexit; it is **not**
`pipefail`. A pipeline's exit status is the status of its **last** command, so
`tee`'s — always 0:

```
$ bash -e -c 'sh -c "exit 3" 2>&1 | tee probe.log'          → 0
$ bash -e -o pipefail -c 'sh -c "exit 3" 2>&1 | tee probe.log' → 3
```

The step reports ✓ for every Gradle outcome. It is not a gap in some corner of
the matrix — it is the only step that runs the tests, the linter and the compiler.

## Evidence, not inference

Run `38902346697` on `main`:

```
run conclusion: success      ← every job green
inside "Gradle (tests + coverage + detekt)":
  > Task :androidApp:detekt FAILED
  > Task :shared:detekt FAILED
```

A green run containing two failed lint tasks. That is the whole finding.

## The chain that hid 60 violations

Four independent links, none of which noticed, and all of which are now closed
or named:

| # | Link | State |
|---|---|---|
| 1 | Violating code was written; the baseline was not regenerated | 60 findings, **fixed** |
| 2 | `check-baseline-ratchet.py` reported green | **it could not have been otherwise** — see below |
| 3 | `:shared:detekt` FAILED | real |
| 4 | CI did not notice | `pipefail` |

**Link 2 is the one nobody suspects.** The ratchet compares the baseline *file*
against its own committed state in git. It never runs detekt. So it proves
"this change did not grow the baseline" — which is not "detekt is green". Those
were being read as one claim. The ratchet's own contract was correct; the
assumption around it was not.

That assumption was the most expensive one in the chain, because it is the one
that feels verified: a green gate is a green gate.

## Decision

**Local enforcement was always real.** `check.sh:189` runs the module detekt
tasks and fails on error. So `./check.sh` was a trustworthy signal the whole
time, and the divergence was **CI-only** — which is why it went unnoticed for as
long as it did. `bash scripts/ci/static-gates.sh` and `./check.sh` disagreeing
about the world is a smell worth chasing on its own.

**The ratchet now states its scope.** Its docstring gained a "What this does NOT
check" section naming the two claims that got conflated, the four-link chain,
and where "detekt is green" is actually enforced. No behaviour changed; the
script still does exactly what it did.

**Exit-code loss is audited per surface, not per file.** See the table below.

## The audit

Every pipeline and `|| true` in `.github/workflows/`, classified:

**Real blind spots** — a command whose failure is discarded:

| Location | What it hides |
|---|---|
| `ci.yml:243` | the entire Gradle phase: tests, detekt, compilation |
| `flake.yml:138` | the flake job's Gradle step |

`flake.yml` is the worse of the two by purpose. That job exists to measure
non-determinism by running the suite N times and comparing the result sets. Its
step is also masked, and its only follow-up steps upload JUnit XML with
`if: always()` — so a Gradle failure produces no XML, the job measures an empty
set, and reports the job green. A measurement that silently measured nothing.

**Command substitutions** — lower severity, different failure: `ci.yml:639`,
`flake.yml:85`, `release.yml:181`, `release.yml:203`, `release.yml:289`. These
are `$( … | sed … )` inside a larger command, so a failed `sed` yields an empty
string rather than a non-zero exit. `release.yml:289` is worth a look on its own:
an empty embedded package version is exactly the shape of the bug #672 just fixed
("an RPM version cannot contain a dash, so a prerelease tag failed to package").

**Declared advisory** — `ci.yml:254` (per-test annotations),
`ci.yml:357` (flake analysis), `ci.yml:578` (`singularity-adr stale`),
`ci.yml:655` (`git add`), `ci.yml:307`/`315` (`grep` into the summary). Each is
annotated with why being advisory is correct.

**Declared advisory, hiding a real failure** — `ci.yml:286`:

```yaml
run: ./gw :koverMarkdownTable >> "$GITHUB_STEP_SUMMARY" || true
```

That step is where Gradle reported:

```
Task ':adr-core:koverGenerateArtifact' uses this output of task
':adr-core:compileKotlinJvm' without declaring an explicit or implicit dependency
```

A genuine Gradle 9 validation error, under a marker that *looks* like an honest
declaration. Fixed at the source instead: `adr-core/build.gradle.kts` now declares
the dependency lazily, so it cannot break configuration if Kover's task ordering
changes. The `|| true` stays — the step genuinely is advisory — but it no longer
has anything real to hide.

## Consequences

- **`ci.yml` and `flake.yml` get `pipefail`.** This makes the Gradle steps able to
  fail for the first time. It lands *after* the violations are fixed, because
  flipping it earlier turns CI red for a reason that is already known and already
  paid for.
- **Green CI runs from before this change say nothing about lint, tests, or
  compilation.** The Python gates in those runs did execute and did pass; that is
  the whole of what they prove.
- **The `test-execution-integrity` spec gained the missing invariant.** It covered
  test execution and counts, and said nothing about the exit codes of CI steps —
  the one thing that was broken.
- A new class of red becomes possible: the Gradle steps will now fail on things
  that were previously invisible. That is the point, and the first run after the
  flip should be read with that expectation.

## Links

- Issue: the follow-up covering `Part H`'s reachability fallback and `Part J`'s
  join key, which are the same defect class in the Python gates
- [2026-10-16-nightly-ci-gate-that-could-not-see.md](2026-10-16-nightly-ci-gate-that-could-not-see.md) —
  the same session, the sibling finding: a gate that could not see what it
  measured
- ADR `2026-10-04-measurement-integrity` — the principle both findings violate
- `specs/test-execution-integrity/spec.md`
- Failed run: <https://github.com/gazon1/sing/actions/runs/38902346697>
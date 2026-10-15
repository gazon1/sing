---
title: "The coverage floor lives in the build file; the two scripts that restated it are gone"
date: 2026-10-15
status: accepted
tags: ["ci", "coverage", "detekt", "gates"]
---

## Context

`detekt-rules` gained a branch-coverage floor in the change
`openspec/changes/detekt-rule-coverage-floor`. The moment it existed, the number
had three homes:

1. `detekt-rules/build.gradle.kts` — the constant Kover reads.
2. `scripts/ci/kover-verify-gate.sh` — a shell gate with its own copy of `60`,
   which generated the XML and compared it in Python.
3. `scripts/check-detekt-rule-coverage.py` — a Python parser of that XML with a
   third copy of `60`, wired as a Part-F positive control and as the renderer of
   ci.yml's job summary.

Three copies is not three defensible readings of one number; it is one number
and two places for it to drift. The drift had already happened silently. The
summary step ran the Python renderer *before* the step that generated the XML
it parses, so the job summary had been printing its own "report not found"
failure for as long as the step existed — a check reporting a verdict nobody
derived, which is the exact defect class `check-gate-wiring.py` exists to catch,
missed because the renderer was a gate rather than a step.

A second, unrelated fact: the comment in `detekt-rules/build.gradle.kts` claimed
that kover 0.9.x's project-level Kotlin DSL has no `verify { }` binding. Checked
against the 0.9.9 sources, that claim is false — `reports { total { verify {
rule { bound { minValue } } } } }` is the API, and `verify.onCheck` defaults to
true for the total variant, so `:detekt-rules:check` already runs the task.

## Idea

Let Kover be the only thing that interprets the floor, and make every other
reader read the declaration rather than restate it.

`koverVerify` enforces (ci.yml's `tests` job, `check.sh`, and `check` by way of
`onCheck`), `koverLog` renders the measured number for the job summary and for
the local loop, and Part C of `check-gate-wiring.py` reads the *declaration* and
asks only the question a static check can answer: is there a bound, inside the
`kover` block, that resolves to at least 60.

## Decision

- **One source.** The floor is `DETEKT_RULE_BRANCH_FLOOR` in
  `detekt-rules/build.gradle.kts`, consumed by the Kover `verify` rule and
  printed by the Kover `log` block. Nothing else holds a copy.
- **Delete both restatements.** `scripts/ci/kover-verify-gate.sh` and
  `scripts/check-detekt-rule-coverage.py` are removed, together with the Python
  script's Part-F fixture entry and its `GATE_PARITY` entry. The Gradle task
  replaces the shell gate on both surfaces (`ci.yml` step "Kover verify
  (detekt-rules)", `check.sh` step 9b) — Part I of `check-gate-wiring.py`
  enforces that both name it.
- **The declaration is held statically, the run is held by a control.** Part C's
  `kover_verify_rules_without_usable_floor()` mirrors the neighbouring
  `ignoreFailures` check and its doctrine: *the build file is the claim*. It
  reads only inside `kover { … }` blocks (brace matching, comments stripped), so
  another plugin's `verify` or a `minValue` in prose cannot qualify a module or
  raise its floor. The half a static check cannot answer — does the task actually
  fail when coverage is below the bound — is the SABOTAGE control
  `detekt-rule-coverage-floor` in `SABOTAGE_ONLY_GATES`, which raises the
  constant to 100 and requires `:detekt-rules:koverVerify` to exit non-zero.
  It is the only control in that file that runs Gradle, deliberately: no Python
  reads the bound at run time, so no Python can show the task honours it.
- **The named list has a generator.** The floor's comment quotes the worst ten
  rules by missed branches with the date they were measured, and `just tkr`
  (scripts/detekt-rule-coverage.py) prints that same list — now with a branch
  section, since branch is the quantity the floor enforces. A comment is a
  dated snapshot; the script is what is current.

## Rationale

- **Drift needs three copies to be possible.** A shell gate and a Python parser
  each had to be told the floor; each was free to disagree with the Kotlin one
  and would have kept passing while doing so.
- **A renderer must not be a parser of a report that does not exist yet.** The
  ordering bug in the summary step is unfixable in principle once rendering and
  generation are separate steps — the fix is to render from the same task the
  gate uses, which needs no XML at all.
- **"A floor that cannot fail is the same defect as no floor" applies to the
  declaration too.** Part C's check costs a file read and catches an unbound or
  halved rule on every run of the meta-gate; the Gradle control costs a
  configuration pass (the module's tests run before it in both surfaces that
  call this script) and catches the case where the declaration is right and the
  task is not.
- **The nesting is the scope.** Reading `verify` and `minValue` anywhere in the
  file would make the check a claim about text it never established was kover's.

## Consequences

- Raising the floor is one edit in one file; lowering it past 60 additionally
  requires editing `_KOVER_MIN_FLOOR` in `check-gate-wiring.py`, which is
  deliberate friction, not an oversight.
- A second module that grows a `kover { verify { … } }` block is covered
  automatically — Part C scans `*/build.gradle.kts` — without any new wiring.
- `check-gate-wiring.py` now performs two Gradle invocations where it performed
  none, in the two places that already run Gradle (ci.yml's tests job, `check.sh`
  step 8) and nowhere else: `static-gates.sh` still does not call it, by the
  earlier decision recorded in that file.
- The root kover aggregation's `koverVerify` remains unwired on purpose: product
  floors live in `config/docs/coverage-baseline.txt` behind `check-coverage.py`,
  and mixing rule coverage with product coverage would make either regression
  unreadable.
- The Python renderer's removal is a net deletion of capability in one respect:
  it re-derived the percentage from XML. `koverLog` prints the same digits from
  the same report, and `just tkr` prints them with the per-rule breakdown.

## Links

- `openspec/changes/detekt-rule-coverage-floor` — the change this decision
  records; its `tasks.md` carries the positive-control run.
- `docs/decisions/2026-10-05-positive-tests-for-every-detekt-rule.md` — why
  branch coverage inside the rules is the number that matters.
- `docs/decisions/2026-10-05-positive-control-registry-is-derived.md` — the
  registry Part F derives, which the removed fixture used to belong to.
- `docs/decisions/2026-10-04-gradle-daemon-isolation.md` — why the control runs
  `./gw` rather than `./gradlew`.

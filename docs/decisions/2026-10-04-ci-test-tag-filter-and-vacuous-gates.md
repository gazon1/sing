---
title: "CI ran ~15 of 214 test classes — restore execution before coverage"
date: 2026-10-04
status: accepted
tags: [ci, testing, detekt, quality-gates, ratchet]
---

# CI ran ~15 of 214 test classes — restore execution before coverage

## Context

The repo presented as fully gated: `check.sh` runs seven verification stages,
`ci.yml` has five jobs, and `detekt` reports zero violations. A 2026-10-04 audit
of the verification layer found that **16 of the audited gates shared one defect
class** — a gate whose *claimed* coverage exceeded its *actual* coverage, so it
reported success while testing nothing.

The two that mattered most were not edge cases. They were the suite itself and
the lint layer.

### Finding 1 — the test suite did not run in CI

`shared/build.gradle.kts:273-280` branches on a Gradle property:

```kotlin
val tags = (project.findProperty("test.tags") as String?)?.split(",")?.orEmpty() ?: emptyList()
if (tags.isNotEmpty()) {
    includeTags(*tags.toTypedArray())   // CI took THIS branch
} else {
    excludeTags("slow")                 // ./check.sh takes this branch
}
```

`ci.yml` passed `-Ptest.tags=fast,slow` to both blocking test steps. JUnit 5's
`includeTags` is an *inclusive* filter: **untagged tests are excluded**. The repo
has 1 `@Tag("fast")` and 15 `@Tag("slow")` across 212 test files, so CI
executed roughly 15 classes and skipped ~198 — including every enforcement test
the project relies on:

`ArchitectureTest` · `DiFacadeTest` · `HarnessConventionTest` ·
`TestTagsWiringTest` · `ViewModelInitOrderTest` · `EntityMapperCompletenessTest` ·
`ExtensionServiceRegistrationTest` · `TestPlatformModuleParityTest`

Locally, `check.sh` passes no `-Ptest.tags`, so it takes the `else` branch and
ran the untagged files all along. **The suite was green locally and hollow in
CI.** That single asymmetry explains every drift finding in the audit — the
dead skill references, the dangling backlog entries, the un-flagged composables.
Nothing was failing; nothing was running.

A second, independent gap compounded it: the workflow carried a `paths:` filter
limited to `openspec/**` and `.github/workflows/ci.yml`, so a Kotlin source
change did not trigger a build **at all**.

### Finding 2 — two rules were registered but never configured

`NoDirectDispatchers` and `ProhibitUserIdInObserve` appear in
`META-INF/services/dev.detekt.api.RuleSetProvider`, and both have KDoc
promising coverage. Neither had a block in `config/detekt/detekt.yml`. detekt
resolves rule config by rule-set id, so both loaded with defaults and enforced
nothing — the exact defect class the project's own custom rules exist to
prevent.

`check-detekt-registrations.sh` checked for duplicate providers, duplicate YAML
blocks, and class resolvability. It never checked that a `RuleSetId` had a
corresponding block, which is why the gap survived.

### Finding 3 — three audit scripts could not fail

- `check-doc-sizes.py` under `just docs-audit` ran `--warn-only || status=1`.
  `--warn-only` returns 0, so `status=1` was unreachable.
- `docs-audit.yml` used `|| true` and `echo "Warning:"` on every step.
- `find-unwired-surfaces-baseline.txt` documents its own invariant — "a line
  without a live backlog reference is a gate failure" — and
  `find-unwired-surfaces.py` reads `parts[2]` (the reason) while ignoring
  `parts[3]` (the reference). Four of its five anchors pointed at headings that
  did not exist in `deferred-backlog.md`.

### Finding 4 — the rules' own tests had never run, and 4 of them failed

Proving that a rule can fire means running it. The obvious way is
`:detekt-rules:test`, and **no gate in the repository ran it** — not `check.sh`,
not `ci.yml`, not the `justfile`. It had 56 tests waiting. On first execution
**four failed**:

- `NoDirectDispatchersRuleTest > Dispatchers_IO is flagged` and
  `> Dispatchers_Default is flagged` — the rule required
  `expr.selectorExpression as? KtCallExpression`, but `Dispatchers.IO` is a
  *property* reference, so every real call site returned early. The rule could
  not fire on the code it was written for, and its own test said so.
- `> Dispatchers_IO in FileLogWriter is whitelisted` — passed a file path where
  `compileContentForTest` wants a package name, raising `IllegalArgumentException`.
- `NoEmptyOnClickLambdaRuleTest > onClick with empty lambda consumed via elvis is
  flagged` — asserted handling the rule never implemented.

This is the sharpest instance of the whole pattern: a rule with a test suite,
reporting compliance, where the test suite had never been executed.

### Finding 5 — `docs-audit.yml` was not valid YAML

Line 17 read `- 'openspec/**''` with a stray trailing apostrophe. The file could
not be parsed, so the workflow could not have run — independently of every step
in it already being advisory. Two separate reasons the documentation audit never
happened, neither visible from reading the YAML.

### Finding 6 — the doc-size budget was never enforced

`just docs-audit` ran `check-doc-sizes.py --warn-only || status=1`.
`--warn-only` returns 0 by construction, so `status=1` was unreachable. The
budget printed "AGENTS.md: 264 lines (max 250)" and the recipe exited 0.

### Finding 7 — a gitignored file is not a dead reference

`check-doc-dead-refs.py` reported `docs/decisions/DIGEST.md` as a dead reference
in `AGENTS.md` and `README.md`. `DIGEST.md` is deliberately gitignored and
rebuilt by a post-checkout hook (5c0c2e9d), so it is absent in a fresh clone and
present after a docs refresh. The check was environment-dependent: green on a
machine where someone had run `just docs-regen`, red everywhere else. The
detector now consults `.gitignore` and classifies such references as *generated*.

## Idea

**Scope note.** This entry covers the CI half. The six remaining detekt rules
suspected of the same defect are worked through separately in
`2026-10-04-rule-verifiability-inventory` — five were real, one was a false
accusation in the original audit.

Treat the *execution* of a gate as a property to be verified, not assumed. Every
gate gets a positive control — a deliberate violation it must catch — and a CI
step that actually runs it. Fix the cheapest, highest-blast-radius defect first:
the test-suite tag filter, because until it lands no other gate result is
trustworthy.

## Decision

**1. CI runs the untagged suite; slow tests get their own job.**
`ci.yml` no longer passes `-Ptest.tags` to the blocking steps, so it takes the
same `else` branch `check.sh` takes. The 15 `@Tag("slow")` classes move to a
separate `slow-tests` job running `-Ptest.tags=slow` — still executed, no longer
gating a PR. The `paths:` filter is removed entirely: a source change must
trigger a build.

**2. `check-detekt-registrations.sh` gains a missing-ruleset invariant** (in both
directions: every `RuleSetId` needs a block, every block needs a provider), and
the two unconfigured rulesets are registered. This one invariant is what would
have caught Finding 2 on the day it was introduced.

**3. Ratchet the detekt baseline instead of treating it as a sink.**
`scripts/check-baseline-ratchet.py` fails when `baseline-shared.xml` grows and
allows it to shrink. Ratcheting is the standard replacement for a mass purge: it
keeps the existing 413 entries while making new debt a visible, reviewable diff.

**4. Backlog references are validated, not assumed.**
`scripts/check-unwired-backlog-refs.py` resolves column 4 of the unwired-surfaces
baseline against a real `## heading` in `docs/decisions/deferred-backlog.md`, and
the four missing entries are written.

**5. Gates that cannot fail are made able to fail.** detekt and a11y become
blocking; the audit-script regression tests (`scripts/tests/`, 20 cases,
previously executed by nothing) run in CI and in `check.sh`; `--skill-symbols`
is wired in.

**6. The rules' own tests run, and the two broken rules are repaired.** Rather
than trusting the audit's reading of the PSI, `NoDirectDispatchers` was run
against a deliberately-violating file: it stayed silent, which located the defect
precisely. The rule now handles both selector forms, and
`NoEmptyOnClickLambda` is driven off the parameter name rather than an 11-entry
callee allow-list, with the elvis-fallback shape implemented. `:detekt-rules:test`
is wired into `check.sh` and `ci.yml`.

**7. The two rulesets that were dead now report.** With the registration fix and
the rule fixes, `NoDirectDispatchers` and `NoEmptyOnClickLambda` surfaced **52
pre-existing violations** that no gate had ever seen — 21 direct dispatcher
references and 31 empty handler lambdas. They are baselined so the build is
green, tracked in `deferred-backlog.md`, and fenced in by the ratchet.

**8. Cheap structural checks for structurally-invisible failures.**
`check.sh` now parses every `.github/workflows/*.yml`. A malformed workflow is
not a test failure and not a lint error — it is a gate that does not exist, and
a three-line parse is the whole fix.

## Rationale

**Why fix execution before coverage.** Adding a rule or tightening a threshold
before the suite runs is building on a measurement that was never taken. The
audit could infer, from PSI semantics, that `PassThroughUseCaseRule` cannot fire
on the canonical primary-constructor pattern — but confirming it required a real
detekt run against a real file, which required the suite to be reachable at all.

**Why a positive control rather than a review.** The durable fix for a vacuous
rule is a test proving it fires, not a second reviewer noticing it does not. The
detekt rule-testing guide treats "every rule has a test" as table stakes; nine
rule classes here had none. Each new script in this change was verified by
deliberately breaking its input and observing a non-zero exit — including the
registration check, which is verified against a removed `detekt.yml` block and
against an orphaned block.

**Why ratchet rather than purge.** Purging 413 entries would produce a huge diff
of findings nobody has triaged, most of which are legitimate. A ratchet stops the
bleeding immediately and lets the backlog drain at its natural rate.

**Why the slow tests were not simply left excluded.** Moving them to a job
preserves the intent of the original tag — restart-smoke and codec-contract tests
are slow for reasons that should not gate a review — while removing the false
impression that they run somewhere. They now run on every push and are visible
in the checks list.

## Consequences

- CI wall-clock rises: ~198 additional test classes now execute per push. Local
  measurement for the untagged shared suite is 4m42s on a warm machine; the CI
  job adds `--no-daemon` on top. Budget triage capacity for a timeout or an
  environment-sensitive test surfacing — and note that **re-tagging a failure to
  make it disappear would recreate the original defect.**
- The baseline grows once, by 17 entries in `baseline-shared.xml` and 1 in
  `baseline-desktopApp.xml`, as the newly-effective rules are baselined. Until
  this lands as a commit the ratchet reports that growth as a failure; that is
  the intended friction. After it lands, the count may only decrease.
- Three quality gates changed from advisory to blocking in the same change. If
  that proves too much for one PR, the A5 scope is the natural split point —
  A1 through A4 stand on their own.
- The four backlog entries written here are documentation only. Per the owner's
  decision, the unwired *product* stubs (sync, backup, attachments, analytics,
  `core/auth/oauth/`) get no code change and no new entry this cycle; they are
  simply visible in a CI log that now runs the detector.
- `AGENTS.md` had to be trimmed from 264 to 250 lines for the budget to pass.
  Nine lines went to content duplicated in dedicated skills and the auto-generated
  catalog. That is the intended pressure, but it means the next genuinely new
  rule will hit the same wall — the durable fix is moving reference material into
  skills, not another trim.

## Links

- `2026-10-04-rule-verifiability-inventory.md` — the follow-through: the same
  method applied to the six remaining suspects, five of which were real defects.
- `2026-10-01-ci-quality-ratchet.md` — the earlier ratchet that uploaded the
  failure bundle without making the bundle non-empty; this change sets
  `-Dsingularity.test.steps=true` so it is.
- `2026-10-04-desktop-test-failure-diagnostics.md` — the bundle format.
- `2026-09-26-detekt-baseline-established.md` — why the baseline exists.
- `deferred-backlog.md` — `ci-gates-are-all-continue-on-error` records the
  oldest-debt-first policy for flipping the remaining advisory gates.

---
title: Scenario test cases, and the traceability matrix built from CI results
date: 2026-10-05
status: accepted
supersedes: 2026-10-05-kiwi-tcms-stand-and-non-test-files
tags: [kiwi, tcm, testing, infra, tooling, traceability]
---

## Context

`2026-10-05-kiwi-tcms-stand-and-non-test-files.md` established the invariant that
**one Kiwi case = one Kotlin test class**. The stated reason was stability: a case id
should survive a signature change inside a class.

That reason is sound but the granularity is wrong, and it makes the question the
stand exists to answer unanswerable. "Which user scenarios do we verify, at which
level, on which platform, and what was the last result?" cannot be expressed at class
granularity. The 259 cases answer "which classes exist", and a class is not a user
behaviour. Concretely, the old model **cannot represent** "TASK-REC-01 passes on
Android and fails on Desktop" — one case, and a "last execution per case" reader
collapses the tiers.

Two models were in tension when this was designed. `tcms_junit_plugin` (the official
publisher) derives one case per *test method* from `${classname}.${name}` and reuses
cases by summary+product. Kiwi's own model puts several `TestExecution`s under a
*single* `TestCase`. A result matrix derived from "last execution per case" cannot be
correct against either.

## Idea

Invert the pipeline: normalise CI results into one canonical form first, and derive
every artefact from it.

```
tests ─► raw JUnit/Maestro XML ─► NORMALISE (pure) ─► coverage matrix (committed, CI-checked)
                                          ├─► result matrix  (CI artifact, never committed)
                                          └─► Kiwi publish   (projection, never a gate)
```

Ownership, stated once:

| Holds | What |
|---|---|
| Git | what should be tested (specs) + how automation links (`@DisplayName`, `scenario:` tags) |
| CI | what ran, and its pass/fail — authoritative |
| Kiwi | test management + run history — a projection, never a gate |
| matrices | derived views, no hand-maintained state |

## Decision

1. **A Kiwi TestCase is a user scenario** (`TASK-REC-01`), specified in Git under
   `infra/kiwi/scenarios/<area>/<subarea>/<ID>.yaml`. One direction only: Git → Kiwi.

2. **Linkage lives in code, in two carriers** that both declare the id as a *prefix
   token*: `@DisplayName("TASK-REC-01 …")` on Kotlin user-flow tests, and
   `scenario:TASK-REC-01` in a Maestro flow header. No custom annotation, no new
   Gradle module, no duplicated declaration.

3. **Coverage is committed; results are a CI artifact.** A file mixing "who claimed
   this" with "what passed on abc1234" goes stale at merge time and conflicts across
   branches, so it is split.

4. **One Kiwi run per (commit, target).** This is what lets Android-✅ and Desktop-❌
   coexist over one case: two executions in one run are the same observation twice,
   and a last-wins reader loses the per-tier status.

5. **Strictness is the feature.** An id not at the start of a `@DisplayName`, two ids
   on one method, an id with no spec, a scenario claimed by two tests, a mixed-commit
   results file — all hard errors. A scenario with *no* automation is not an error; it
   is a hole (`○`), and the hole is the point.

6. **The 259 legacy unit/domain cases are not migrated, renamed or tagged.** They stay
   supporting tests in the `Automated/*` plans, which are frozen. Per-class coverage
   is a different question, and Kover answers that one.

## Rationale

**Why `@DisplayName` and not a custom annotation.** `desktopApp` sees only `shared`'s
*main* sources — there is no `testFixtures` anywhere — so a custom annotation would
have to be declared twice, in two modules that cannot share it, plus a new Gradle
module and build wiring, for tens of annotated methods. `@DisplayName` already exists
in Jupiter, works for Compose UI tests, is compile-checked, and reaches JUnit XML for
free.

**Why `@DisplayName` is keyed, not parsed.** A regex that starts tolerating whitespace
variance is how a Kotlin parser ends up being written in Python. KSP was rejected as
over-engineering for tens of methods.

**Why a prefix token.** A reader — human or scanner — can tell an id from prose.
`TASK-REC-011` must not read as `TASK-REC-01`.

**Why no requirements tree in Kiwi.** This build exposes no `Requirement.*` RPC method
and `TestCase.requirement` is a free-text `CharField(255)`. The `Tasks → Recurrence →
Daily` hierarchy therefore lives in Git only, and Kiwi is not asked to mirror it.
Dotted Component names and nested plans were both considered and both are compromises;
the directory hierarchy won because Git already has it.

**Why staleness is decided by commit, not file age.** A regenerated-but-old report
must not masquerade as current. The commit is written in the file, so a 24-hour
heuristic is a guess when the answer is known.

**Why the plugin was not adopted for publishing.** `kiwitcms-junit.xml-plugin` derives
its case identity from `${classname}.${name}` and reuses cases by summary+product; on
GitHub Actions no Travis/Jenkins default applies, so every `TCMS_*` would need setting
explicitly or it creates a plan per run. Given that the model does not fit, publishing
directly over the already-debugged `kiwi_client.py` was cheaper than working around
the plugin. This retires the plan's rollback criterion in the direction it pointed.

## Consequences

- `docs/testing/coverage-matrix.md` is generated and diffed byte-for-byte in `check.sh`
  (step 8c), which runs `validate` first so a broken spec is reported as a broken
  spec rather than as a matrix diff. Editing it by hand is reverted.
- `build/traceability/result-matrix.md` is never committed; it names its commit in its
  header.
- The `scenario:` tag namespace is deliberately disjoint from `TestTags` UI selector
  ids, and a validator enforces that. Unifying `@Tag` / Maestro tags / `TestTags` is a
  **separate** refactor and was not pulled in.
- `Maestro/CONVENTIONS.md` and `Maestro/config.yaml` are amended to admit
  `scenario:<ID>` as the one documented exception to the lowercase-hyphen tag rule.
  Both files must be changed together; matching is exact string comparison, so no code
  change was needed.
- Kiwi cannot influence a build. A stand outage degrades reporting and nothing else
  (exit code 3, distinct from 1, so an outage is not mistaken for a broken spec).
- Deleting anything in Kiwi is still a human decision. `seed` surfaces duplicates
  rather than removing them, per the standing project rule.

### CI cannot reach the local stand

`https://127.0.0.1:8443` is loopback-only, so the publish step cannot run in CI as
configured. Until that is decided the honest statement is: **Kiwi history is only as
fresh as the last local publish.** The stand was deliberately *not* exposed or
tunnelled to work around this.

## Defects found while building this

Recorded because each was a real latent bug that this work depended on, not a
consequence of it.

1. **`RecurrenceSpec` was `@Serializable sealed` with no `@Serializable` on any
   subclass.** Polymorphic serialisation of *any* recurrence threw
   `Serializer for subclass 'Interval' is not found`. The consequence was that
   `TasksRobot.given(recurrence = …)` could never have worked, and no test in the
   repository seeded a recurring task. Fixed by annotating the four subclasses;
   backup/restore of a recurring task was equally broken and is now covered.

2. **Desktop failure diagnostics masked the failure they were capturing.**
   `SemanticOverlay.captureAnnotated` called `onRoot()`, which *asserts* exactly one
   root — so as soon as an overlay (a `ModalBottomSheet`) created a second semantics
   root, capturing the failure threw and replaced it. A test failing on a selector
   inside a sheet reported "expected exactly 1 node but found 2 nodes that satisfy
   (isRoot)" instead of the missing tag. Diagnostics that mask the failure are worse
   than none. Fixed by taking the first of several roots and appending the overlay
   roots to the node list.

3. **The recurrence picker had no `testTag`s**, unlike the priority picker. The project
   rule is a tag is added together with the flow that consumes it; here there was no
   consumer, so the gap was invisible. `TestTags.RECURRENCE_OPTION_*` added for the
   frequency options and the clear option.

Defects found in the new code itself, by review against the live stand, each of
which contradicted a contract this ADR states:

4. **A re-tested commit kept its stale `PASSED`.** Idempotency was implemented as
   "skip a case that already has an execution in the run", which is wrong: the same
   HEAD is routinely re-run, and its outcome can have flipped. Kiwi then held
   `PASSED` while the result matrix honestly held `❌` — a preserved pass where the
   truth was a failure, and the single most dangerous failure mode available here.
   An existing execution is now **overwritten**
   (`KiwiClient.update_execution`), which keeps unchanged re-publishes idempotent
   while making changed ones correct. `TestExecution.update` also takes
   *positional* args and a status **pk**, like `add_execution`.

5. **`seed --check` was a false green.** It compared only the case status, so a UI
   edit to the summary, priority, area or spec path was neither reverted nor
   reported, and a spec edit to any of them never reached Kiwi. It now compares all
   of them, and a normal seed repairs the property fields (Git → Kiwi is one
   direction and the spec wins). Summary, priority and case status are **not**
   repairable — this RPC surface has no usable `TestCase.update` — so drift in
   those is reported for a human.

6. **A non-`confirmed` spec made `--check` permanently red.** `create_case` hardcoded
   `CONFIRMED`, so a `proposed` spec created a CONFIRMED case that no seed could
   repair. `create_case` now takes the status explicitly.

7. **An unrecognised outcome published nothing and reported success.** `not-run`
   and "any other string" were both treated as "skip", so a typo or a hand-edited
   `results.json` exited 0 having written nothing. Unknown outcomes are now an
   error.

8. **The publish path could create a Build that permanently breaks run creation.**
   `ensure_build` falls through to creating a Build under a `Version` named after
   the commit, whose id is not the plan's `product_version`; `NewRunForm` constrains
   builds by exactly that field, so every later `TestRun.create` would fail. The
   publisher now uses the plan's own compatible build or fails loudly. The commit
   does not need the Build dimension — the run *summary* is the (commit, target)
   identity, and that is what makes history queryable per commit.

9. **A flow claiming two scenarios silently lost one.** Result rows are indexed by
   flow path, so a second `scenario:` tag overwrote the first and that scenario
   rendered as `not-run` with nothing raised. One flow run yields one result, so it
   can verify exactly one scenario; two tags are now rejected in `scan_maestro`.

10. **Smaller ones, same spirit:** `_match_flow` was defined twice (dead code in a
    module whose whole point is deduplicating the reader); the "report every
    problem at once" contract did not cover `title`; `links.py` carried a second,
    provably dead copy of the name normalisation that `keys.py` explicitly forbids;
    a malformed `results.json` escaped as a raw traceback; a missing product or plan
    was reported as exit 3 ("stand unreachable") instead of a configuration error.

## A limitation worth recording, not hiding

`ModalBottomSheet` renders into a separate semantics root on Compose Multiplatform
desktop, so **no JVM Compose test can reach a selector inside a sheet**. Verified, not
assumed: the tags were added and the test still could not see them. (`AlertDialog` is
reachable — `ConfirmActionDialog` is asserted in `SavedAgendaEditFlowTest` — so this
is specific to sheets.)

Consequence for `TASK-REC-01`: choosing a frequency is verified on Android by the
Maestro flow, and the desktop Compose test covers the composer's entry point. The
desktop test says so in its KDoc rather than pretending to cover the journey. A `●` in
the matrix means "a real user behaviour is verified here", not "a selector was found".

Related: `TaskDetailContent` passes `recurrenceCallbacks = null`, so the recurrence row
is deliberately absent from the editor reached by tapping a task — the composer is the
only surface that offers it.

## The zero-testcase rule is scoped to targets that actually ran

A target with **no** result directory is not enforced; a target whose directory
exists but yielded no linked testcase is an error (exit 2). Without that
distinction a desktop-only local run — or a CI job where the Android flows are a
different job — would fail for the absence of a run nobody scheduled. "Ran and
produced nothing" is the quiet-green bug; "was never run" is a fact the matrix
already reports as `not-run`.

Note that `check.sh` is **not** what CI runs: `ci.yml` restates the gates
inline, so a check that lives only in `check.sh` runs only on a maintainer's
machine. The coverage matrix is therefore verified in *both* places, and the
result matrix is uploaded as an artifact and rendered into the job summary there.

## Known limitations

- **CI cannot reach the stand**, so `kiwi-seed` / `kiwi-publish` are local-only. The
  honest statement remains: Kiwi history is only as fresh as the last local publish.
- **Maestro's JUnit reporter is confirmed to emit `file`** — verified in Maestro
  2.10.0's `JUnitTestSuiteReporter$TestCase`, which declares a `file: String`
  field. Its *base* is not pinned by that, so the join tries the plausible roots
  rather than assuming one. `scripts/run-maestro.sh` now passes
  `--format=JUNIT --output=build/maestro-results`, because without it nothing in
  the repository produced the XML this system reads. Still unverified end to end:
  no flow has been executed against a real emulator in this environment, so the
  first Android result here will be the first real proof.
- **Summary, priority and case status cannot be repaired** in Kiwi through the
  available RPC surface; drift in those is reported, not fixed. A scenario case
  is nonetheless safe to delete (unlike a legacy one) precisely because Git is
  canonical for it: `just kiwi-seed` rebuilds it. That is the one deletion this
  design makes safe, and it is the reason the standing "surface, never delete"
  rule does not apply to the `Scenarios` plan.
- **Supporting tests remain invisible in v1** — the matrices show user-flow
  automation only. Per-class coverage is Kover's question.

## Links

- `infra/kiwi/traceability/` — the implementation
- `infra/kiwi/scenarios/` — canonical specs
- `docs/testing/coverage-matrix.md` — generated
- `scripts/tests/test_traceability.py` — 94 pure-layer tests
- `2026-10-05-kiwi-and-traceability-structural-debt.md` — the debt this change
  surfaced but deliberately did not fix, with what was already checked
- Issues #149–#158 — the queue for the follow-on work this change left open
- `openspec/changes/scenario-results-are-authoritative-in-ci/` — making a
  scenario's result authoritative in CI, which is the follow-on with a contract
  change rather than a refactor
- `2026-10-05-kiwi-tcms-stand-and-non-test-files.md` — superseded invariant

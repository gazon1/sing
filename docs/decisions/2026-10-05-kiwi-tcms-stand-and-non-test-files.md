---
title: Kiwi TCMS stand, and the four files that never were tests
date: 2026-10-05
status: accepted
tags: [kiwi, tcm, testing, infra, tooling]
---

## Context

The project answers two questions about its own tests: *how much code is covered*
(`scripts/check-coverage.py`, Kover) and *how many test files exist*
(`find … -name '*Test.kt'`). Neither answers the question a maintainer actually asks
before planning work: **what do I intend to verify, and what have I never run?**

`infra/kiwi/` was added to answer it: a local Kiwi TCMS stand where each test class
becomes a TestCase, each Gradle run becomes a TestRun, and a case with no TestExecution
is a visible hole. Kiwi does not run tests — it stores intent and results; Gradle still
executes.

Building it surfaced a defect that outlives the stand itself.

**Four files match `*Test.kt` and never produce a test run.** Found by asking whether
each scanned class appears in any `build/test-results/**/TEST-*.xml`:

| File | Why it never runs |
|---|---|
| `TaskRepositoryContractTest` | `abstract class` — a contract base, subclasses run the scenarios |
| `FileSystemContractTest.kt` | same — and the class is named `FileSystemContract<F : FileSystem>`, i.e. the file carries `Test` and the class does not |
| `RunVmTest` | helper for running a VM under a scope |
| `IsolatedComposeTest` | Compose harness helper |

`sync.py` created a TestCase for each. All four sat permanently in the "cases never run"
list — the report's headline signal. A report whose main number is polluted by files
that *cannot* ever pass is worse than no report: it trains the reader to discount it.

## Idea

Two options were available.

**Filter by name** — skip files under `test/helpers/`, skip classes declared `abstract`.
Cheap. Also wrong twice over: `helpers` is a convention, not a guarantee, and a renamed
helper re-enters the report as a permanent hole; `abstract` alone does not distinguish a
contract base from a genuinely parameterised suite.

**Filter by the property the report actually cares about** — does this file contain a test
method JUnit will execute? That is the same question Gradle asks when it decides whether
to emit a `TEST-*.xml`.

The second won. The test is not a heuristic about naming; it is the *cause* of the
symptom, and a file that gains a `@Test` tomorrow is admitted without anyone updating a
list.

The same ADR records two smaller findings, both of the same species — a check that
asserts less than its comment claims.

## Decision

**1. `is_runnable_test_class()` admits a file only if it declares a runnable test
method and its top-level class is not `abstract`/`open`.**

Runnability is `@Test | @ParameterizedTest | @RepeatedTest | @TestFactory |
@TestTemplate`. The `@ParameterizedTest` arm is load-bearing: `RecurrenceRuleMapperTest`
and `RruleGeneratorTest` are real suites with **no** `@Test` in the file, and an
`@Test`-only filter drops two of the project's genuine test classes.

Result: 259 cases, 4 skipped, and the skip list is printed on every run. 263 → 259
would otherwise read as four lost cases, and the next agent would go looking for them.

**2. Orphan cases get their own report section instead of being deleted.**

A case whose `source_path` no longer exists can never receive a result, so it inflates
"never run" forever. Sync does **not** delete it: deletion in Kiwi is irreversible, and
whether a case is obsolete, mislabelled, or should be rewritten by hand is a human
decision. `gaps.py` reports them under `[3] КЕЙСЫ-БЛИЗНЕЦЫ` with an explicit note that
they are *not* testing gaps, keeping the headline number honest while leaving the
decision to a person.

**3. `TestCase.properties` is fetched in bulk, not per case.**

`get_cases_properties()` collapses the N+1 into one RPC per plan (263 round-trips →
1; measured 3.6 s → 0.05 s). This is not a micro-optimisation: the old form silently
paid the cost on every `sync` and every `gaps`, and it was invisible because both still
"worked".

**4. Two false claims in comments were corrected rather than left standing.**

- `tcms.utils.secrets.get_secret()` reads an **env var**, and follows it as a file path
  when the value starts with `/`. It does **not** scan `/run/secrets/`. The compose file
  mounted `secrets.env` into that directory and the comment claimed Kiwi read it there —
  it never did, and would not have.
- `up.sh` looped 120 times while testing for the timeout at iteration 60, so a hang
  exited 0 and printed "Kiwi готов" over a stand that had not started. The bound is now
  the loop length.

## Rationale

Every item above is the same failure: **something reported success while covering less
than a reader would assume.** The four phantom cases were the most expensive instance,
because the affected artefact is the report a future agent will consult to decide what
to work on next. The corrections are deliberately narrow — no renames, no deletes, no
new abstractions. A wider refactor of the stand would have been harder to review and
could not be verified without a running Kiwi, which is exactly the situation that let
these defects in.

The 4-class list in the test is the point where a future change is forced to be a
deliberate act: adding a fifth non-test named `*Test.kt` breaks
`test_abstract_bases_and_helpers_are_excluded`, and whoever fixes it has to decide
whether the file or the filter is wrong.

## Consequences

`gaps.py` gains a fourth section; its `--json` output gains `orphan_cases`.

`scan_repository()` is no longer a pure filename scan — it reads file contents, so it
gets slower and can report differently if a file is unreadable. At 263 files this is
~0.1 s and irrelevant next to the RPC calls.

Sync does not remove stale cases, so the orphan list grows until a human acts on it. That
is the intended direction of the error: a visible list of four is better than four silent
holes in the coverage number.

Bulk property fetching assumes `case__in` accepts a few hundred ids. Verified at 263;
chunked at 500 in the client, so a 10k-case product degrades to chunks rather than one
enormous response. A future 50k-case product should revisit the chunk size.

Two of the four findings (`get_secret`, the timeout bound) are Kiwi/compose mechanics
with no bearing on project architecture. They are recorded here because the next agent to
touch `infra/kiwi/` will hit them, not because they are decisions.

## Links

- `infra/kiwi/README.md` — stand operation and the full list of Kiwi 16 API sharp edges
- `infra/kiwi/sync.py` — `is_runnable_test_class`, `SKIPPED_NON_TESTS`
- `infra/kiwi/gaps.py` — `report_orphans`
- `infra/kiwi/kiwi_client.py` — `get_cases_properties`, `get_all_cases_properties`
- `scripts/tests/test_kiwi_sync.py` — the four-class list as an assertion

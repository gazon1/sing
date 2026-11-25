# PR review — #3195: чтение блокирующих задач спрашивает границу по первичному ключу

- Repository: gazon1/sing
- Target: PR #3209 — https://github.com/gazon1/sing/pull/3209
- Base → Head: main (a67c6504a758f520e7b66dc74d468bbdf9668c2f) → gazon1/perf-database-measure-the-user-scoped-blocking-t (fc03adcc75aa9e5f8373f9464c0b091268cbb12a)
- Reviewed at: 2026-11-25T11:00Z
- Scope: 6 changed files — one Room DAO query
  (`core-database/src/commonMain/kotlin/com/singularity/todo/core/database/Daos.kt`),
  one new regression test
  (`shared/src/jvmTest/kotlin/com/singularity/todo/core/database/BlockingTasksReadSeamTest.kt`),
  the performance harness
  (`shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/DependencyQueryPerformanceHarness.kt`),
  the architecture report section
  (`docs/architecture/dependency-query-performance.md`), one ADR
  (`docs/decisions/2026-11-25-user-scoped-blocking-tasks-read-scopes-through-the-primary-key.md`),
  one regenerated catalogue line.

## Verdict

APPROVE — both independent reviewer passes (correctness/security and
architecture/scope) returned zero findings and an explicit approve. The rewrite of
`TaskDao.blockingTasksForUser` keeps both ownership checks, the `verb` filter and the
"not completed / not archived" filters, changes neither the DAO signature nor the
schema, and is covered by a regression test that runs the pre-rewrite SQL next to the
DAO on a live Room database. Nothing in either pass requires a code change, so the
branch stayed at `fc03adcc75` and no further commit was made.

## Checks

| Check | Command | Result | Notes |
|---|---|---|---|
| Reviewer A — correctness / security | `10x-chat chat --provider chatgpt --new-chat --timeout 1800000 -p "<role A prompt>"` | PASSED | 100 s, "No blocking findings / No findings", verdict: Approve |
| Reviewer B — architecture / scope | `10x-chat chat --provider chatgpt --new-chat --timeout 1800000 -p "<role B prompt>"` | PASSED | 98 s, "No blocking findings, no major findings", verdict: Approve |
| Local verification (before publication) | `./gw :shared:jvmTest`, `./gw :shared:jvmTest -Ptest.tags=slow`, `./gw :shared:archTest`, `./gw :shared:detekt`, `./gw :core-domain:jvmTest`, `./gw :desktopApp:test`, `./gw :shared:testAndroidHostTest`, `./gw :adr-cli:test`, `./gw :androidApp:compileDebugKotlin`, `mcp-server`, `adr-detekt`, ktlint | PASSED | all green on the head SHA |
| Static gates | `sgate run-all` | PASSED | 65/65 blocking gates |
| Architecture gate | `easement check` | PASSED | 0 violations |
| `:core-database:detekt` | `./gw :core-database:detekt` | FAILED | pre-existing `LargeClass` on `TaskDao`; also fails at base `a67c6504a7` and on `main`'s own CI — not introduced by this PR, deliberately not fixed (splitting the DAO interface is out of scope) |
| PR CI | `gh pr checks 3209` | SKIPPED | commit message carries `[skip ci]`; skipped on the author's explicit instruction (all CI/pre-commit/pre-push checks to be skipped) |

## Findings

No findings from either reviewer. Nothing to fix, nothing to verify further, nothing
to reject.

## Implicit decisions and trade-offs

1. **Both ends of a dependency must independently establish ownership** — intended
   decision
   - Previous behavior or alternative: the edge-owning task was scoped by
     `d.task_id IN (SELECT id FROM tasks WHERE user_id = …)`, the blocker by
     `b.user_id = :userId` inside the join predicate.
   - Evidence and rationale: the rewritten query resolves the edge-owning task by
     `JOIN tasks t ON t.id = d.task_id AND t.user_id = :userId` and the blocker by a
     correlated `EXISTS (SELECT 1 FROM tasks ob WHERE ob.id = b.id AND
     ob.user_id = :userId)`;
     `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/BlockingTasksReadSeamTest.kt`
     covers a foreign-profile blocker and an orphan edge.
   - Consequence: multi-user isolation is unchanged — a dependency crossing a profile
     boundary still exposes nothing.
   - Failure scenario or unresolved risk: a future change that removes either
     ownership check would reintroduce a cross-profile leak.
   - Human confirmation: not needed

2. **The blocker owner check must not sit in a join clause** — intended decision
   - Previous behavior or alternative: the plain-join form (`b.user_id = :userId` in the
     `ON` clause), measured at 1.91–39.33 ms.
   - Evidence and rationale: `EXPLAIN QUERY PLAN` on the join form shows
     `SEARCH b USING INDEX index_tasks_user_id (user_id=?)` — SQLite drives the read
     from the owner's whole task list; the `EXISTS` form uses `sqlite_autoindex_tasks_1`
     on both sides (0.01–0.12 ms on all nine datasets).
   - Consequence: the read's cost no longer scales with the size of the owner's task
     list; no new index is needed.
   - Failure scenario or unresolved risk: SQLite's planner can change across
     versions — the measurement should be re-run if the schema, the indexes or the
     SQLite version change.
   - Human confirmation: not needed

3. **The write side, the DAO signatures and the schema stay untouched** — intended
   decision
   - Previous behavior or alternative: adding an index, changing
     `upsertDependencyForUser`, or introducing a query abstraction were all available.
   - Evidence and rationale: the diff touches only the body of the one `@Query` in
     `core-database/src/commonMain/kotlin/com/singularity/todo/core/database/Daos.kt`;
     `upsertDependencyForUser` and `getBlockingEdgeIdsForUser` are unchanged; the only
     production caller
     (`shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/data/TaskRepositoryImpl.kt`)
     needed no adaptation.
   - Consequence: no migration, no backup-format change, no caller change.
   - Failure scenario or unresolved risk: the optimized read still trusts the existing
     integrity assumptions of the write side; it does not repair malformed dependency
     rows.
   - Human confirmation: not needed

4. **Latency is recorded, never asserted** — intended decision
   - Previous behavior or alternative: a JUnit test with a latency threshold, which
     would make correctness depend on machine load.
   - Evidence and rationale: the harness fails on row-equivalence or scope violations
     (`RunOutcome.mismatch` → non-zero exit) and prints the timings; the regression
     test asserts semantics only.
   - Consequence: ordinary test runs never flake on a slow host.
   - Failure scenario or unresolved risk: a future change that preserves the row set
     but restores the slow plan will not fail any test — someone has to run the
     documented harness.
   - Human confirmation: not needed

5. **The whole-relation equivalence check spells the scope as `EXISTS`, not as the
   `IN`-list** — implicit decision
   - Previous behavior or alternative: repeat the original
     `d.task_id IN (SELECT id FROM tasks WHERE user_id = :userId)` literally in the
     whole-relation comparison.
   - Evidence and rationale: the list form answers the scope question once per
     relation row and took minutes on the 50k profile (it would not finish on 100k);
     the `EXISTS` spelling asks the same question through the primary key. The ADR and
     `docs/architecture/dependency-query-performance.md` both state this.
   - Consequence: the validation stays affordable on large datasets.
   - Failure scenario or unresolved risk: the whole-relation check is not a literal
     execution of the pre-rewrite production SQL — it relies on the independently
     established equivalence of the two scope spellings. The regression test closes
     that gap by running the original SQL verbatim next to the DAO.
   - Human confirmation: not needed

6. **The measurement stays a standalone program, not a JUnit test** — intended
   decision
   - Previous behavior or alternative: a dataset-dependent JUnit test that skips when
     the archive is absent.
   - Evidence and rationale: the harness keeps its own `main`, argument parsing,
     fixture lifecycle and Gradle entry point (`:shared:dependencyQueryPerf`), reusing
     the #3090 generator and `DatabaseTestFixture`.
   - Consequence: the normal test suite does not depend on external performance
     datasets.
   - Failure scenario or unresolved risk: the harness can drift from the test
     conventions if its standalone invocation is not exercised periodically.
   - Human confirmation: not needed

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| The user-scoped blocking-tasks read is measured before its SQL is changed | MET | `docs/architecture/dependency-query-performance.md`, section «Чтение блокирующих задач пользователя ([#3195])»: nine datasets, p50/p95, `EXPLAIN QUERY PLAN` for three query forms |
| The production SQL changes only if the improvement is material | MET | old form 9.72–273.27 ms → new form 0.12–0.41 ms via the DAO (0.01–0.10 ms raw); the plain-join alternative is priced separately and rejected |
| The `verb` filter and the "not completed"/"not archived" filters are unchanged | MET | `core-database/src/commonMain/kotlin/com/singularity/todo/core/database/Daos.kt`, `blockingTasksForUser` — the diff shows only the join/scope lines |
| Write semantics are unchanged | MET | `upsertDependencyForUser` / `getBlockingEdgeIdsForUser` untouched in the diff |
| The new read answers exactly like the old one | MET | `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/BlockingTasksReadSeamTest.kt` — DAO rows compared against the pre-rewrite SQL; the harness compares all forms row-by-row on every read task and over the whole relation on all nine datasets |
| Cross-profile isolation is preserved | MET | the regression test: profile u2 sees none of u1's blockers, sees its own; a foreign blocker is invisible to u1; an orphan edge is invisible |
| The change is documented | MET | the architecture report section and the ADR (plus the regenerated decisions digest) |
| The branch is committed and pushed to the PR | MET | one commit `fc03adcc75`, pushed to `origin/gazon1/perf-database-measure-the-user-scoped-blocking-t`; the PR head is `fc03adcc75` |

## Not verified

- PR CI — skipped on purpose (`[skip ci]` in the commit message, per the explicit
  instruction to skip all CI/pre-commit/pre-push checks). Neither reviewer could
  confirm the reported local verification independently.
- `:core-database:detekt` — red on the head SHA for the pre-existing `LargeClass` on
  `TaskDao`; the same failure exists at the base commit and on `main`.

## Reviewers

| Role | Provider | Verdict | Notes |
|---|---|---|---|
| Correctness / security | chatgpt (Pro) | APPROVE — no findings | 100 s, own `--new-chat`; checked SQL equivalence, NULL handling, duplicates, cross-user isolation, the regression test and the harness |
| Architecture / scope | chatgpt (Pro) | APPROVE — no blocking, no major findings | 98 s, own `--new-chat`; checked the DAO contract, KMP placement, schema/data compatibility, ADR and doc conventions, reuse assessment |

Both roles ran with the same coordinates and project context, in separate
conversations, and neither saw the other's first pass.

Raw answers: [role A](3195-review/role-a-chatgpt.txt),
[role B](3195-review/role-b-chatgpt.txt).

## Remaining risks

- CI was intentionally skipped — the reviewers could not independently confirm the
  local verification run; the branch protection's `CI gate` check will not be
  satisfied until CI runs normally.
- `:core-database:detekt` `LargeClass` on `TaskDao` — pre-existing on `main`,
  accepted as out of scope; it will keep `main` red until split separately.

## Follow-up improvements

No follow-up improvements recommended. Both reviewers judged the reuse/approach
assessment complete and found nothing that warrants a separate issue.

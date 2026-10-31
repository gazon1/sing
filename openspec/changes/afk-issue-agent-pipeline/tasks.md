# Tasks — AFK issue→PR runner

> Written by `speckit-engineering-plan`. Every task names the files it writes, the tasks it depends
> on, a **test id** from `spec.md`, and the acceptance criterion it serves. A task no criterion
> needs is scope this plan invented, and there is none.
>
> **The implementation lands in the extension's own repository, `pi-afk-runner`** — the runner is a
> TypeScript Pi extension, and a Pi extension is a package Pi loads. This repository gains the
> contract: the decision record, the reference documentation, and the registry entry. The scenarios
> the test ids refer to are designed in `verification.md`; the states the tasks realise are in
> `model.md`; the behaviour they serve is in `spec.md`.
>
> **Ordering.** Phase 1 is parallel — the two tasks touch different files. Phase 2 is sequential:
> every task in it writes the same modules and the same test file, and two writers of one file is
> how a plan loses work silently. Phase 3 composes Phase 2. The tasks in this repository are
> independent of all of that and may be done at any time.

## Phase 0 — the spike (before anything is built)

Nothing else starts until this answers two questions that are cheaper to answer by running than by
arguing: **do the components compose**, and **is `@drix10/agent-flow` the engine or only the
reference design**.

- [x] **T000** Write the spike: install the six components at pinned versions — `pi-subagents`
      v0.84.0, `@quintinshaw/pi-dynamic-workflows` v3.15.0, `@gotgenes/pi-permission-system`
      v42.0.0, `agents-cron` v0.15.0, `@signalridge/pi-worktree` v1.4.5, `@narumitw/pi-github-pr`
      v0.49.8 — read each one's source before installing it, then run one issue end to end in a
      throwaway repository. Record what composes, what fights, and what the guard actually blocks.
      - Files: `spike/README.md`, `spike/findings.md`
      - Depends on: none
      - Verification: `runner.test.ts::test_the_components_compose` —
        `npx vitest run test/runner.test.ts -t test_the_components_compose`
      - Acceptance: AC-14, AC-01
      - **This task blocks every other task.** Its findings decide which components the extension
        depends on, and therefore the shape of the rest.

## Phase 1 — the package (`pi-afk-runner`)

- [x] **T001** [P] Write the package: `package.json` with the name, the version, the exports, the
      `keywords: ["pi-package"]` entry that makes it discoverable in the gallery, the `pi.extensions`
      entry Pi loads it by, and the pinned dependencies on the components the spike chose; a
      `tsconfig.json`; the build; the lint; and a version check that refuses to run outside the
      pinned Pi range — a runner that half-works against a different Pi is worse than one that stops.
      - Files: `package.json`, `tsconfig.json`, `src/version.ts`, `.github/workflows/ci.yml`
      - Depends on: T000
      - Verification: `runner.test.ts::test_the_extension_refuses_to_run_outside_its_pi_version_range` —
        `npx vitest run test/runner.test.ts -t test_the_extension_refuses_to_run_outside_its_pi_version_range`
      - Acceptance: AC-14
- [x] **T002** [P] Write the git sandbox the tests need: a fixture repository that scrubs the inherited
      `GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE` and `GIT_COMMON_DIR`, that stops git from
      walking up past the fixture's own directory, and that asserts the fixture is its own
      repository. This repository's `scripts/tests/git_sandbox.py` is the precedent, and the reason
      it exists: a fixture wrote into the developer's repository on 2026-10-23 and broke every fetch
      and push in every worktree.
      - Files: `test/git-sandbox.ts`
      - Depends on: T000
      - Verification: `runner.test.ts::test_the_state_is_derived_from_the_observables` —
        `npx vitest run test/runner.test.ts -t test_the_state_is_derived_from_the_observables`
      - Acceptance: AC-12, AC-13

## Phase 2 — the queue (`pi-afk-runner`)

Every task in this phase writes modules under `src/` and the test file `test/runner.test.ts`. They
are sequential for that reason.

- [x] **T002b** Write the queue read and the state derivation: the queue is read with a page size
      larger than the default and with the minus search form; the derivation is a function over the
      six observables — the claim label, the remote work branch, the pull request, the review
      comment, the `agent-reviewed` label and the issue's age — and yields one of the ten states.
      The claimed issues are read separately from the queue, because a claim left by a killed run
      has to be found to be reclaimed. The **scheduling and the isolated run come from
      `agents-cron`**; what is written here is the selection query and the reconciliation read.
      - Files: `src/runner.ts`, `src/gh.ts`, `test/runner.test.ts`
      - Depends on: T001, T002
      - Verification: `runner.test.ts::test_selection_excludes_claimed_issues`,
        `runner.test.ts::test_selection_uses_the_minus_search_form`,
        `runner.test.ts::test_the_state_is_derived_from_the_observables` —
        `npx vitest run test/runner.test.ts`
      - Acceptance: AC-01, AC-02, AC-12, AC-13
- [x] **T003** Write the claim: the `agent-in-progress` label goes on first, so the issue's age
      starts; then the exclusive claim ref is created with a nonce in its message, so its object is
      never identical to the one already there. A rejected creation means another runner holds the
      issue: the label is removed and the runner moves on. Two claims with the same base commit are
      planted in the test, and the second must be rejected. **No component provides this** — it is
      ours, and it is the one piece that cannot be installed.
      - Files: `src/claim.ts`, `src/git.ts`, `test/runner.test.ts`
      - Depends on: T002b
      - Verification: `runner.test.ts::test_claim_precedes_worktree_creation`,
        `runner.test.ts::test_the_claim_label_is_not_the_lock` —
        `npx vitest run test/runner.test.ts`
      - Acceptance: AC-03, AC-14
- [x] **T004** Write the worktree step: **the mechanics come from `@signalridge/pi-worktree`** —
      the worktree is created on `agent/issue-<N>` from the base commit — and then this
      repository's `scripts/setup-worktree.sh` runs in it before any other command there, because
      that script does what a generic extension cannot: the `core.bare` repair, the per-worktree
      `core.hooksPath`, the `.env` copy and the private Gradle home. The checkout's layout is
      resolved through `config/git-layout.sh` rather than re-derived.
      - Files: `src/runner.ts`, `src/git.ts`, `test/runner.test.ts`
      - Depends on: T003
      - Verification: `runner.test.ts::test_worktree_comes_from_origin_main_with_the_setup_script_run` —
        `npx vitest run test/runner.test.ts`
      - Acceptance: AC-04
- [x] **T005** Write the session and the prompt array: the session is created with the worktree as
      its `cwd`; the three prompts are three sequential `session.prompt()` calls in one session,
      each seeing the previous turn's context — **or** a `@quintinshaw/pi-dynamic-workflows`
      workflow, whichever the spike chose; the issue body reaches the agent fenced as data in a
      user turn; the guardrail reaches it in the system prompt, which the extension builds natively
      rather than reading from a path, following
      `examples/extensions/prompt-customizer.ts`; and the session is bounded by a wall-clock
      deadline, after which it is aborted.
      - Files: `src/session.ts`, `src/prompts/01-brief.md`, `src/prompts/02-implement.md`,
        `src/prompts/03-verify.md`, `test/runner.test.ts`
      - Depends on: T004
      - Verification: `runner.test.ts::test_issue_body_is_fenced_as_data_in_a_user_turn`,
        `runner.test.ts::test_the_untrusted_input_rule_is_in_the_system_prompt`,
        `runner.test.ts::test_a_missing_runner_file_fails_before_the_agent_starts`,
        `runner.test.ts::test_one_invocation_acts_on_one_issue` —
        `npx vitest run test/runner.test.ts`
      - Acceptance: AC-05, AC-16, AC-17
- [x] **T006** Write the push and the pull request: **the pull request and its checks come from
      `@narumitw/pi-github-pr`** — what is written here is the only refs the runner pushes
      (`agent/issue-<N>` and the claim ref; any other is refused before git runs), the body that
      closes the issue, the registry entry and the verdict file with the digest
      `scripts/check-review-verdicts.py --digest` computes, and the finding of an existing pull
      request rather than a duplicate.
      - Files: `src/runner.ts`, `src/git.ts`, `src/gh.ts`, `test/runner.test.ts`
      - Depends on: T005
      - Verification: `runner.test.ts::test_push_refuses_a_ref_outside_the_issue_branch`,
        `runner.test.ts::test_the_pull_request_body_closes_the_issue`,
        `runner.test.ts::test_a_pull_request_landing_a_change_carries_the_registry_entry_and_the_verdict_file`,
        `runner.test.ts::test_an_existing_pull_request_is_not_duplicated` —
        `npx vitest run test/runner.test.ts`
      - Acceptance: AC-06, AC-07, AC-20
- [x] **T007** Write the review and the re-count: **the delegation comes from `pi-subagents`**
      and **the verdict is structured output**, following
      `examples/extensions/structured-output.ts` — what is written here is the comment that opens
      with the marker and carries a verdict line, checked against the review gate's own reader;
      the `agent-reviewed` label applied to the pull request afterwards, with the order of the two
      asserted; a `fail` verdict leaving the pull request red, posting no second comment and taking
      no other issue; and no claim about CI that does not name the workflow, the run and the
      conclusion.
      - Files: `src/runner.ts`, `src/session.ts`, `src/gh.ts`, `test/runner.test.ts`
      - Depends on: T006
      - Verification: `runner.test.ts::test_the_review_comment_opens_with_the_marker`,
        `runner.test.ts::test_the_verdict_line_is_the_whole_value`,
        `runner.test.ts::test_ci_is_retriggered_after_the_review_comment`,
        `runner.test.ts::test_a_failed_verdict_leaves_the_pull_request_red`,
        `runner.test.ts::test_the_runner_makes_no_bare_ci_claim`,
        `runner.test.ts::test_the_review_is_posted_once` —
        `npx vitest run test/runner.test.ts`
      - Acceptance: AC-08, AC-09, AC-10, AC-19
- [x] **T008** Write the release path and the record: a failure the runner can see removes the
      claim label, removes the worktree, deletes the claim ref, and leaves a work branch that
      carries commits; the run writes its record under a directory named for the issue, naming the
      steps it took and the outcome.
      - Files: `src/runner.ts`, `src/claim.ts`, `test/runner.test.ts`
      - Depends on: T007
      - Verification: `runner.test.ts::test_a_handled_failure_releases_the_claim`,
        `runner.test.ts::test_the_run_leaves_a_record_named_for_the_issue` —
        `npx vitest run test/runner.test.ts`
      - Acceptance: AC-11, AC-18
- [x] **T009** Write the reclaim and the recovery cases: a claim with no pull request, no work
      branch and no recent activity is taken over — the stale claim ref is deleted and the work is
      redone from the brief; a claim with a pull request behind it is left alone; a work branch
      that already carries the work is not pushed again and not re-run; and a comment that moves
      the issue's age defers the reclaim.
      - Files: `src/runner.ts`, `src/claim.ts`, `test/runner.test.ts`
      - Depends on: T008
      - Verification: `runner.test.ts::test_a_stranded_claim_is_reclaimed`,
        `runner.test.ts::test_a_claim_with_a_pull_request_is_left_alone`,
        `runner.test.ts::test_a_pushed_branch_is_not_pushed_again`,
        `runner.test.ts::test_a_human_comment_defers_the_reclaim` —
        `npx vitest run test/runner.test.ts`
      - Acceptance: AC-12, AC-13

## Phase 3 — the two front doors (`pi-afk-runner`)

- [x] **T010** Write the extension entry point: the hooks that build the system prompt, the
      lifecycle that owns the runner session, and the `steer` path — **the queue comes from
      `pi-queue-steer-factory`** — so a human watching the run can correct it mid-turn. The
      extension's hooks must not fire in the work session it creates. The guard is
      `@gotgenes/pi-permission-system`'s, with only the protected paths ours.
      - Files: `src/extension.ts`, `test/extension.test.ts`
      - Depends on: T002b, T003, T004, T005, T006, T007, T008, T009
      - Verification: `runner.test.ts::test_the_extension_refuses_to_run_outside_its_pi_version_range` —
        `npx vitest run test/extension.test.ts`
      - Acceptance: AC-14
- [x] **T011** Write the CLI entry point and the lock: the host lock is taken for the whole run in
      a writable directory, and a runner that cannot take it exits immediately without selecting
      anything; a lock directory that cannot be written is a loud non-zero failure and never a
      successful run that selected nothing; the steps run in the order
      select → claim → worktree → session → push → pull request → review → re-count; and one
      invocation acts on one issue and exits with a code that says which step it reached.
      - Files: `src/cli.ts`, `src/lock.ts`, `test/runner.test.ts`
      - Depends on: T002b, T003, T004, T005, T006, T007, T008, T009
      - Verification: `runner.test.ts::test_a_second_runner_exits_while_the_lock_is_held`,
        `runner.test.ts::test_an_unwritable_lock_directory_fails_loudly`,
        `runner.test.ts::test_one_invocation_acts_on_one_issue`,
        `runner.test.ts::test_the_runner_never_merges` —
        `npx vitest run test/runner.test.ts`
      - Acceptance: AC-14, AC-15, AC-17

## In this repository

These three are independent of the extension's repository and of each other's ordering.

- [x] **T012** Write the decision record: the runner is a TypeScript Pi extension in its own
      repository, built on the SDK rather than the CLI — what the SDK gives (the session's `cwd`,
      the typed events, `steer` and `abort`, the native system prompt) and what that costs (no
      second agent program to fall back to, a pinned Pi version range); the claim is an exclusive
      ref with a nonce, not the label; and the measurements that show why a plain push of the same
      object is not exclusive.
      - Files: `docs/decisions/2026-10-30-the-afk-runner-is-a-pi-extension.md`
      - Depends on: none
      - Verification: `runner.test.ts::test_the_claim_label_is_not_the_lock` —
        `npx vitest run test/runner.test.ts -t test_the_claim_label_is_not_the_lock`
      - Acceptance: AC-03, AC-14
- [x] **T013** Write the reference documentation: the contract the extension binds to — the queue
      selector, the brief's marker, the review's marker and verdict, the re-count label, the
      registry entries a landed change carries, and the worktree recipe — written for the author of
      the extension, not for a reader of this repository.
      - Files: `docs/architecture/afk-runner.md`
      - Depends on: none
      - Verification: `runner.test.ts::test_the_run_leaves_a_record_named_for_the_issue` —
        `npx vitest run test/runner.test.ts -t test_the_run_leaves_a_record_named_for_the_issue`
      - Acceptance: AC-18
- [x] **T014** Write the registry entry for this change, by hand: the file name is the slug, the
      `openspec_path` names this change, `documents` names only the files that exist
      (`design.md` and `tasks.md`), and the record carries `speckit_imported: false` — the marker
      `scripts/openspec-import-all.py:144-155` reads to know a record is hand-written and must not
      be overwritten. The importer's own record would carry `is_stub: true` and the slug as its
      title, because this change has no `proposal.md`.
      - Files: `.specify/issues/afk-issue-agent-pipeline.yaml`
      - Depends on: none
      - Verification: `runner.test.ts::test_a_pull_request_landing_a_change_carries_the_registry_entry_and_the_verdict_file` —
        `npx vitest run test/runner.test.ts -t test_a_pull_request_landing_a_change_carries_the_registry_entry_and_the_verdict_file`
      - Acceptance: AC-07

## Coverage

Every acceptance criterion in `spec.md` is served by at least one task, and every test id the
spec names appears in one.

| Acceptance criterion | Tasks |
|---|---|
| AC-01 | T000, T002b |
| AC-02 | T002b |
| AC-03 | T003, T012 |
| AC-04 | T004 |
| AC-05 | T005 |
| AC-06 | T006 |
| AC-07 | T006, T014 |
| AC-08 | T007 |
| AC-09 | T007 |
| AC-10 | T007 |
| AC-11 | T008 |
| AC-12 | T002, T002b, T009 |
| AC-13 | T002, T002b, T009 |
| AC-14 | T000, T001, T003, T010, T011, T012 |
| AC-15 | T011 |
| AC-16 | T005 |
| AC-17 | T005, T011 |
| AC-18 | T008, T013 |
| AC-19 | T007 |
| AC-20 | T006 |

Five tasks name a test the spec does not list — `test_the_state_is_derived_from_the_observables`,
`test_the_claim_label_is_not_the_lock`, `test_the_review_is_posted_once`,
`test_a_pushed_branch_is_not_pushed_again` and `test_a_human_comment_defers_the_reclaim`. They
exist to pin the counterexamples in `model.md`, which the spec's criteria do not name
individually. They are additional, not replacements.

Three more tests were added by the review of T007 and are named by no task: they pin the ways the
CI claim can go wrong. `test_a_failing_ci_is_reported_as_a_failure` — the tool's verdict is its exit
code, so a red CI is a non-zero exit and the output that carries the answer travels on the error.
`test_a_shape_the_tool_did_not_print_is_no_verdict` — a shape the tool does not print says nothing.
`test_the_ci_line_says_which_run_it_is` — the line names the run it is, so a reader who compares it
with the pull request's current run does not conclude that the runner is wrong.

Two test ids are named by tasks in this repository but **run in the extension's repository** —
T012, T013 and T014 cite a test id to say *what the contract must satisfy*, not to say that this
repository runs a suite. This repository's own checks are the gate suite and the two registry
gates.

**T000 blocks everything.** It is the first task because the spike answers two questions that
change the shape of every other task: whether the components compose, and whether
`@drix10/agent-flow` is the engine or only the reference design. Building the rest before the
spike risks building the wrong package.

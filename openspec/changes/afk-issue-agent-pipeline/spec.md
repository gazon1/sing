# Spec — AFK issue→PR runner

> Written by `speckit-engineering-specify`. This document states **what must be true**, never
> **how to make it true**. Choosing between a `Mutex`, an actor-like queue and a transactional lock
> is `design.md`'s decision, unless `investigation.md` already recorded a constraint that forbids
> the alternatives.
>
> Sections marked *(when applicable)* stay in the document. Mark them *Not applicable* with a
> reason — do not delete them. Cancellation, concurrency and persistence are where this codebase's
> real bugs live.

## Summary

The repository has 76 issues labelled `ready-for-agent`, each carrying an agent brief that the
triage skill calls "the authoritative specification that an AFK agent will work from", and no
thing that works them. This change adds a runner that takes one such issue, gives it an isolated
worktree, runs an agent on it, opens a pull request, and reviews that pull request — recording its
progress in GitHub labels so a human can see it, and so a killed run can be recognized and
reclaimed rather than stranding the issue forever.

## Problem

The intake contract exists and is maintained; the executor does not. `.agents/skills/triage/`
defines the states, the brief format and the rules a brief obeys, and a human maintainer moves
issues into `ready-for-agent` by hand. 76 of them are sitting there. Every other piece the runner
would need is also already here: the worktree recipe, the review gate, the verdict-file gate, the
issue registry, the labels. What is missing is the thing that joins them and is safe to run
without a human watching.

Three properties make that "safe" a real engineering problem rather than a slogan.

1. **The input is attacker-writable.** The repository is public, so an issue body is text anybody
   can write. A runner that reads the body as instructions turns any issue into a way to make the
   runner push, comment and open pull requests.
2. **The runner writes to the repository.** It pushes a branch and opens a pull request. That is
   the point, and it is also the blast radius.
3. **The runner is unattended.** A run can be killed between any two steps. The plan's failure
   handling covers a *handled* failure — the script noticed and cleaned up. It does not cover a
   kill: nothing noticed, nothing cleaned up, and the issue keeps the `agent-in-progress` label it
   was claimed with. The queue selector excludes claimed issues, so that issue is now invisible
   to every later run and to the plan's own precheck. It is stuck until a human notices.

Nothing in the repository consumes the `agent-in-progress` label today, so there is no existing
mechanism that would notice it either.

## Scope

- **In:** the runner's contract — its state machine, its guards, its claim and reclaim rules, and
  the promise it makes to this repository (one branch, one pull request, a review the gate
  accepts, the registry entries a landed change carries, never a merge).
- **In:** the contract the runner must honour when it opens a pull request (review marker and
  verdict format, the change registry entry, the verdict file, the CI re-count).
- **In, in this repository:** the decision record, the reference documentation, and the registry
  entry for this change.
- **Out:** the implementation and its test suite — those live in the extension's own repository
  (see [Where the runner lives](#where-the-runner-lives)).
- **Out:** the learning loop, the desktop or Android applications, and any change to an existing
  capability specification.
- **Out:** the content of the agent's prompts beyond the safety rules the runner itself must
  enforce. What the agent is *asked* to do is a prompt-engineering question; what the runner
  *guarantees* about the body it feeds in is a safety question, and only the second is in scope.

### Where the runner lives

**Answered: in its own repository, `pi-afk-runner`, published as a Pi extension.**

The runner is a **TypeScript Pi extension** built on the Pi SDK. Two consequences follow, and both
are decisions rather than accidents:

1. **A Pi extension is a package, not a script in a checkout.** Pi loads extensions from
   `~/.pi/agent/extensions/` or through `pi install`, so the runner needs a `package.json`, a
   version, a build and a release. Putting a JavaScript toolchain into a Kotlin Multiplatform
   application repository that has none — no `package.json`, no `tsconfig.json` anywhere in this
   checkout — would put the extension's code under the same review and gates as the app it
   operates on. A separate repository keeps those apart.
2. **This change directory is therefore the *record* of the contract, not the place the code
   lands.** The implementation and its test suite are applied in the extension's repository. The
   acceptance criteria below name test ids in **that** repository's suite; the commands say so.

The binding between the two repositories is a contract, and it is written down on both sides —
the formats this repository owns, and the extension's reliance on them:

| This repository guarantees | The extension relies on |
|---|---|
| `ready-for-agent` is the queue selector | the label exists and is human-applied |
| a brief is a comment opening with `## Agent Brief` | `.agents/skills/triage/AGENT-BRIEF.md:42` |
| a review opens with `<!-- agent-review -->` and carries a verdict line | `scripts/ci/check-agent-review.py:137-238` |
| `agent-reviewed` re-counts the review gate | `.github/PULL_REQUEST_TEMPLATE.md:38-39` |
| a landed change carries a registry entry and a verdict file | `scripts/check-issue-registry.py:28-35`, `scripts/check-review-verdicts.py:19-29` |
| a worktree needs `scripts/setup-worktree.sh` | `scripts/setup-worktree.sh:5-8` |

The operational isolation the plan wants is preserved regardless of which repository holds the
code: the runner never runs in the main checkout, it works in a throwaway worktree, and it pushes
only a reserved branch prefix.

### Capability specification

`skip_specs: true` stands. No capability specification in `openspec/specs/` describes the
issue→pull-request workflow, and this change alters none of the ones that come closest:
`change-review-integrity` (the verdict file and the CI claim) is *consumed* by the runner, not
modified; `branch-hygiene` governs a different tool; `gate-tooling` governs the gate suite, which
the runner does not join; `agent-toolbox-surface` and `ai/external-agent` govern the application's
own agent surfaces, not a CI-side queue runner.

## Non-goals

- **Not a CI workflow and not a gate.** The runner is a scheduled process that acts; it is not a
  check that runs on every commit. It is therefore not registered in the gate registry, and it
  does not need the four declarations a gate needs (`docs/architecture/registering-a-gate.md:28`).
  It does need its own unit test and a positive control.
- **Never auto-merges.** A human merges. The `agent-reviewed` label exists so a *review* is
  re-counted, not so a change lands unattended.
- **Not a replacement for the triage skill or the agent brief.** The runner consumes them.
- **Not a state store.** No database, no JSON journal, no `state.ts`. The state lives in GitHub
  and in git, where a human can already see it.
- **Not a TypeScript rewrite.** One script, in this repository's language.
- **Not the Pi live-chat extension.** That is a separate change.

## Current behavior

Facts, cited.

- The `ready-for-agent` label exists with the description "Spec is ready for an agent to
  implement", and **76** open issues carry it.
  (`investigation.md:147-149`, `investigation.md:169-172`)
- The agent brief is the contract: "the authoritative specification that an AFK agent will work
  from". (`.agents/skills/triage/AGENT-BRIEF.md:3`) The triage states are
  `needs-triage → needs-info → ready-for-agent → ready-for-human → wontfix`
  (`.agents/skills/triage/SKILL.md:33-45`).
- **Nothing in the repository consumes the `agent-in-progress` label.** A search across the
  repository finds it only in the plan and in this change's own documents; no script, skill or
  workflow reads it. The label exists, and no code acts on it.
- The `agent-reviewed` label exists and its re-count is already documented for humans in
  `.github/PULL_REQUEST_TEMPLATE.md:38-39`, `.github/workflows/ci.yml:1369-1371`,
  `docs/architecture/verification.md:126-128` and
  `.agents/skills/code-review-pr-workflow/SKILL.md:295-300`.
- The review gate requires the marker `<!-- agent-review -->` as the **first non-blank line** of
  a comment (`scripts/ci/check-agent-review.py:164-178`), a verdict line that is exactly
  `**Verdict:** pass` or `**Verdict:** fail` (`:146-149`), and the **newest** marked comment
  decides (`:216-238`). A `fail` verdict fails the check (`:233-237`). The check does not judge
  the comment's author (`:88-94`).
- The `Agent review` job runs on `pull_request` (`.github/workflows/ci.yml:1364-1395`), and
  `ci-gate` accepts only `success` or `skipped` (`:1418-1437`).
- Every active change needs `.specify/reviews/<slug>.md` with six frontmatter fields, and the
  `documents` digest is computed by the gate itself
  (`scripts/check-review-verdicts.py:19-29`, `:144-161`). Every change directory — archived ones
  included — needs `.specify/issues/<slug>.yaml` pointing back by slug
  (`scripts/check-issue-registry.py:28-35`).
- A pull request that moves a change's contract must carry a verdict naming **that** pull request
  (`scripts/ci/check-agent-review.py:313-354`,
  `scripts/src/sgate/gatekit/verdicts.py:195-220`).
- The worktree recipe is mandatory once per worktree and performs the `core.bare` repair, the
  per-worktree `core.hooksPath`, the `.env` copy, the per-checkout venv and the private Gradle
  home (`scripts/setup-worktree.sh:5-8`, `:182-183`, `:268-333`, `:335`). `./gw` exports the
  private `GRADLE_USER_HOME` (`gw:84-85`).
- `gh issue list` returns **30** results by default; `--search '-label:X'` excludes a label while
  `--search 'no:label:X'` returns zero results; there is no `--filter` flag.
  (`investigation.md:147-158`)
- Adding a label is idempotent — a second add exits 0 — so the claim label is not a
  compare-and-set and cannot by itself stop two runners taking the same issue.
- The repository already holds automation of exactly this kind: a standalone Python tool with its
  own unit test and its own capability spec (`scripts/prune-merged-branches.py`,
  `scripts/tests/test_prune_merged_branches.py`, `openspec/specs/branch-hygiene/spec.md`), and
  scripts that read and write GitHub (`scripts/refresh-issues-snapshot.py`).
- The gate scripts' own tests live in `scripts/tests/` and have one entry point,
  `python3 scripts/run-gate-tests.py` (`docs/architecture/tests.md:44-48`).
- No runner exists. There is no runner script, no prompt directory and no skill that does this.
- The plan this change realizes contains ten factual errors, corrected in place, with the
  evidence in `investigation.md` (discrepancies D1-D11).

## Required behavior

Decisions, not facts.

1. **One issue per invocation.** A run selects at most one issue and works it to the end of its
   journey. A crash loses one issue, not the queue.
2. **Selection** reads the open issues labelled `ready-for-agent`, excludes those carrying
   `agent-in-progress`, and pages past the default result limit so that the whole queue is
   visible, not its first page.
3. **Claim.** The runner marks the selected issue `agent-in-progress` **before** it creates the
   worktree, so that a human watching the tracker sees which issue is in flight, and so that a
   later run's selection skips it.
4. **Isolation.** The runner works in a git worktree created from `origin/main` on a branch named
   `agent/issue-<N>`, and runs this repository's `scripts/setup-worktree.sh` inside it before any
   other command. The runner never modifies the main checkout.
5. **The agent runs in the worktree, in a session the runner owns.** The runner creates the agent
   session with the worktree as its working directory and drives it through the session's own
   prompt API. The three prompts of the array are three sequential turns in one session, each
   seeing the previous turn's context. The runner never changes the session's directory after
   creating it.
6. **The issue body is data, not instructions.** The body reaches the agent fenced and labelled as
   untrusted input, and the rule that says so sits above the conversation — in the system prompt —
   rather than in a user turn where it would compete with the injection. Only issues carrying
   `ready-for-agent` are ever picked up, and that label is applied by a human.
7. **The runner pushes two refs, and only two.** The work branch `agent/issue-<N>`, and the
   exclusive claim ref that holds the issue. It never pushes to `main`, never pushes another tag,
   and never force-pushes.
8. **The pull request** is opened against `main` from `agent/issue-<N>` and its body closes the
   issue. When the pull request lands a change in this repository, it also carries that change's
   registry entry and its verdict file naming that pull request, with the `documents` digest
   computed by the same tool the gate uses.
9. **The pull request carries its own review.** A second agent — one that did not write the
   change — reviews it and posts a comment that opens with the review marker and carries a
   verdict line. The comment is written to satisfy the gate as it is implemented: marker first,
   verdict as the whole value of its line.
10. **The review is re-counted.** After the comment lands, the runner re-triggers the pull
   request's CI. Posting a comment does not start a run, and a run started by the comment has its
   checks counted on the default branch rather than on the pull request.
11. **A `fail` verdict is not a retry.** The runner leaves the pull request red, leaves the
    worktree in place, and reports the findings. It does not post another verdict over it and
    move on.
12. **The runner never merges.**
13. **A handled failure releases the claim.** If the run fails in a way the runner can see, it
    removes the `agent-in-progress` label and returns the issue to the queue.
14. **A killed run does not strand an issue.** A claim left behind by a run that died without
    cleaning up is reclaimable: a later run recognizes a claim that has no pull request, no
    branch and no recent activity, and takes the issue over rather than skipping it forever.
15. **The runner's progress is observable without reading its logs.** Which issue is in flight,
    and how far it got, is visible from the issue's labels and the pull request.
16. **The runner is loud about its own failures.** A runner that cannot take the lock, or that
    cannot find its own files, fails loudly and non-zero. It must never exit successfully having
    done nothing.
17. **The runner writes its own record.** What it did is logged under a directory named for the
    issue, in the shape this repository already uses for diagnostics.

## Invariants

### INV-01 — One runner per issue

At most one runner acts on a given issue at a time.

*Why:* two runners on one issue produce two worktrees, two pushes of the same branch name, and two
pull requests for the same issue, and the second push either fails or overwrites the first. The
claim label cannot enforce this by itself — adding a label is idempotent, so both runners would
"win" it. Whatever enforces this must be an exclusive operation, not a marker.

### INV-02 — No claim is stranded

Every claim on an issue is either released by the run that took it, or is reclaimable by a later
run.

*Why:* the queue selector excludes claimed issues, so a claim that is neither released nor
reclaimable removes the issue from every future run's view — permanently, and silently. The
repository is public and the runner is unattended, so "silently and permanently" is the normal
failure mode of a killed run.

### INV-03 — The runner writes only its own branch

The only ref the runner pushes is `agent/issue-<N>` for the issue it is working.

*Why:* the runner reads attacker-writable text. If the text could name a ref, the blast radius
of a successful injection would be the whole repository's history.

### INV-04 — The issue body is never executed as an instruction

No text from an issue body is treated as a command, a tool call, or a rule that changes the
runner's behaviour.

*Why:* the repository is public. Any user can open an issue, and the only thing separating their
text from the runner's credentials is the rule that their text is data.

### INV-05 — The runner never merges and never weakens a gate

The runner does not merge, does not approve, does not modify CI configuration, and does not
change what a gate checks.

*Why:* the review gate exists so that a change is looked at by something other than its author.
A runner that merges its own work makes the gate theatre.

### INV-06 — Every pull request the runner opens carries a review and a re-counted CI

A pull request the runner opens carries a review comment that satisfies the gate, and its CI is
re-triggered after that comment lands.

*Why:* a pull request whose gate cannot go green is a pull request that cannot merge, and the
failure is invisible to the runner — it is red, not failed.

### INV-07 — The runner's state is observable from outside itself

Which issue is in flight, and how far the run got, is readable from the issue's labels and the
pull request, without reading the runner's logs.

*Why:* the runner is unattended and may die. The person who has to clean up after it cannot
reconstruct its state from a log directory on a machine nobody is watching.

### INV-08 — A run touches one issue

A single invocation acts on at most one issue.

*Why:* this is what makes a crash bounded. It is the property that turns "the runner died" from
a queue-wide event into an issue-local one.

## Failure semantics

One row per failure the model describes.

| Failure | State left behind | Recoverable by | The runner observes |
|---|---|---|---|
| The lock cannot be taken | nothing | the next run | a non-zero exit, naming the lock |
| The queue is empty | nothing | the next run | a zero exit, saying it selected nothing |
| The claim cannot be applied | nothing claimed | the next run | a non-zero exit naming the issue |
| The worktree cannot be created | the claim | this run, by releasing it | a non-zero exit |
| The setup script fails | the claim and the worktree | this run, by releasing the claim and removing the worktree | a non-zero exit naming the script |
| The agent fails or is killed | the claim and the worktree | this run, if it can see the failure; otherwise INV-02's reclaim | a non-zero exit, or nothing at all |
| The push fails | the claim, the worktree, the local commits | this run, by releasing the claim | a non-zero exit naming the ref |
| The pull request cannot be opened | the claim, the branch | this run, by releasing the claim | a non-zero exit |
| The review agent fails | the pull request exists and is red | a human — the pull request is the artifact | a non-zero exit |
| The review verdict is `fail` | the pull request exists and is red | a human | a zero exit naming the verdict |
| The re-count cannot be applied | the pull request exists and is red | a human, or the next push | a non-zero exit |
| The process is killed | the claim, and whatever the run had done | a later run, by reclaiming | nothing — which is why INV-02 exists |

## Cancellation and lifecycle

*(when applicable)*

The runner is a process, not a coroutine, so cancellation means termination — but the
distinction between **handled** and **unhandled** termination is the whole of this section.

- **SIGTERM** (a scheduler stopping the timer, an operator) is a handled termination. The runner
  releases the claim, removes the worktree, and exits non-zero.
- **SIGKILL, a power loss, an OOM kill** are unhandled. No handler runs. The claim stays, the
  worktree stays, and the branch may or may not have been pushed. This is the case INV-02 is
  about, and it is the reason the reclaim rule is a requirement rather than a convenience.
- **Cancellation between a mutation and its side effect** is the sharp case. The runner's
  mutations are external and observable: the claim (a label), the branch (a git ref), the pull
  request, the comment. None of them rolls back. The runner's contract is therefore *forward*
  recovery, not rollback: a later run re-derives what happened from GitHub and from git, and
  either continues from there or takes the issue over. Nothing in the design may depend on a
  rollback that cannot happen.
- **Ownership.** The runner owns one worktree for the duration of one run and is responsible for
  removing it. It does not own the main checkout and must not modify it.

## Concurrency and ordering

*(when applicable)*

- **What can run in parallel.** Two runner invocations on the same host. The queue, the tracker
  and the repository are shared; the worktrees are not.
- **What serializes them.** An exclusive lock held for the whole run, in a directory that is
  writable — `/var/lock` is not writable by a normal user here, and a lock file that cannot be
  opened must fail loudly rather than let the run proceed unlocked.
- **What the lock does *not* do.** A host-local lock does not exclude a runner on another host.
  INV-01 is therefore not satisfied by the lock alone: it also needs an exclusive operation
  against a shared system. Which one is `design.md`'s decision; the constraint recorded here is
  that it must be an operation that cannot succeed twice for the same issue — a marker is not
  one, because adding a label is idempotent.
- **Ordering.** The claim happens before the worktree, the worktree before the agent, the agent
  before the push, the push before the pull request, the pull request before the review, the
  review before the re-count. Every one of those orders exists because a later step's artifact is
  what an earlier step's recovery needs.
- **Re-entrancy.** The same event may arrive twice: a second timer fire while a run is in
  progress, and the Phase-2 extension taking an issue the timer is working. Both are the same
  question, and INV-01 answers it.
- **Read-modify-write windows.** Selecting and then claiming is exactly such a window: two
  runners can select the same issue before either claims it. The claim must therefore not be the
  thing that decides, or must be made exclusive.

## Persistence and recovery

*(when applicable)*

- **What is persisted.** The issue's labels; the branch `agent/issue-<N>`; the pull request; the
  review comment; the worktree on disk. All of it is outside the runner, and all of it is
  readable by a human.
- **What is not.** The runner keeps no journal, no state file and no database. Its own memory
  does not survive the process, by design.
- **How a run recovers.** By *re-deriving*, not by replaying. A later run asks GitHub and git what
  the earlier run did, and continues from that point. This is what makes the runner's state
  survive its own death.
- **Idempotence.** Applying the claim twice is safe — adding a label is idempotent. Creating the
  branch twice is not safe in general, and the worktree creation must not destroy an existing
  worktree. Opening the pull request twice is not safe: the second attempt must find the first
  pull request rather than open another. Applying the re-count twice is safe — adding a label is
  idempotent.
- **Replay after a restart.** The same issue can be picked up again after a crash. The reclaim
  rule is what makes that a continuation rather than a duplicate: an issue whose claim has no
  pull request, no branch and no recent activity is taken over, and an issue whose pull request
  already exists is left alone.
- **Terminal states.** For one issue, the terminal states are: a pull request open and reviewed
  (the runner is done; a human merges), and a handled failure (the claim released, the issue back
  in the queue). There is a path back from the second. There is none from the first — and that is
  correct, because a pull request awaiting a human is not the runner's to withdraw.

## Compatibility constraints

- **The review gate is not negotiable.** The comment format is fixed by
  `scripts/ci/check-agent-review.py:137-238`, and the runner must satisfy it as implemented.
- **The verdict gate and the issue registry are not negotiable.** A pull request that lands a
  change carries the registry entry and the verdict file, and the verdict's `documents` digest is
  computed by `scripts/check-review-verdicts.py --digest <slug>` — the same implementation the
  gate uses, so the recorded value and the checked value cannot disagree
  (`openspec/specs/change-review-integrity/spec.md:29-31`).
- **The runner makes no claim about CI.** `change-review-integrity` REQ-2 requires a claim about
  CI to name the workflow, the run and the conclusion
  (`openspec/specs/change-review-integrity/spec.md:59-71`). The runner does not report CI status
  at all — it opens a pull request and stops — so it makes no such claim and changes nothing
  about REQ-2.
- **The worktree recipe keeps its contract.** `scripts/setup-worktree.sh` is run, not
  reimplemented, and the runner does not change what it does.
- **The gate suite is untouched.** The runner is not a gate, is not registered in the gate
  registry, and changes no gate's behaviour.
- **No capability specification changes.** `change-review-integrity`, `branch-hygiene`,
  `gate-tooling`, `agent-toolbox-surface` and `ai/external-agent` all stay as they are.
- **The application is untouched.** No Kotlin, no Compose, no Room schema, no Koin graph.

## Acceptance criteria

- [ ] **AC-01:** When the queue is read, the system must exclude issues carrying
      `agent-in-progress` and must request a page size larger than the default, so that the whole
      queue is visible rather than its first page.
      `Verification:` `runner.test.ts::test_selection_excludes_claimed_issues` —
      `npx vitest run test/runner.test.ts -t test_selection_excludes_claimed_issues`
- [ ] **AC-02:** When the queue is read, the system must use the minus search form, because the
      `no:label:` form returns nothing.
      `Verification:` `runner.test.ts::test_selection_uses_the_minus_search_form` —
      `npx vitest run test/runner.test.ts -t test_selection_uses_the_minus_search_form`
- [ ] **AC-03:** When an issue is selected, the system must apply the `agent-in-progress` label
      before it creates the worktree.
      `Verification:` `runner.test.ts::test_claim_precedes_worktree_creation` —
      `npx vitest run test/runner.test.ts -t test_claim_precedes_worktree_creation`
- [ ] **AC-04:** When the worktree is created, it must be created from `origin/main` on the branch
      `agent/issue-<N>`, and `scripts/setup-worktree.sh` must have run in it before any other
      command executes there.
      `Verification:` `runner.test.ts::test_worktree_comes_from_origin_main_with_the_setup_script_run` —
      `npx vitest run test/runner.test.ts -t test_worktree_comes_from_origin_main_with_the_setup_script_run`
- [ ] **AC-05:** When the issue body is handed to the agent, it must arrive fenced and labelled as
      untrusted data, and the instruction that says so must be in the system prompt rather than in
      a user turn.
      `Verification:` `runner.test.ts::test_issue_body_is_fenced_as_data_in_a_user_turn` and
      `runner.test.ts::test_the_untrusted_input_rule_is_in_the_system_prompt` —
      `npx vitest run test/runner.test.ts -t test_issue_body_is_fenced_as_data_in_a_user_turn`
- [ ] **AC-06:** When the runner pushes, it must push only `agent/issue-<N>`, and it must refuse
      any other ref.
      `Verification:` `runner.test.ts::test_push_refuses_a_ref_outside_the_issue_branch` —
      `npx vitest run test/runner.test.ts -t test_push_refuses_a_ref_outside_the_issue_branch`
- [ ] **AC-07:** When the pull request is opened, its body must close the issue, and when it lands
      a change in this repository it must carry that change's registry entry and a verdict file
      naming that pull request, with the digest the gate computes.
      `Verification:` `runner.test.ts::test_the_pull_request_body_closes_the_issue` and
      `runner.test.ts::test_a_pull_request_landing_a_change_carries_the_registry_entry_and_the_verdict_file` —
      `npx vitest run test/runner.test.ts -t test_the_pull_request_body_closes_the_issue`
- [ ] **AC-08:** When the review comment is posted, it must open with the review marker and carry
      a verdict line whose value is exactly `**Verdict:** pass` or `**Verdict:** fail`.
      `Verification:` `runner.test.ts::test_the_review_comment_opens_with_the_marker` and
      `runner.test.ts::test_the_verdict_line_is_the_whole_value` —
      `npx vitest run test/runner.test.ts -t test_the_review_comment_opens_with_the_marker`
- [ ] **AC-09:** When the review comment has been posted, the system must re-trigger the pull
      request's CI, so that the review is counted against the pull request and not the default
      branch.
      `Verification:` `runner.test.ts::test_ci_is_retriggered_after_the_review_comment` —
      `npx vitest run test/runner.test.ts -t test_ci_is_retriggered_after_the_review_comment`
- [ ] **AC-10:** When the review verdict is `fail`, the system must leave the pull request open and
      red, must not post another verdict over it, and must not take another issue.
      `Verification:` `runner.test.ts::test_a_failed_verdict_leaves_the_pull_request_red` —
      `npx vitest run test/runner.test.ts -t test_a_failed_verdict_leaves_the_pull_request_red`
- [ ] **AC-11:** When a run fails in a way it can see, the system must release the claim so the
      issue returns to the queue.
      `Verification:` `runner.test.ts::test_a_handled_failure_releases_the_claim` —
      `npx vitest run test/runner.test.ts -t test_a_handled_failure_releases_the_claim`
- [ ] **AC-12:** When a later run finds a claim with no pull request, no branch and no recent
      activity, the system must take that issue over rather than skip it forever.
      `Verification:` `runner.test.ts::test_a_stranded_claim_is_reclaimed` —
      `npx vitest run test/runner.test.ts -t test_a_stranded_claim_is_reclaimed`
- [ ] **AC-13:** When a claim has a pull request behind it, the system must leave it alone.
      `Verification:` `runner.test.ts::test_a_claim_with_a_pull_request_is_left_alone` —
      `npx vitest run test/runner.test.ts -t test_a_claim_with_a_pull_request_is_left_alone`
- [ ] **AC-14:** When two runners start on one host, the second must exit immediately without
      selecting an issue, and it must say so.
      `Verification:` `runner.test.ts::test_a_second_runner_exits_while_the_lock_is_held` —
      `npx vitest run test/runner.test.ts -t test_a_second_runner_exits_while_the_lock_is_held`
- [ ] **AC-15:** When the lock cannot be taken because its directory is not writable, the system
      must fail loudly and non-zero, never exit successfully having selected nothing.
      `Verification:` `runner.test.ts::test_an_unwritable_lock_directory_fails_loudly` —
      `npx vitest run test/runner.test.ts -t test_an_unwritable_lock_directory_fails_loudly`
- [ ] **AC-16:** When the runner's own files are missing, the system must fail before the agent
      starts.
      `Verification:` `runner.test.ts::test_a_missing_runner_file_fails_before_the_agent_starts` —
      `npx vitest run test/runner.test.ts -t test_a_missing_runner_file_fails_before_the_agent_starts`
- [ ] **AC-17:** When the runner runs, it must act on at most one issue, and it must never merge.
      `Verification:` `runner.test.ts::test_one_invocation_acts_on_one_issue` and
      `runner.test.ts::test_the_runner_never_merges` —
      `npx vitest run test/runner.test.ts -t test_one_invocation_acts_on_one_issue`
- [ ] **AC-18:** When the runner finishes, it must have left a record of what it did under a
      directory named for the issue.
      `Verification:` `runner.test.ts::test_the_run_leaves_a_record_named_for_the_issue` —
      `npx vitest run test/runner.test.ts -t test_the_run_leaves_a_record_named_for_the_issue`
- [ ] **AC-19:** When the runner reports anything about CI, it must name the workflow, the run and
      the conclusion — or say nothing at all.
      `Verification:` `runner.test.ts::test_the_runner_makes_no_bare_ci_claim` —
      `npx vitest run test/runner.test.ts -t test_the_runner_makes_no_bare_ci_claim`
- [ ] **AC-20:** When the runner is given an issue whose pull request already exists, it must not
      open a second one.
      `Verification:` `runner.test.ts::test_an_existing_pull_request_is_not_duplicated` —
      `npx vitest run test/runner.test.ts -t test_an_existing_pull_request_is_not_duplicated`

_Rules for this list:_

- _Name a **resolvable test id**, not "add a test". The test does not exist yet — `verify-design`
  designs it and `implement` writes it. Binding to a named test is what makes the binding checkable
  rather than aspirational._
- _Assert on something **observable**: a returned value, a persisted row, an emitted event, a logged
  line. "The system must be correct" is not observable._
- _Number sequentially from `AC-01`. Never reuse a number — the gate that used to check this
  was removed on 2026-10-19, and reused identifiers produced a traceability matrix that
  confidently reported the wrong requirement._

## Open questions

1. ~~**Does the runner live in this repository or in a separate one?**~~ **Answered: a separate
   repository, `pi-afk-runner`, published as a Pi extension.** A Pi extension is a package Pi
   loads, so it needs a version, a build and a release; and a JavaScript toolchain inside a
   Kotlin Multiplatform application repository that has none would put the extension's code under
   the same gates as the app it operates on. See [Where the runner lives](#where-the-runner-lives).
2. **What makes the claim exclusive across hosts?** A host-local lock excludes two runners on one
   machine; it excludes nothing across machines. The label cannot (adding a label is idempotent).
   **Answered in `design.md`:** an exclusive remote ref, created with a nonce so that a second
   creation is always rejected rather than silently accepted.
3. **How long is a claim stale before it may be reclaimed?** INV-02 needs a window. Too short and
   a slow run loses its issue to a second runner; too long and a killed run keeps the issue out of
   the queue for hours.
4. **What is the runner's cost ceiling?** The extension drives Pi's own model registry, so the
   ceiling is a wall-clock deadline on the session, not a step count: the session is aborted when
   the deadline passes. The runner's provider is configured at deployment.
5. ~~**Which harness is the default?**~~ **Answered: Pi's SDK, and only Pi's SDK.** The runner is a
   Pi extension; the session it creates is a Pi session, so the harness is Pi and the model comes
   from Pi's registry. The old `mcode exec` fallback is gone with it — an extension cannot fall
   back to a different agent program, because it *is* inside the agent program.
6. **Does the runner report CI status at all?** This spec says no, which keeps
   `change-review-integrity` REQ-2 untouched. If it must report CI, REQ-2's shape applies and the
   claim has to name the workflow, the run and the conclusion.
7. **What token scope does the runner need?** Pushing `agent/*` and opening pull requests need
   more than reading issues. The plan defers this to its phase 4.
8. **Which issues are safe for the runner to take?** All 76 carry a brief, but a brief is a
   human's judgement about whether the work is specified, and some of those issues may need
   judgement the runner does not have. The `ready-for-agent` label is the only filter, and it is
   applied by a human — but the runner's failure modes are not the same as a human's.
9. **Which Pi version does the extension target?** The SDK surface it uses
   (`createAgentSession`, `prompt`, `steer`, `abort`, the typed events) is versioned with Pi, and
   the hobby projects in this space all pin one version. The extension must pin a range and check
   it at start-up. See the plan's "Not verified".

## References

- `docs/plans/2026-10-30-afk-issue-agent-pipeline.md` — the plan this change realizes, revised
  against `investigation.md`
- `openspec/changes/afk-issue-agent-pipeline/investigation.md` — the facts, the discrepancies
  D1-D11 and the open questions behind this spec
- `.agents/skills/triage/SKILL.md` — the issue state machine the runner selects from
- `.agents/skills/triage/AGENT-BRIEF.md` — the brief format the runner's input is expected to
  have
- `.agents/skills/code-review-pr-workflow/SKILL.md` — the review contract the runner satisfies
- `scripts/ci/check-agent-review.py` — the gate the review comment must satisfy
- `scripts/check-review-verdicts.py` — the verdict-file gate and the digest the runner records
- `scripts/check-issue-registry.py` — the registry entry a landed change carries
- `scripts/setup-worktree.sh` — the worktree recipe the runner runs
- `scripts/prune-merged-branches.py` and `openspec/specs/branch-hygiene/spec.md` — the
  precedent: a standalone tool in `scripts/` with its own unit test and its own capability spec
- `docs/architecture/tests.md` — where the script tests live and the single entry point
- `docs/architecture/registering-a-gate.md` — what a gate needs, and therefore what this is not
- `openspec/specs/change-review-integrity/spec.md` — the verdict and CI-claim contracts this
  change consumes and does not modify
- `docs/decisions/2026-10-04-one-gate-recipe.md` — the gate recipe the runner's prompts name
- `.github/workflows/ci.yml` — the `Agent review` job and the `ci-gate` assertion

# Model — AFK issue→PR runner

> Written by `speckit-engineering-model`. The transition table's columns deliberately mirror jqwik's
> state-machine model (`Action`, precondition, `Transformer.mutate`, `withInvariant`), so that
> migrating to property-based testing later is mechanical rather than a rewrite.
>
> **This step always produces a file.** Either a model, or a justified *Not applicable*. An empty
> document, or a table invented to fill the template, is a worse outcome than an honest omission.

> **This change has state.** All four state tests came back yes: a value (the claim, the branch,
> the pull request, the review comment) is held across events and read by later ones; the same
> event arriving twice gives a different outcome depending on what happened before; work can be
> interrupted and taken over; and two callers can arrive. So this is a model, not a *Not
> applicable*.
>
> **This model is a proposal.** The runner does not exist yet. Every state and transition below is
> specified behaviour, and each one names the `AC-NN` in `spec.md` that requires it. The
> `review-spec` gate checks it; nothing here has been executed.

## Purpose and scope

**What is modelled.** The journey of **one issue** through the runner, and the reconciliation the
runner performs when it finds that journey already partly travelled.

**The model boundary.** The runner's handling of one issue, from the moment it selects that issue
to the moment it stops touching it. Concretely: the issue's labels, the branch
`agent/issue-<N>`, the pull request, and the review comment are **inside** the boundary — they are
the state, and they live outside the runner so that they survive the runner's death. The git
worktree on the local disk is **outside** the boundary as state: it is a side effect with its own
cleanup, and its contents are not recoverable state (the agent runs with `--no-session`, so an
interrupted agent's context is gone — `docs/plans/2026-10-30-afk-issue-agent-pipeline.md:404-405`).

**Why the state lives outside the runner.** The runner keeps no journal and no state file. Its
state is *derived* by observing GitHub and git. That is the whole of the recovery story: a later
run does not replay a log, it asks the world what the dead run did.

**Deliberately outside the boundary.**

- The **agent's own internal state** — its context window, its turns, its tool calls. The runner
  sees a process exit code and a stream of events; nothing else.
- The **prompt array's internal sequence**. The three prompts are one invocation to the runner;
  the split between them is the agent's business.
- The **content of the change** the agent produces.
- **The Phase-2 live-steering extension**, which acts on the same issue in the same run and is a
  separate change.

## Model boundary

| Inside | Outside |
|---|---|
| The issue's labels | The agent's conversation and context |
| The branch `agent/issue-<N>` on the remote | The worktree's contents on disk (a side effect) |
| The pull request and its labels | The pull request's *content* — the diff, the commits |
| The review comment | The reviewer's reasoning |
| The runner's own claim on the issue | The Phase-2 extension's actions |

## States

The state names the runner's *derived* view of one issue. "Derived" is the operative word: every
state below is computable from the observables in [Events and triggers](#events-and-triggers),
and none of them is stored by the runner.

| State | Meaning | Reachable from |
|---|---|---|
| `Queue` | The issue is eligible: labelled `ready-for-agent`, not claimed, no durable work behind it. | initial / `Release` / `Reclaim` |
| `Claimed` | The claim label is applied. The run owns the issue. Nothing durable exists yet. | `Claim` / `Reclaim` |
| `Isolated` | The worktree exists on `agent/issue-<N>` from `origin/main`, and the setup script has run in it. | `Prepare` |
| `Working` | The agent session is running in the worktree. | `Start` |
| `Ready` | The agent exited and the worktree holds commits. Nothing is durable outside this host. | `AgentEnds` |
| `Pushed` | The branch is on the remote. The work is now visible to everyone. | `Push` |
| `Opened` | The pull request exists. | `OpenPr` |
| `Reviewed` | The pull request carries a review comment that satisfies the gate. | `Review` |
| `Counted` | The pull request carries `agent-reviewed`, so its CI has been re-triggered with the review counted. **Terminal for the runner.** | `Recount` |
| `Orphaned` | A claim exists but no run owns it: the run that took it died without releasing it, and the claim has gone stale. **Not terminal** — reclaimable. | `Observe` |

### Why `Orphaned` is a state and not an error

`Orphaned` is the state a **kill** leaves behind. It is not reachable by any event of a running
process — a process that is alive is in one of the other nine states. It is reachable only by
`Observe`, which is the runner reconciling what it finds with what it expected.

This matters because the plan's failure handling covers a *handled* failure and says nothing about
a kill (`docs/plans/2026-10-30-afk-issue-agent-pipeline.md:536-538`). Without `Orphaned`, a killed
run's issue would sit in `Claimed` forever, and the queue selector excludes claimed issues — so
the issue would leave every future run's view, silently and permanently. That is `INV-02` in
`spec.md`, and `Orphaned` is its model.

### Why `Ready` is a state at all

`Ready` is the only state whose evidence is *not* durable: the commits exist in a worktree on one
machine. It is a state because the transition `Ready → Pushed` is a genuine step with a genuine
failure mode (the push fails, the work is still here), and because it is the last point at which
the runner can still fail without the world having seen anything.

## Events and triggers

### The runner's own actions

| Event | Raised by |
|---|---|
| `select()` | The runner, at the start of an invocation |
| `claim(N)` | The runner, immediately after selecting issue `N` |
| `prepare(N)` | The runner, after the claim |
| `start(N)` | The runner, after the worktree is ready |
| `agentEnds(N)` | The agent session, when it settles — completed or aborted |
| `push(N)` | The runner, after the agent ends with commits |
| `openPr(N)` | The runner, after the push |
| `review(N)` | The reviewer agent, after the pull request exists |
| `recount(N)` | The runner, after the review comment is posted |
| `release(N)` | The runner, on any handled failure |
| `reclaim(N)` | The runner, after `Observe` found an orphaned claim |

### The reconciling observation

| Event | Raised by |
|---|---|
| `observe(N)` | The runner, at the start of an invocation, for each issue it is considering |

### The observables `observe` reads

`observe` is the only event that reads the world. Its inputs:

| Observable | Source | Meaning |
|---|---|---|
| `L` | issue labels | the issue carries `agent-in-progress` |
| `R` | `git ls-remote` | the remote branch `agent/issue-<N>` exists |
| `P` | `gh pr list --head agent/issue-<N>` | a pull request exists for the branch |
| `V` | the pull request's comments | it carries a comment opening with `<!-- agent-review -->` |
| `C` | the pull request's labels | it carries `agent-reviewed` |
| `A` | the issue's last update | the claim is older than the staleness window |

### The derivation

```
L = false                                     → Queue
L = true, P = true,  V = true,  C = true      → Counted
L = true, P = true,  V = true,  C = false     → Reviewed
L = true, P = true,  V = false               → Opened
L = true, P = false, R = true                → Pushed
L = true, P = false, R = false, A = true     → Orphaned
L = true, P = false, R = false, A = false    → Claimed
```

`A = true` means *stale* — the claim has aged past the window (see Open question 3 in
`spec.md`). The two rows that differ only in `A` are the whole of `Orphaned`: a fresh claim with
nothing behind it belongs to a live run and is left alone; a stale one does not.

## Transition table

| Source | Event / trigger | Guard | Target | State mutation | Invariants checked | Side effects | Failure behaviour | Cancellation behaviour | Verification reference |
|---|---|---|---|---|---|---|---|---|---|
| `Queue` | `select()` | at least one eligible issue exists; the lock is held | `Queue` | records the chosen issue number | INV-08 | reads the queue from GitHub | empty queue → zero exit, says it selected nothing | killed before the claim → nothing claimed | AC-01, AC-02 — `runner.test.ts::test_selection_excludes_claimed_issues` |
| `Queue` | `claim(N)` | the claim label is absent | `Claimed` | applies `agent-in-progress` | INV-01, INV-06 | one label add | the label add fails → non-zero exit, issue unclaimed | killed between the add and the read-back → the claim is visible and becomes `Orphaned` | AC-03 — `runner.test.ts::test_claim_precedes_worktree_creation` |
| `Claimed` | `prepare(N)` | the worktree does not already exist | `Isolated` | none (the state is derived from the filesystem) | INV-03 | creates the worktree on `agent/issue-<N>` from `origin/main`, runs `scripts/setup-worktree.sh` | worktree or setup fails → `release(N)` | killed mid-setup → the claim is stranded; the next run finds `Orphaned` | AC-04 — `runner.test.ts::test_worktree_comes_from_origin_main_with_the_setup_script_run` |
| `Isolated` | `start(N)` | the guardrails file exists and is readable | `Working` | none | INV-04 | launches the agent with the body fenced as data and the rule in the system prompt | the agent cannot start → `release(N)` | killed here → `Orphaned`; the agent's context is unrecoverable (`--no-session`), so the work is redone | AC-05, AC-16 — `runner.test.ts::test_issue_body_is_fenced_as_data_in_a_user_turn` |
| `Working` | `agentEnds(N)` | the session settled | `Ready` | none | INV-08 | the agent's commits sit in the worktree | a session that settles with `aborted: true` → `release(N)`; an aborted turn is a failed agent | the process is killed here → `Orphaned`; the local commits are discarded and the issue is started over | AC-17 — `runner.test.ts::test_one_invocation_acts_on_one_issue` |
| `Ready` | `push(N)` | the ref is exactly `agent/issue-<N>`; the branch has commits | `Pushed` | none | INV-03, INV-05 | one push of one ref | the push fails → `release(N)`; a ref outside the prefix is refused | killed between the push and the read-back → the next run finds `R = true` → `Pushed`, and the work is not redone | AC-06 — `runner.test.ts::test_push_refuses_a_ref_outside_the_issue_branch` |
| `Pushed` | `openPr(N)` | no pull request exists for the branch | `Opened` | none | INV-05 | opens the pull request, body closing the issue | the pull request cannot be opened → `release(N)`; an existing pull request is **found, not duplicated** | killed here → the next run finds `P = true` → `Opened` | AC-07, AC-20 — `runner.test.ts::test_the_pull_request_body_closes_the_issue` |
| `Opened` | `review(N)` | the pull request carries no review comment yet | `Reviewed` | none | INV-06 | a second agent posts the comment: marker first, verdict as the whole value of its line | the reviewer fails → the pull request stays red and is left for a human | killed here → the next run finds `V = true` → `Reviewed` | AC-08 — `runner.test.ts::test_the_review_comment_opens_with_the_marker` |
| `Reviewed` | `recount(N)` | the pull request carries the review | `Counted` | none | INV-06 | applies `agent-reviewed` to the pull request, which re-triggers its CI | the label add fails → non-zero exit; the pull request stays red | killed here → the next run finds `C = false` → `Reviewed` and applies the label | AC-09 — `runner.test.ts::test_ci_is_retriggered_after_the_review_comment` |
| `Counted` | `release(N)` | never | `Counted` | none | INV-05 | none | n/a | n/a — terminal for the runner; a human merges | AC-17 — `runner.test.ts::test_the_runner_never_merges` |
| any non-terminal | `release(N)` | the failure was seen by the runner | `Queue` | removes `agent-in-progress` | INV-02 | one label remove, and the worktree is removed | the label remove fails → the claim is stranded and becomes `Orphaned` | n/a — this *is* the handled path | AC-11 — `runner.test.ts::test_a_handled_failure_releases_the_claim` |
| `Orphaned` | `reclaim(N)` | the claim is stale and no pull request exists | `Claimed` | re-applies the claim (idempotent), and resets the local work | INV-01, INV-02 | removes the dead run's worktree | the reset fails → the issue stays `Orphaned` and is retried later | n/a | AC-12, AC-13 — `runner.test.ts::test_a_stranded_claim_is_reclaimed` |
| `Orphaned` | `observe(N)` | a pull request exists — the dead run got further than the label | the derived state (`Pushed`, `Opened` or `Reviewed`) | none | INV-01 | none | n/a | n/a | AC-13 — `runner.test.ts::test_a_claim_with_a_pull_request_is_left_alone` |
| `Queue` | `observe(N)` | a second runner is already working the issue | `Queue` | none | INV-01 | none | n/a | n/a | AC-14 — `runner.test.ts::test_a_second_runner_exits_while_the_lock_is_held` |

## Side effects

Every effect the states above cause, and where it lands:

| Effect | Target | Durable across a runner death? |
|---|---|---|
| Apply `agent-in-progress` | the issue | **yes** — GitHub |
| Remove `agent-in-progress` | the issue | **yes** — GitHub |
| Create the worktree and the local branch | the local filesystem | **no** — one host |
| Run `scripts/setup-worktree.sh` | the worktree | **no** |
| Push `agent/issue-<N>` | the remote | **yes** |
| Open the pull request | GitHub | **yes** |
| Post the review comment | the pull request | **yes** |
| Apply `agent-reviewed` | the pull request | **yes** |
| Write the run's record | `build/diagnostics/issue-<N>/` | **no** — one host |

The split is the point. Everything durable is on GitHub, and everything durable is something a
human can read without the runner.

## Failure transitions

Covered in the table above; the three that carry a decision worth naming:

- **`release(N)` fails to remove the label.** The issue stays claimed and becomes `Orphaned` on
  the next observation. It is recoverable — but only because the reclaim rule exists. Without it,
  this one failed `gh` call would strand the issue permanently.
- **The agent's turn is aborted** (the session reports `aborted: true` mid-array). The session
  exited, so the runner sees a finished agent with possibly-partial commits. The runner treats it
  as a failed agent: `agentEnds(N)` → `release(N)`, not `Ready`. The alternative — treating a
  partial array as success — would ship a change whose remaining prompts never ran.
- **The review verdict is `fail`.** Not a runner failure: the pull request exists, is red, and
  carries the findings. The runner leaves it and stops. `Counted` is not reached, and the
  `agent-reviewed` label is **not** applied — applying it would re-count a failed review against
  the pull request.

## Cancellation and shutdown

The runner is a process, so "cancellation" is termination. The distinction the model needs is
between termination the runner can see and termination it cannot.

| Termination | Seen by the runner? | What is left | What recovers it |
|---|---|---|---|
| SIGTERM | yes | the claim, the worktree | `release(N)` — the claim is removed |
| A step failing (worktree, setup, push, pull request) | yes | the claim | `release(N)` |
| The agent exiting non-zero | yes | the claim, the worktree | `release(N)` |
| SIGKILL | **no** | the claim, the worktree, possibly a branch | `observe` → `Orphaned` → `reclaim` |
| Power loss | **no** | the same | the same |
| OOM kill | **no** | the same | the same |

**The mutation is never rolled back.** Every mutation the runner makes is external and durable —
a label, a ref, a pull request, a comment. None of them has an inverse the runner can rely on:
a pull request that was opened cannot be un-opened, and a pushed commit cannot be unpushed
without a rewrite. So recovery in this model is always **forward** — re-derive, then continue or
take over — and never a rollback. Any transition whose design depended on undoing a previous one
would be wrong.

**Ownership.** The runner owns one worktree for the duration of one run and removes it. It never
touches the main checkout. It owns no scope beyond that run: when the process ends, nothing is
left running.

## Concurrency and serialization

**What can run in parallel.** Two invocations on the same host. The tracker, the remote and the
repository are shared; the worktrees are not.

**What serializes them.** An exclusive lock held for the whole run, in a writable directory. The
lock is *host-local*: it excludes a second runner on the same machine and nothing else.

**What the lock does not do, and what has to.** The lock alone does not satisfy `INV-01` — not
across machines, and not against the Phase-2 extension running on the same host while the timer's
lock is held. Something else has to be exclusive, and it has to be exclusive against a system the
two callers share. Adding a label is **not** it: a label add is idempotent, so both callers
"win" it. This is the constraint `design.md` has to solve, and the model says so rather than
picking for it.

**Ordering.** The order of the transitions is not arbitrary. Each one exists because the later
step's artifact is what the earlier step's recovery needs:

`claim` before `prepare` — a claim with no worktree is the cheapest thing to recognise and
release. `push` before `openPr` — a pull request needs a branch to point at. `review` before
`recount` — the re-count is what makes the review count, and it can only be applied once the
comment exists. `observe` before everything — the runner reconciles before it acts, so that it
never acts on an issue another run already owns.

**The selection/claim window.** Selecting an issue and claiming it are two calls, and between
them another runner can select the same issue. The window is real. Closing it is the same
exclusivity problem, and it is the reason `claim` carries the `INV-01` guard rather than being
unconditional.

**Re-entrancy.** Every event can arrive twice, because the runner can be re-run over the same
issue. The table's guards are where that is answered — see [Recovery and retry](#recovery-and-retry).

## Recovery and retry

**Recovery is re-derivation, not replay.** The runner holds no journal. A later run calls
`observe(N)`, which reads the observables, and lands in one of the ten states. From there:

| The derived state | What the run does |
|---|---|
| `Queue` | select it and start over |
| `Claimed` | a live run owns it — leave it alone |
| `Isolated`, `Working`, `Ready` | **not derivable from outside** — these live on one host's disk. A run that finds them is on the same machine and can see the worktree; a run on another machine cannot, and sees `Orphaned` once the claim goes stale |
| `Pushed` | the work is durable — open the pull request, do not push again |
| `Opened` | run the review, do not open a second pull request |
| `Reviewed` | apply the re-count, do not post a second review |
| `Counted` | nothing. The pull request is a human's now |
| `Orphaned` | take it over, or leave it if a pull request exists behind it |

**Is the operation idempotent?** Per operation:

| Operation | Applied twice? | Why |
|---|---|---|
| Apply `agent-in-progress` | **safe** | a label add is idempotent |
| Remove `agent-in-progress` | **safe** | a label remove is idempotent |
| Create the worktree | **unsafe** | it must find an existing one rather than destroy it |
| Run the setup script | **safe** | the script is written to be re-runnable — it repairs rather than initialises (`scripts/setup-worktree.sh:182-183,268-333`) |
| Push the branch | **mostly safe** | a second push of the same commits is a no-op; a second push of *different* commits to the same branch is a force-push, which the runner must not do |
| Open the pull request | **unsafe** | a second pull request for the same issue is a duplicate — the runner must find the first |
| Post the review comment | **unsafe** | the **newest** marked comment decides (`scripts/ci/check-agent-review.py:216-238`), so a second review *overrides* the first. A `pass` followed by a `fail` turns the pull request red; a `fail` followed by a `pass` turns a red pull request green on the strength of a review that may not have looked at the same diff |
| Apply `agent-reviewed` | **safe** | a label add is idempotent |

**Terminal states.** `Counted` is terminal for the runner: the pull request exists, it carries its
review, and a human merges it. There is no path back — and that is correct, because withdrawing a
pull request that awaits a human is not the runner's decision. `Orphaned` is the other kind of
end: not terminal, because `reclaim` leaves it.

**The failed-review hazard.** Posting a review twice is the one genuinely dangerous repetition,
because the gate's rule is "the newest marked comment decides" rather than "any marked comment
counts". The model therefore gives `review(N)` the guard *the pull request carries no review
comment yet* — the runner reviews once. A second review is a decision for a human, who knows
whether the change moved.

## Invariants

| Id | Restated as a state assertion | Checked by |
|---|---|---|
| INV-01 | At most one runner is in a non-`Queue` state for a given issue at a time; a second runner observing a fresh claim stays in `Queue` | `claim`, `observe` — AC-14, `runner.test.ts::test_a_second_runner_exits_while_the_lock_is_held` |
| INV-02 | No claim is stranded: every `Claimed` state is either released to `Queue` or becomes `Orphaned` and is then reclaimed | `release`, `reclaim`, `observe` — AC-11, AC-12, `runner.test.ts::test_a_stranded_claim_is_reclaimed` |
| INV-03 | The only ref the runner pushes is `agent/issue-<N>` | `push` — AC-06, `runner.test.ts::test_push_refuses_a_ref_outside_the_issue_branch` |
| INV-04 | No text from an issue body is executed as an instruction | `start` — AC-05, `runner.test.ts::test_issue_body_is_fenced_as_data_in_a_user_turn` |
| INV-05 | The runner never merges, never approves and never modifies a gate | `release` from `Counted` is forbidden; `push` guard — AC-17, `runner.test.ts::test_the_runner_never_merges` |
| INV-06 | Every pull request the runner opens reaches `Counted`, or is left red for a human | `review`, `recount` — AC-09, AC-10, `runner.test.ts::test_ci_is_retriggered_after_the_review_comment` |
| INV-07 | The state is readable from the issue's labels and the pull request, without the runner's logs | every transition's side effects — AC-18, `runner.test.ts::test_the_run_leaves_a_record_named_for_the_issue` |
| INV-08 | One invocation acts on one issue | `select` — AC-17, `runner.test.ts::test_one_invocation_acts_on_one_issue` |

## Counterexamples

### C-1 — Re-entry: the review is posted twice

- **Sequence:** `Opened` → `review(N)` → `Reviewed` → the runner is killed → a later run observes
  `V = true` and, believing the review is missing, posts a second comment.
- **Expected behaviour:** the second run must find the existing review comment and skip to
  `recount`. It must not post a second comment. If the first verdict was `fail` and the second is
  `pass`, the pull request turns green on a review of a different diff.
- **Why this is a risk:** the gate decides on the **newest** marked comment
  (`scripts/ci/check-agent-review.py:216-238`), so a duplicate comment is not a harmless repeat —
  it overrides. Nothing in the gate distinguishes "a second reviewer" from "the same reviewer
  twice".

### C-2 — Parallel calls: two runners, one issue

- **Sequence:** runner A selects issue 42; runner B selects issue 42 before A claims it; both
  claim; both create a worktree.
- **Expected behaviour:** exactly one runner acts. The other must observe the claim and leave the
  issue alone. The claim itself cannot enforce this — a label add is idempotent — so the
  exclusivity has to come from an exclusive operation that both callers contend for.
- **Why this is a risk:** two runners produce two worktrees, two pushes of one branch name and
  two pull requests for one issue. The second push either fails or clobbers the first.

### C-3 — Cancellation mid-flight: killed between the claim and the worktree

- **Sequence:** `Claimed` → `claim(N)` succeeds → SIGKILL.
- **Expected behaviour:** the claim is visible to the next run. Once it goes stale, the next run
  observes `Orphaned` and reclaims the issue, and the work is redone from the brief.
- **Why this is a risk:** without `Orphaned` the issue is skipped by every future run forever.
  The claim survives the process, and the queue selector excludes claimed issues.

### C-4 — Restart: killed between the push and the pull request

- **Sequence:** `Ready` → `push(N)` succeeds → SIGKILL → the next run starts.
- **Expected behaviour:** the next run observes `R = true`, `P = false` → `Pushed`, and opens the
  pull request. It must **not** push again and must not restart the agent: the commits are
  durable.
- **Why this is a risk:** a runner that treats every claimed issue as a fresh start would push
  the same branch again. If the agent had produced *different* commits, that second push is a
  force-push over a branch a human may already be reading.

### C-5 — The staleness clock is bumped by a human

- **Sequence:** a run is killed, leaving a claim; a human comments on the issue an hour later,
  asking what happened.
- **Expected behaviour:** the issue stays `Orphaned` until the window passes again. The delay is
  acceptable — the comment is itself evidence that someone is looking at the issue.
- **Why this is a risk:** the staleness signal is the issue's last update, which any comment
  moves. A chatty issue stays stranded longer than a quiet one.

### C-6 — The claim is not exclusive

- **Sequence:** two runners on two hosts, no shared lock; both call
  `gh issue edit --add-label agent-in-progress`; both succeed, exit 0.
- **Expected behaviour:** the runner must not treat a successful label add as proof of
  exclusivity. The exclusivity has to be established by an operation that cannot succeed twice.
- **Why this is a risk:** the label reads like a lock and is not one. A runner that believes it
  won the claim will happily start a second worktree on the same issue.

### C-7 — The agent's array is aborted mid-run

- **Sequence:** `Working` → the session settles after the second of three prompts, reporting
  `aborted: true`.
- **Expected behaviour:** the runner treats the agent as failed and releases the claim. It must
  not treat a partial array as success, and must not push the partial work.
- **Why this is a risk:** the worktree holds commits from a half-finished turn — a change that
  never went through the verification prompt. Pushing it would put an unverified diff in front
  of a reviewer.

## Mapping to implementation

**None of this is implemented.** The runner does not exist — there is no runner script, no prompt
directory and no skill that does this
(`investigation.md`, "Facts about the plan itself"). The table below says where each transition
*would* live once it does, and which existing mechanism it leans on.

| Transition | Implemented at | Leans on |
|---|---|---|
| `Queue --select()--> Queue` | not yet implemented — the extension's repository does not exist yet | `gh issue list` |
| `Queue --claim(N)--> Claimed` | not yet implemented | `gh issue edit --add-label` |
| `Claimed --prepare(N)--> Isolated` | not yet implemented | `scripts/setup-worktree.sh:5-8` |
| `Isolated --start(N)--> Working` | not yet implemented | `createAgentSession` with the worktree as its `cwd` |
| `Working --agentEnds(N)--> Ready` | not yet implemented | the `agent_settled` / `aborted` events |
| `Ready --push(N)--> Pushed` | not yet implemented | `git push origin agent/issue-<N>` |
| `Pushed --openPr(N)--> Opened` | not yet implemented | `gh pr create` |
| `Opened --review(N)--> Reviewed` | not yet implemented | `.agents/skills/code-review-pr-workflow/SKILL.md`; `scripts/ci/check-agent-review.py:137-238` |
| `Reviewed --recount(N)--> Counted` | not yet implemented | `gh pr edit --add-label agent-reviewed` |
| `any --release(N)--> Queue` | not yet implemented | `gh issue edit --remove-label` |
| `Orphaned --reclaim(N)--> Claimed` | not yet implemented | the derivation in [The derivation](#the-derivation) |
| `Orphaned --observe(N)--> derived state` | not yet implemented | `gh pr list`, `git ls-remote` |

## Verification coverage

| Transition | Scenario | Acceptance |
|---|---|---|
| `Queue --select()--> Queue` | V-01, V-02 | AC-01, AC-02 |
| `Queue --claim(N)--> Claimed` | V-12, V-21 | AC-03, AC-14 |
| `Claimed --prepare(N)--> Isolated` | V-13 | AC-04 |
| `Isolated --start(N)--> Working` | V-07, V-17 | AC-05, AC-16 |
| `Working --agentEnds(N)--> Ready` | V-20 | AC-17 |
| `Ready --push(N)--> Pushed` | V-08 | AC-06 |
| `Pushed --openPr(N)--> Opened` | V-09, V-10 | AC-07, AC-20 |
| `Opened --review(N)--> Reviewed` | V-06, V-24 | AC-08, AC-10 |
| `Reviewed --recount(N)--> Counted` | V-05, V-14, V-19 | AC-09, AC-10, AC-19 |
| `Counted --release(N)--> Counted` | V-11 | AC-17 |
| `any --release(N)--> Queue` | V-04 | AC-11 |
| `Orphaned --reclaim(N)--> Claimed` | V-03, V-22 | AC-12 |
| `Orphaned --observe(N)--> derived state` | V-03, V-23, V-25, V-26 | AC-12, AC-13 |
| `Queue --observe(N)--> Queue` | V-15, V-16 | AC-14, AC-15 |

_Any transition with no scenario is an untested risk, and `converge` will report it. The scenarios are
designed in `verification.md`; the counterexamples C-1 to C-7 map onto them as C-1 → V-24, C-2 → V-15
and V-21, C-3 → V-22, C-4 → V-25, C-5 → V-26, C-6 → V-21, C-7 → FI-05._

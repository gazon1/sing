# Design — AFK issue→PR runner

> Written by `speckit-engineering-plan`. This document chooses the solution and says why. The
> contract it must satisfy is `spec.md`; the states it must realise are `model.md`; the evidence it
> must produce is `verification.md`. Where this design reveals a defect in one of those, the defect
> is named here rather than papered over.

## Summary

A **TypeScript Pi extension** reads one issue labelled `ready-for-agent`, gives it an isolated git
worktree, runs a Pi session on it, opens a pull request, and has a second session review that pull
request. The extension keeps no state: everything it needs to recover after a crash is read back
from GitHub and from git.

The extension is built on the **Pi SDK**, not on the `pi` CLI, and that choice is what makes the
rest of the design simple:

- the session's working directory **is** the worktree (`AgentSessionConfig.cwd`);
- the prompt array is three `session.prompt()` calls in one session, not a CLI convention;
- `steer` and `abort` are method calls, so the live chat is not a later phase;
- the events are typed, so nothing is parsed out of a log;
- the guardrail is a string the extension holds, so there is no file that can go missing.

The one decision the rest of this document defends: **the claim on an issue is an exclusive
remote ref, not the label.** The label is a marker for humans; the ref is the lock.

## Current architecture and affected modules

### What already exists and is reused unchanged

| Mechanism | Where | How the extension uses it |
|---|---|---|
| The issue contract | `.agents/skills/triage/SKILL.md:33-45`, `AGENT-BRIEF.md:3,39-68` | the queue it selects from and the format of the task it hands the agent |
| The worktree recipe | `scripts/setup-worktree.sh:5-8` | run in every worktree it creates |
| The review gate | `scripts/ci/check-agent-review.py:137-238` | the format of the comment it posts, read by the extension's own test |
| The verdict gate | `scripts/check-review-verdicts.py:19-29` | the digest it writes into a verdict file |
| The issue registry | `scripts/check-issue-registry.py:28-35` | the registry entry its pull request carries |
| The diagnostics convention | `build/diagnostics/<name>/` | where it writes the record of a run |

Nothing in this repository consumes the `agent-in-progress` label today — it exists, and no code
acts on it. This change is the first reader.

### The Pi SDK, and what it actually gives

`pi` is `@earendil-works/pi-coding-agent` 1.4.0 — an ESM TypeScript package with
`dist/index.d.ts`. The extension uses:

| What | Where |
|---|---|
| `createAgentSession`, `AgentSession`, `AgentSessionConfig` | `dist/core/agent-session.d.ts` |
| a session bound to any `cwd` | `AgentSessionConfig.cwd` (`:116`) |
| `session.prompt(text, options?)` | (`:553`) |
| `session.steer(text, images?, options?)` | (`:574`) |
| `session.followUp(...)`, `session.abort()`, `session.dispose()` | (`:585`, `:654`, `:391`) |
| typed events: `agent_end`, `agent_settled {aborted}`, `queue_update`, `compaction_start` | `AgentSessionEvent` (`:47-61`) |
| `createExtensionRuntime`, `defineTool`, `discoverAndLoadExtensions` | `dist/index.d.ts:9` |
| the extension API: `ExtensionAPI`, `ExtensionContext`, `BeforeAgentStartEvent`, `TurnStartEvent`, `TurnEndEvent`, `ToolCallEvent`, `AgentSettledEvent`, `SessionShutdownEvent` | `dist/core/extensions/index.ts` |
| a headless RPC protocol — "RPC protocol types for headless operation" | `dist/modes/rpc/rpc-types.d.ts` |

Pi loads a `.ts` extension directly — `jiti` is a dependency — so **there is no build step
between editing an extension and running it**. Three extensions written this way already run on
this host, in `~/.pi/agent/extensions/`.

### What is added, and where

**In this repository:**

| File | What it holds |
|---|---|
| `docs/decisions/2026-10-30-the-afk-runner-is-a-pi-extension.md` | the decision record |
| `docs/architecture/afk-runner.md` | the contract the extension binds to, written for the extension's author |
| `.specify/issues/afk-issue-agent-pipeline.yaml` | this change's registry entry |

**In the extension's own repository, `pi-afk-runner`:**

```
pi-afk-runner/
├── package.json            # name, version, exports, "pi": { "extensions": [...] }
├── tsconfig.json
├── src/
│   ├── extension.ts        # the Pi extension: hooks, tools, lifecycle
│   ├── cli.ts              # the standalone entry point: one issue, then exit
│   ├── runner.ts           # the queue: select, claim, worktree, push, PR, review
│   ├── session.ts          # the agent turn: createAgentSession, the prompt array
│   ├── claim.ts            # the exclusive claim
│   ├── gh.ts               # every GitHub call, in one place
│   ├── git.ts              # every git call, in one place
│   └── prompts/            # 01-brief.md, 02-implement.md, 03-verify.md
├── test/                   # vitest
└── .github/workflows/      # the extension's own CI
```

### What is not touched

No application code. No capability specification. No gate. No existing script. The gate registry
(`scripts/ci/static-gates.sh`) is unchanged — the runner is a scheduled process that acts, not a
check that runs on every commit, so it is not registered (`docs/architecture/registering-a-gate.md:28`).

## Proposed solution

### The components

The runner is assembled, not written. The npm keyword that marks a package as a Pi package is
`pi-package`, and the registry holds **13 036** of them. Every version, version count and download
count below was read from the npm registry on 2026-10-30.

| # | Component | Source | Version | Downloads/wk | What we still write |
|---|---|---|---|---|---|
| 1 | The agent session | the Pi SDK — `createAgentSession` | ships with Pi 1.4.0 | — | the `cwd`, the prompt loop |
| 2 | Builder / reviewer / checker / guard | `@drix10/agent-flow` | v1.2.7 | 176 | whether to adopt it as the engine |
| 3 | The reviewer (delegation) | `pi-subagents` | v0.84.0 | **329 003** | the verdict format |
| 4 | The executor/advisor flow | `pi-advisor-flow` | v0.14.3 | 18 693 | which role is which |
| 5 | The multi-step workflow | `@quintinshaw/pi-dynamic-workflows` | v3.15.0 | 16 045 | the three prompts |
| 6 | The guard | `@gotgenes/pi-permission-system` | v42.0.0 | 10 648 | the protected paths |
| 7 | Live lint and format feedback | `pi-lens` | v4.4.7 | 26 609 | which checks the gates run |
| 8 | Cron, isolated runs, the run store | `agents-cron` | v0.15.0 | 2 152 | the selection query |
| 9 | The worktree | `@signalridge/pi-worktree` | v1.4.5 | 47 | the branch name, `setup-worktree.sh` |
| 10 | The pull request and its checks | `@narumitw/pi-github-pr` | v0.49.8 | 93 | the body, `Fixes #N`, the registry entry |
| 11 | The verdict as structured output | `examples/extensions/structured-output.ts` | ships with Pi | — | the two verdict values |
| 12 | The commit | `examples/extensions/auto-commit-on-exit.ts` | ships with Pi | — | the message convention |
| 13 | The system prompt (the guardrail) | `examples/extensions/prompt-customizer.ts` | ships with Pi | — | the guardrail text |
| 14 | The steer queue (phase 2) | `pi-queue-steer-factory` | v0.17.11 | — | the wiring |
| 15 | Inter-component messaging | `examples/extensions/event-bus.ts` | ships with Pi | — | the event names |

Items 11-15 are first-party: they ship inside the versioned Pi package, so they are the most stable
things available — they move with Pi, not behind it.

**One deliberate refusal.** `@langfuse/pi-observability-plugin` v0.1.2 would give the run traces
for free, but it ships the run's content to a third-party service — and the content includes
**issue bodies, which are attacker-writable on a public repository**. The record stays on the
machine, under `build/diagnostics/issue-<N>/`.

**`@drix10/agent-flow` is nearly the whole pipeline.** Its README: "**One agent builds. A second
reviews. A third tests. A guard keeps all three out of what they must never touch.**" — "It runs
in its own git worktree and stops with checked work and a draft pull request waiting for you.
Works with Claude Code, Codex, Gemini CLI, Cursor, Copilot, Windsurf, **Pi** and anything that
reads `AGENTS.md`" — and "**The guard blocks tool calls directly on Claude Code and Pi.**" A failed
check already goes back to the implementer up to a round cap, and `agent-flow run <issue> --pr`
opens the pull request.

What it does not do is the part specific to this repository: it takes a task id on the command
line, so it has no queue; and its reviewer is its own, so it does not post a comment this
repository's gate accepts.

**What nobody provides**, and it is irreducibly ours:

1. **The exclusive claim** — a remote ref created with a nonce. No extension does this, and none
   could: it depends on the fact that adding a label is idempotent, so a label cannot be a lock.
2. **The binding to this repository's contract** — the brief's marker (`## Agent Brief`), the
   review's marker and verdict (`<!-- agent-review -->` / `**Verdict:** pass`), the re-count label
   (`agent-reviewed`), the registry entry, and the verdict file with the digest
   (`scripts/check-review-verdicts.py --digest`). Every one of those is read from this
   repository's own code, and every one changes when that code changes.
3. **The record** under `build/diagnostics/issue-<N>/`.

### Two entry points, one codebase

| Entry point | Who runs it | What it is |
|---|---|---|
| `src/extension.ts` | Pi loads it | the **live chat**: a human starts a session with the extension loaded and watches the run, able to steer it |
| `src/cli.ts` | a timer or a systemd unit | the **unattended** run: one issue, then exit |

Both drive the same `runner.ts`. The difference is who owns the session and whether a human is
watching. The extension is not a wrapper around the CLI — it is the same code with a different
front door.

### The run

```
select → claim → worktree → session → push → PR → review → re-count → done
```

One invocation works one issue and stops.

### The session

The extension creates the session with the worktree as its `cwd`:

```ts
const session = await createAgentSession({
  cwd: worktreePath,
  model,
  systemPrompt: guardrails,          // built natively, never read from a path
  extensions: [runnerExtension],
});
```

Then the prompt array, which is the extension's own loop rather than a CLI convention:

```ts
for (const prompt of [brief, implement, verify]) {
  await session.prompt(prompt);      // resolves when the turn settles
}
```

`session.prompt` resolves when the turn is done, so the array is sequential by construction and each
turn sees the previous one's context. **Whether it resolves on the turn's end or on the whole
array's end is not verified** — if it is the latter, the array goes out as one prompt and the array
becomes a convention again (see [Known limitations](#known-limitations)).

**The guardrail goes into the system prompt, not into a prompt file.** The issue body is untrusted
external data — the repository is **public**, so issue bodies are attacker-writable — and a rule
that sits in a user turn competes with the injection. The extension holds the rule as a string and
puts it in the system prompt, where the body has no standing.

The CLI's `--append-system-prompt` was the opposite: it **silently ignores a missing file**
(measured), which is why the earlier shape of this design had to check the file's existence before
starting the agent. There is no file here to go missing.

The body reaches the agent fenced and labelled as data:

```
<issue-body source="github-issue" trust="untrusted">
...the body, verbatim...
</issue-body>
```

Only issues carrying `ready-for-agent` are ever picked up, and that label is applied by a human.

### The prompt files

Three files, each a task-specific rule set, in the extension's repository:

- `prompts/01-brief.md` — read the issue and its comments, find the agent brief (the comment
  opening with `## Agent Brief`, or the issue's body when the issue has no such comment), activate
  the skill the brief names, and plan the work without touching a file yet.
- `prompts/02-implement.md` — implement; run the repo's gates; commit — but do not push.
- `prompts/03-verify.md` — re-read the diff, fix what the gates found, and say why in the commit
  message.

The gates the agent runs are the set the repository's own review skill names
(`.agents/skills/code-review-pr-workflow/SKILL.md:40-50`): `./check.sh`, `just tcheck-evals`,
`just docs-audit`. **Not** `just gate` — it is three steps, and the third runs the Maestro agenda
flows on a device (`.just/tests/mod.just:190-220`, ADR `2026-10-04-one-gate-recipe.md`), which an
unattended host does not have. `just gate` is also not a superset: the same ADR records that it
does not run `docs-audit` or `find-unwired-surfaces`.

### The claim: a label and an exclusive ref

The runner marks the issue `agent-in-progress` so a human can see which issue is in flight, and
then it creates an **annotated ref** holding the claim.

**Why the claim cannot be the label.** Adding a label is idempotent: a second add exits 0, so both
runners "win" it. `model.md`'s counterexample C-6 is exactly this, and `spec.md`'s `INV-01` says
in words that the marker cannot be the lock.

**Why a ref, and why it carries a nonce.** Creating a git tag is not a fast-forward operation:
git refuses to update an existing tag without `--force`. But git answers "Everything up-to-date"
and exits **0** when the ref already points at the *same* object — so a plain push of the base
commit is not exclusive either. A **nonce in the ref's message** makes the object unique, and the
second creation is then rejected no matter what.

Measured on this host, 2026-10-30, against a throwaway repository:

```
$ git push origin "$BASE:refs/heads/afk/claim-42"     # first claim
exit=0
$ git push origin "$OTHER:refs/heads/afk/claim-42"     # a different object
 ! [rejected]  (non-fast-forward)
exit=1
```

and the same base pushed twice without a nonce:

```
$ git push origin "$BASE:refs/heads/afk/claim-42"     # second claim, same object
Everything up-to-date
exit=0
```

So the nonce is not decoration. Without it the second runner's push is a no-op that exits 0, and
the claim is not exclusive at all.

The claim also **records the base commit** it was taken from, which the reclaim path needs and
which the issue's own metadata cannot supply — `origin/main` has moved on by then:

```
$ git ls-remote --tags origin "refs/tags/afk-claim-issue-42*"
135948e…	refs/tags/afk-claim-issue-42
2f3150d…	refs/tags/afk-claim-issue-42^{}
```

The second line is the base commit.

**The order of the claim.** The label goes on **before** the claim ref. The label is what moves
the issue's last-update time, and that time is the staleness signal `A` in `model.md`'s
derivation; the ref is what makes the claim exclusive. The reverse order leaves a claim whose
staleness clock never started.

If the claim push is rejected, the runner removes the label it just added and moves on to another
issue: it lost the race, and it must not leave a claim behind.

### The work

A worktree on `agent/issue-<N>` from the base commit, with `scripts/setup-worktree.sh` run in it
before any other command there — the same order `scripts/setup-worktree.sh:5-8` mandates. The agent
commits locally; the runner pushes, fast-forwarding the branch from the base to the agent's commits.

### The pull request and its review

The pull request closes the issue. If it lands a change in this repository it also carries that
change's registry entry and its verdict file, with the `documents` digest computed by
`scripts/check-review-verdicts.py --digest <slug>` — the same implementation the gate uses, so the
recorded value and the checked value cannot disagree.

A second session reviews the pull request and posts a comment that opens with
`<!-- agent-review -->` and carries a verdict line. The runner then applies `agent-reviewed` to the
**pull request**, which is what re-triggers its CI: a comment does not start a run, and a run
started by the comment has its checks counted on the default branch rather than on the pull request.

The runner reviews **once**. The gate decides on the newest marked comment, so a second review
overrides the first — a `pass` after a `fail` would turn a red pull request green on a review of a
different diff.

### The release path

A failure the runner can see releases the claim: the label comes off, the worktree is removed, and
the claim ref is deleted. The work branch, if it carries commits, is **left** — the work is real,
and a later run opens the pull request from it.

### The reclaim path

A claim whose issue has no pull request, no work branch and no recent activity is taken over. The
runner deletes the stale claim ref, re-claims, and starts the work again from the brief. The
session is gone with the process, so the work is redone rather than resumed.

### The record

A run writes to `build/diagnostics/issue-<N>/`: the session's events, the steps the runner took,
and the outcome. The issue number plus a run identifier correlates the events of one run; the
identifier is needed because the same issue can be reclaimed and worked twice.

## Alternatives considered

**Writing the whole pipeline ourselves.** Rejected, and the ecosystem research is what settled
it: the worktree, the delegation, the guard, the cron, the pull request, the structured verdict,
the auto-commit and the system prompt all exist as installable, maintained packages or as
first-party examples inside the Pi package. Only three things are ours, and they are the three
that are specific to this repository. Writing the other ten would be writing them worse.

**A Python script in this repository that shells out to `pi --mode json -p`.** The earlier shape of
this design, and the one the plan was first rewritten from. It works, but it gives up four things
the SDK hands over: the session's `cwd`, the typed events, `steer` and `abort`, and the native
system-prompt injection. It also has to work around the CLI's two gaps — no `--cwd` (a `cd`
workaround) and a system prompt that is read from a path that may be missing — and it has to parse
a JSON event stream to learn what the agent did. The plan's rewrite through TypeScript was
requested by the maintainer, and the SDK is the reason it is an improvement rather than a change of
house style.

**The label alone as the lock.** Rejected: adding a label is idempotent — a second add exits 0 —
so both runners "win" it. `model.md`'s counterexample C-6 is exactly this, and `spec.md`'s
`INV-01` says in words that the marker cannot be the lock.

**A host-local lock file alone.** Rejected as the *whole* mechanism: it excludes a second runner
on the same machine and nothing else. It is kept, but as the *first* of three layers, not as the
lock.

**The work branch itself, created at claim time.** Rejected. It makes the model's observable `R`
ambiguous: `R` means "the remote branch `agent/issue-<N>` exists", and if the branch is created at
claim time then `R` is true for a claim that has done no work, so the derivation cannot tell
`Claimed` from `Pushed`. A separate claim ref leaves `R` meaning exactly what `model.md` says it
means, at the cost of one extra ref that is deleted on release.

**Pushing the work branch as the lock.** Rejected: it happens after the agent has done all the
work. Two runners would both do the work and only then collide on the push. The exclusivity has to
come before the work, not after it.

**A SQLite or JSON state file.** The draft's `state.ts`. Rejected: a file on one host is not a
lock, and it is a second place where the state of an issue lives. GitHub already holds it, where a
human can see it.

**A TypeScript core split into `state.ts`/`queue.ts`/`worktree.ts`/`runner.ts`.** The draft's
shape. Rejected: the state machine has ten states and no concurrency beyond one lock. The
TypeScript survives; the split does not.

**Driving Pi over its RPC protocol instead of using the SDK.** `dist/modes/rpc/rpc-types.d.ts` is
a documented, typed, headless control protocol — `prompt` (with
`streamingBehavior: "steer" | "followUp"`), `steer`, `abort`, `get_state`. It is the right tool
for driving Pi from **outside** Pi — a CI job with no TTY, for instance. It is not what this
extension is: the extension runs inside Pi, and inside Pi the SDK is the shorter path to the same
capabilities.

**The runner as a GitHub Actions workflow.** Rejected: the runner needs a worktree with the setup
script, a private Gradle home and the agent's credentials, and it needs to run for hours. The
repository's CI has no device for the Maestro step and no reason to carry an agent's runtime.

**A GitHub App or fine-grained token for the pushes.** The plan's phase 4. Rejected *for now*, not
on merit: it is a separate change with its own approval surface, and the runner cannot be
implemented without a token that can push, so the token question is answered by whoever deploys it.

## Why this solution is the simplest

The runner has **no state store**. It has no journal, no queue file and no database. Everything it
needs to know about an issue it reads from GitHub and from git — and because the claim is a ref and
the work is a branch and the review is a comment, every one of those is already there, owned by
someone else, visible to a human.

That is what makes the recovery path short: a later run does not replay a log, it re-derives the
state from six observables and continues. The alternative — a runner with a journal — needs the
journal to be written before every effect, kept consistent with every effect, and readable after a
crash that happened between two of them. The state-free runner needs the world to be consistent,
and the world already is.

And the extension needs **no protocol of its own**: the SDK is the protocol. There is no event
stream to parse, no argv convention to honour, no file to keep in sync — the guardrail is a string,
the worktree is the session's `cwd`, and a turn is a method call.

Three layers of exclusion, each one line:

1. the host lock stops a second runner on the same machine;
2. the claim ref stops a second runner anywhere;
3. the work push is rejected if it is not a fast-forward, so a runner that lost layer 2 cannot
   overwrite the winner's work.

## Component and dependency changes

| Component | Change | The defect it removes |
|---|---|---|
| `pi-afk-runner` (new repository) | a thin package that installs six components and writes three things | There is no executor for the 76 issues that carry `ready-for-agent`; the contract exists and nothing works it |
| `pi-subagents` v0.84.0 | installed | The reviewer needs an isolated context in its own process; writing one is the delegation problem, and this package has 156 versions and 329 003 downloads a week |
| `@quintinshaw/pi-dynamic-workflows` v3.15.0 | installed | The prompt array is a workflow, and workflows are what this package does |
| `@gotgenes/pi-permission-system` v42.0.0 | installed | "A guard keeps all three out of what they must never touch" — 262 versions and 10 648 downloads a week |
| `pi-advisor-flow` v0.14.3 | installed | The implementer/reviewer pair as a flow, rather than as a delegation primitive — 84 versions and 18 693 downloads a week |
| `pi-lens` v4.4.7 | installed | Real-time lint and format feedback inside the turn, so the gates are cheap rather than a batch at the end |
| `agents-cron` v0.15.0 | installed | The draft's `queue.ts` retry/backoff/reconcile machinery, replaced by a scheduler that never wakes itself up |
| `@signalridge/pi-worktree` v1.4.5 | installed | Worktree mechanics — but **not** this repository's `setup-worktree.sh`, which stays ours |
| `@narumitw/pi-github-pr` v0.49.8 | installed | Opening the pull request and reading its checks — but **not** the body, the `Fixes #N`, the registry entry or the verdict file |
| `examples/extensions/structured-output.ts` | copied from the Pi package | A verdict that is a value, not a paragraph |
| `examples/extensions/auto-commit-on-exit.ts` | copied from the Pi package | The commit step |
| `examples/extensions/prompt-customizer.ts` | copied from the Pi package | The system prompt, built by the extension rather than read from a path that may be missing |
| `src/claim.ts` | ours | A marker is not a lock: adding a label is idempotent, so two runners both win the claim |
| `src/contract.ts` | ours | This repository's markers, verdicts and labels, read from this repository's own code |
| `src/prompts/` | ours | What the agent is asked to do |
| the record under `build/diagnostics/issue-<N>/` | ours | An unattended runner that dies leaves a human with nothing to read |

In this repository: the decision record, the reference documentation, and the registry entry. No
new dependency, no new library, no change to any gate.

## State-model changes

**The states are unchanged.** `model.md`'s ten states, fourteen transitions and six observables are
realised as they are written, and the claim ref does not enter the derivation at all.

This is worth stating explicitly, because the obvious design — the work branch created at claim
time — *would* have required a change: it makes `R` ("the remote branch `agent/issue-<N>` exists")
true for a claim that has done no work, and the derivation could then no longer tell `Claimed`
from `Pushed`. The separate claim ref avoids that: `R` keeps its meaning exactly, and the ref is an
**effect** of the claim transition and an **input** to the reclaim transition, never an observable
the derivation reads.

The model's `A` — the staleness signal — stays where it is: the issue's last update, which the
label add moves.

**One consequence the model does not spell out** is that the runner reads the **claimed** issues as
well as the queue. `spec.md`'s required behavior 14 already says a later run "recognizes a claim
that has no pull request, no branch and no recent activity", which cannot happen unless the runner
looks at the claimed issues. The queue read (`AC-01`) excludes them; the reconciliation read does
not. Both exist, and they are different queries.

**The agent's boundary moves, but not the states.** `model.md` puts the agent's own conversation
outside the boundary and treats "the agent process" as an observable. With the SDK the agent is a
**session** the runner owns: it is created, driven and disposed by the runner, and it reports
`agent_settled {aborted }` rather than exiting. The transition `Working --agentEnds(N)--> Ready`
is therefore now "the session settles", and a session that settles with `aborted: true` is a failed
agent — the same distinction the model already draws between "the agent exited non-zero" and "the
turn was aborted".

## Persistence and compatibility impact

- **No persisted format changes** in this repository. No Room schema, no serialized form, no
  preferences file. The runner writes only a directory under `build/diagnostics/`, which is not
  tracked.
- **The claim ref is new in the repository.** It is created and deleted by the runner. A ref left
  behind by a killed run is removed by the reclaim path. `branch-hygiene` governs branches and does
  not reach refs, so no existing tool's expectations change.
- **The `agent-in-progress` label is now written by code.** It already exists with the description
  "An AFK agent is working on this issue" (`gh label list`), and nothing reads it today.
- **The pull request the runner opens is an ordinary pull request** and is judged by the gates
  already in place: the review gate, the verdict gate, the issue registry.
- **The extension pins a Pi version range.** The SDK surface it uses is versioned with Pi, and the
  hobby projects in this space all pin one version. The extension checks the range at start-up and
  refuses to run outside it — a runner that half-works against a different Pi is worse than one
  that stops.

## Testing strategy

The strategy is fixed in `verification.md` and this design does not restate it. Three things this
design owes the tests:

1. **The decisions must be callable without a network.** The derivation, the guards and the prompt
   construction are functions over injected values, imported by the extension's test file the way
   `scripts/tests/test_check_agent_review.py:38-62` imports the review gate.
2. **The clock must be injectable.** The runner takes "now" as a value. There is no `MutableClock`
   for TypeScript in this repository, and the extension's repository has to provide one.
3. **`gh`, `git` and the session must be stubbable**, and every git command in a test must run
   inside a sandbox that scrubs the inherited `GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE` and
   `GIT_COMMON_DIR` and refuses to walk up past the fixture's own directory.

That third point is not optional and not transferable. `scripts/tests/git_sandbox.py` exists in
**this** repository because a fixture wrote into the developer's repository on 2026-10-23 and broke
every fetch and push in every worktree. The extension's repository has no such helper, so the
extension's tests need their own — and the failure it prevents is not hypothetical here.

One test is worth naming because it pins a decision rather than a function: **the claim ref's
nonce.** Without it, the second push is "everything up-to-date" and exits 0, and the claim is not
exclusive. The test plants two claims with the same base commit and asserts that the second is
rejected.

## Rollout or migration

Nothing to migrate. The runner starts with no issues claimed and creates its own state.

The rollout is five steps, in order:

0. **The spike.** Install the six components, run one issue end to end, and find out whether they
   compose. Each package has its own idea of what a turn is and who owns the session; that is a
   claim to be tested, not a fact. The spike also settles whether `@drix10/agent-flow` is the
   engine or only the reference design.
1. **The package.** Build the extension, publish it, install it into `~/.pi/agent/extensions/`,
   and check that Pi loads it and that its version check passes.
2. **Dry.** Run the CLI against an issue it selects, with the push and the pull request
   suppressed, and read the record it writes under `build/diagnostics/issue-<N>/`.
3. **One issue.** Let it open a real pull request on one issue. The pull request is reviewed by a
   human, as every other one is.
4. **The queue.** Let the timer run.

Before step 3, the runner's token needs `contents: write` on `agent/*` and `pull_requests: write`.
The plan defers the token's shape to its phase 4; the runner does not depend on which one it is.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| **The issue body carries an injection.** The repository is public. | The body is fenced and labelled as data; the rule that it is not to be obeyed is in the system prompt, which the extension builds natively — there is no file that can go missing and no user turn where it competes with the injection; the runner pushes one branch and never merges, so the blast radius of a successful injection is one pull request a human reviews |
| **The claim is not exclusive because the ref's object repeats.** | The nonce in the ref's message makes the object unique, and a test plants two claims with the same base and asserts the second is rejected |
| **Two runners work one issue anyway.** | Three layers: the host lock, the claim ref, and the non-fast-forward rejection of the work push |
| **A killed run strands the issue.** | The claim is reclaimed after the staleness window; the runner reads the claimed issues as well as the queue |
| **The agent's context is lost when a run dies.** | It is unrecoverable — the session is gone with the process. The work is redone from the brief, which is the contract |
| **The runner's cost is unbounded.** | The session has a wall-clock deadline and is aborted when it passes. The provider is configured at deployment |
| **The reviewer disagrees with the author.** | They are separate sessions and the gate does not judge the login; the reviewer's 25-minute budget and "do not run Gradle" rule keep it from competing with the author's build |
| **A `fail` verdict is treated as a retry.** | It is not: the pull request is left red, no second verdict is posted, and no other issue is taken |
| **The pull request is red because CI was never re-counted.** | The runner applies `agent-reviewed` after the comment, and the order is asserted by a test |
| **The extension breaks against a newer Pi.** | The extension pins a version range and refuses to run outside it |
| **The extension's own fixture writes into a real repository.** | The extension's test suite needs its own git sandbox; this repository's `scripts/tests/git_sandbox.py` is the precedent and the reason |

## Task dependencies

```
In pi-afk-runner (the extension's repository)
  Phase 1 — the package
    T001 the package, the build, the version check
    T002 the git sandbox the tests need
  Phase 2 — the queue                       (sequential: one module at a time)
    T003 the queue read and the state derivation
    T004 the claim
    T005 the worktree
    T006 the session and the prompt array
    T007 the push and the pull request
    T008 the review and the re-count
    T009 the release path and the record
    T010 the reclaim and the recovery cases
  Phase 3 — the two front doors
    T011 the extension entry point
    T012 the CLI entry point and the lock

In this repository
    T013 the decision record
    T014 the reference documentation
    T015 the registry entry
```

**Phase 0 blocks everything.** The spike decides which components the extension depends on, and
therefore the shape of every task after it.

Phase 1 is parallel — the two tasks touch different files. Phase 2 is sequential because every task
in it writes the same modules and the same test file, and two writers of one file is how a plan
loses work silently. Phase 3 depends on all of Phase 2, because the entry points are the
composition of the steps.

## Non-goals

- **The learning loop.** The plan's phase 2, and a separate change.
- **Auto-merge.** The runner never merges.
- **A capability specification.** No requirement in `openspec/specs/` describes this workflow, and
  none of the five nearest neighbours is altered.
- **A gate.** The runner is not a check that runs on every commit, so it is not registered in the
  gate registry and does not need a positive control.
- **A token design.** The plan's phase 4. The runner needs a token that can push `agent/*` and open
  pull requests; it does not care which kind.
- **Cross-issue parallelism.** One issue at a time, deliberately: the repository's own rules
  forbid two Gradle invocations at once, and `./gw` gives each worktree a private
  `GRADLE_USER_HOME`, but the detekt verdict in the pre-push hook takes about nine minutes for a
  `shared/` change.
- **A harness other than Pi's.** The extension is inside Pi; there is no second agent program to
  fall back to. The model is chosen from Pi's registry.

## Validation commands

In the extension's repository:

```bash
npm test                                     # the extension's suite
npx vitest run test/runner.test.ts           # one file
npx vitest run test/runner.test.ts -t test_selection_excludes_claimed_issues
npm run build                                # the package builds
npm run lint
```

In this repository:

```bash
# the gates the runner's pull request has to satisfy
python3 scripts/check-review-verdicts.py --quiet
python3 scripts/check-issue-registry.py --quiet
python3 scripts/ci/check-agent-review.py --pr <PR_NUMBER>

# the digest the runner's verdict file must carry
python3 scripts/check-review-verdicts.py --digest <slug>

# the gate suite — the change must not turn a gate red
bash scripts/ci/static-gates.sh

# the reference documentation must stay true
python3 scripts/check-doc-dead-refs.py --quiet
```

## Known limitations

- **Whether `session.prompt()` resolves on the turn's end or on the whole array's end.** The
  three-turn sequence assumes the former. If it is the latter, the array is sent as one prompt and
  the array becomes a convention again — which would be a regression to the shape this design
  rejects, and the test that pins it would say so.
- **Whether an extension loaded into a session can also create further sessions through the SDK
  without the two interfering.** The runner session and the work session are different sessions,
  and the extension's hooks must not fire in the wrong one.
- **Whether the SDK surface used here is stable across Pi versions.** It is versioned with Pi, and
  the hobby projects in this space all pin one version.

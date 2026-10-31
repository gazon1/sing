# AFK issue→PR pipeline

**Goal.** A queue runner that reads GitHub issues, gives each one its own git
worktree from `origin/main`, runs an agent on it, and opens a pull request.

**Status.** Plan only. Nothing is implemented. Every "verified" claim below was
checked on this host on 2026-10-30; every "not verified" claim was not.

**Revised 2026-10-30 (twice).** First, ten claims in this plan were checked
against the code and found wrong; they were corrected in place, and the
corrections and their citations live in
`openspec/changes/afk-issue-agent-pipeline/investigation.md` — read it as the
evidence behind every edit here.

Then the plan was rewritten **through TypeScript**. The runner is no longer a
Python script that shells out to `pi`. It is a **TypeScript Pi extension**,
built on the Pi SDK, developed in its own repository, published as a Pi
extension. The section that argued against a Pi extension is gone, because the
argument did not survive contact with the SDK. What replaced it says what the
SDK actually gives — and it gives more than the CLI did.

## The short answer

Do not write the six-phase TypeScript core described in the draft. Three of its
four steps are already solved by things that are installed and running:

| Draft step | What already exists |
|---|---|
| Read issues from a repo | `gh issue list --label ready-for-agent` (the label exists and **76** open issues carry it) |
| Run "my own agent harness" | the Pi **SDK** — `createAgentSession`, `prompt()`, `steer()`, `abort()`, typed events |
| Create a worktree from `origin/main` | `git worktree add` + the repo's own `scripts/setup-worktree.sh` |
| Open a PR with a review that passes CI | `gh pr create` + the repo's `code-review-pr-workflow` skill |

The draft's instinct was right about the language and about the framework, and
wrong about the shape. The TypeScript belongs in a **Pi extension** — not in a
`state.ts`/`queue.ts`/`worktree.ts`/`runner.ts` split, and not in a script that
parses a JSON event stream.

## What is already here

Verified on this host, 2026-10-30.

**The issue intake contract exists and is in use.** The repo has a
`ready-for-agent` label ("Spec is ready for an agent to implement"), **76** open
issues carry it, and `.agents/skills/triage/AGENT-BRIEF.md` defines the brief
format that goes with it: behavioural, no file paths, acceptance criteria,
explicit scope boundaries. The brief's first line is `## Agent Brief`
(`AGENT-BRIEF.md:42`) — a marker a program can find. The triage skill's state
machine is
`needs-triage → needs-info → ready-for-agent → ready-for-human → wontfix`
(`.agents/skills/triage/SKILL.md:33-45`).

This is the single most important fact for the plan: **the queue selector, the
task format, and the review contract are already written and already
maintained.** A pipeline that invents its own label and its own prompt format
would be a second, drifting copy of them.

**A worktree recipe exists.** `scripts/setup-worktree.sh` handles what a bare
`git worktree add` does not in this repo: the `core.bare` repair, the
per-worktree `core.hooksPath`, copying `.env` from the canonical checkout, and
creating the per-worktree Python venv and Gradle home. It is documented as
mandatory ("Run once per worktree, right after creating it"). A pipeline that
skips it produces worktrees that fail at the first Gradle command.

**A PR gate already requires an agent review.** `.github/workflows/ci.yml` runs
`scripts/ci/check-agent-review.py` as a leaf of the `ci-gate` job, and `ci-gate`
fails on anything other than `success` or `skipped`. The check requires the PR
to carry a comment that **opens** with the marker `<!-- agent-review -->` —
the marker has to be the first non-blank line, so a comment that merely quotes
the format is not a review — and a verdict line matching `**Verdict:** pass`
(or `fail`, which fails the gate). The **newest** marked comment decides, so a
later comment that states no verdict decides nothing rather than falling back
to an older `pass`. The gate explicitly does not judge the comment's author —
"separate" is a fact about the session, not the login.

**And the gate has to be re-counted.** The `Agent review` job runs on
`pull_request`, and a comment does not start a run: GitHub counts the checks
of an `issue_comment` run on the default branch, so the green would land on
main and not on the pull request. The re-count is the `agent-reviewed` label —
which exists on the repo — or a push. A runner that opens a pull request, posts
a review and stops there has produced a **red** pull request.

**A separate verdict-file gate exists for spec'd changes.**
`scripts/check-review-verdicts.py` requires every active change under
`openspec/changes/` to carry `.specify/reviews/<slug>.md` with frontmatter
(`change`, `verdict`, `date`, `pr`, `reviewed`, `documents` digest).

**And an issue-registry gate sits beside it.** Every directory under
`openspec/changes/` — archived ones included — needs a record in
`.specify/issues/<slug>.yaml` that points back at that change **by slug**
(`scripts/check-issue-registry.py:28-35`). There are 718 such records today. A
pull request that lands a change needs both files.

**A review skill exists** — `.agents/skills/code-review-pr-workflow/SKILL.md`,
which already specifies the `**Verdict:** pass` format and how to post it.

### The Pi SDK is installed and it is the right surface

`pi` is `@earendil-works/pi-coding-agent` 1.4.0 — an ESM TypeScript package
with `dist/index.d.ts`. It exports exactly what a runner needs:

| What | Where |
|---|---|
| `createAgentSession`, `AgentSession`, `AgentSessionConfig` | `dist/core/agent-session.d.ts` |
| a session bound to any `cwd` | `AgentSessionConfig.cwd` (`dist/core/agent-session.d.ts:116`) |
| `session.prompt(text, options?)` | `dist/core/agent-session.d.ts:553` |
| `session.steer(text, images?, options?)` | `dist/core/agent-session.d.ts:574` |
| `session.followUp(...)`, `session.abort()`, `session.dispose()` | `dist/core/agent-session.d.ts:585,654,391` |
| typed events: `agent_end`, `agent_settled {aborted}`, `queue_update`, `compaction_start` | `AgentSessionEvent` (`dist/core/agent-session.d.ts:47-61`) |
| `createExtensionRuntime`, `defineTool`, `discoverAndLoadExtensions` | `dist/index.d.ts:9` |
| the extension API: `ExtensionAPI`, `ExtensionContext`, `BeforeAgentStartEvent`, `TurnStartEvent`, `TurnEndEvent`, `ToolCallEvent`, `AgentSettledEvent`, `SessionShutdownEvent` | `dist/core/extensions/index.ts` |
| a headless RPC protocol — "RPC protocol types for headless operation" | `dist/modes/rpc/rpc-types.d.ts` |

Three extensions written this way already run on this host, in
`~/.pi/agent/extensions/`. Pi loads a `.ts` extension directly — `jiti` is a
dependency — so **there is no build step to run before the extension works**.

### The Orca CLI

Orca has `orca worktree create` (with `--agent`, `--prompt`, `--issue`,
`--base-branch`, `--setup run|skip|inherit`, `--run-hooks`) and
`orca automations create` (with `--provider <agent>`, `--precheck`, `--trigger`).

Two caveats. First, `orca` cannot drive the harness that actually works on
this host: there is no `orca agent list` command to enumerate providers
(`orca agent list` answers "Unknown command"), and `orca automations create --help`
describes `--provider <agent>` as "Agent id such as codex, claude, or gemini" —
`pi` is not an Orca provider at all. Second, `orca worktree create`
makes its own checkout, and the bare-main-entry layout means a worktree it
creates is not usable until `scripts/setup-worktree.sh` runs in it. Note that
the command *does* have hooks for exactly that: `--setup run|skip|inherit`
("Setup policy for repo-defined setup hooks") and `--run-hooks`. Whether either
reaches **this** repository's setup script is not verified — it is the first
thing to check before dismissing Orca.

## Why a Pi extension

The draft's instinct — *"Pi extensions are easy to write, the API is nice"* —
is correct, and `pi-ops` exists and does roughly this shape (issue → worktree
→ agent → PR, labelled `piops`, never auto-merging). It has **0 stars and 0
forks** and hardcodes `/tmp/pi-ops/` as its worktree root. `pi-board-agent`,
`@quintinshaw/pi-dynamic-workflows` and the various `pi-subagents` forks are
in the same tier: hobby projects, no release cadence, API surface pinned to one
Pi version. So the ecosystem does not hand this to us; we build it.

What the SDK gives that the CLI did not, and why it matters:

- **The session's `cwd` is the worktree.** The CLI has no `--cwd` — the plan's
  old workaround was to `cd` into the worktree before launching, which worked
  but was a workaround. `AgentSessionConfig.cwd` is a field.
- **The prompt array becomes the extension's own loop.** `session.prompt(text)`
  three times, awaiting each turn, is three turns in one session sharing one
  context — the same shape the CLI's argv array gave, obtained as an API
  instead of a convention.
- **`steer` and `abort` exist from the first run.** `session.steer(...)`
  (`dist/core/agent-session.d.ts:574`) and `session.abort()` (`:654`) are
  method calls, not a JSONL protocol to hand-roll. The live chat stops being
  Phase 2 and becomes a feature of Phase 1.
- **The events are typed, not parsed.** `agent_settled` arrives with
  `aborted: boolean`; `compaction_start` is an event, so "compaction kills a
  turn mid-array" becomes something the runner *observes* rather than something
  it infers from a log line.
- **The guardrail is injected natively.** `BeforeAgentStartEvent` lets the
  extension build the system prompt itself. The CLI's
  `--append-system-prompt` **silently ignores a missing file** — measured, and
  the old plan had to work around it by checking the file first. With the
  extension API there is no file to go missing: the guardrail is a string the
  extension holds.

## The components

Most of this pipeline already exists, as ready-made Pi extensions. The npm keyword that marks a
package as a Pi package is **`pi-package`**, and the registry holds **13 036** of them. The
gallery is [pi.dev/packages](https://pi.dev/packages); installation is
`pi install npm:<name>`.

Every version and download count below was read from the npm registry on 2026-10-30.

### The pieces, and where each one comes from

| # | Component | Source | Version | Downloads/wk | What we still write |
|---|---|---|---|---|---|
| 1 | **The agent session** | the Pi SDK — `createAgentSession` | ships with Pi 1.4.0 | — | the `cwd`, the prompt loop |
| 2 | **Builder / reviewer / checker / guard** | `@drix10/agent-flow` | v1.2.7 | 176 | whether to adopt it as the engine |
| 3 | **The reviewer** (delegation, isolated context) | `pi-subagents` | v0.84.0 | **329 003** | the verdict format |
| 4 | **The executor/advisor flow** | `pi-advisor-flow` | v0.14.3 | 18 693 | which role is which |
| 5 | **The multi-step workflow** (the prompt array) | `@quintinshaw/pi-dynamic-workflows` | v3.15.0 | 16 045 | the three prompts |
| 6 | **The guard** (what must never be touched) | `@gotgenes/pi-permission-system` | v42.0.0 | 10 648 | the protected paths |
| 7 | **Live lint and format feedback** | `pi-lens` | v4.4.7 | 26 609 | which checks the gates run |
| 8 | **Cron, isolated runs, the run store** | `agents-cron` | v0.15.0 | 2 152 | the selection query |
| 9 | **The worktree** | `@signalridge/pi-worktree` | v1.4.5 | 47 | the branch name, `setup-worktree.sh` |
| 10 | **The pull request and its checks** | `@narumitw/pi-github-pr` | v0.49.8 | 93 | the body, `Fixes #N`, the registry entry |
| 11 | **The verdict as structured output** | `examples/extensions/structured-output.ts` | ships with Pi | — | the two verdict values |
| 12 | **The commit** | `examples/extensions/auto-commit-on-exit.ts` | ships with Pi | — | the message convention |
| 13 | **The system prompt** (the guardrail) | `examples/extensions/prompt-customizer.ts` | ships with Pi | — | the guardrail text |
| 14 | **The steer queue** (phase 2) | `pi-queue-steer-factory` | v0.17.11 | — | the wiring |
| 15 | **Inter-component messaging** | `examples/extensions/event-bus.ts` (`pi.events`) | ships with Pi | — | the event names |

Items 11-15 are **first-party**: they ship inside the versioned Pi package, so they are the most
stable things available — they move with Pi, not behind it.

### The one that is nearly the whole pipeline

`@drix10/agent-flow` deserves its own line, because its README describes this plan's shape
almost exactly:

> **One agent builds. A second reviews. A third tests. A guard keeps all three out of what they
> must never touch.**

> It runs in its own git worktree and stops with checked work and a draft pull request waiting for
> you. Works with Claude Code, Codex, Gemini CLI, Cursor, Copilot, Windsurf, **Pi** and anything
> that reads `AGENTS.md`.

> **The guard blocks tool calls directly on Claude Code and Pi.**

And it already does two things the plan was going to build: a failed check goes back to the
implementer up to a round cap, and `agent-flow run <issue> --pr` opens the pull request.

What it does **not** do is the part that is specific to this repository: it takes a task id on the
command line, so it has no queue; and its reviewer is its own, so it does not post a comment this
repository's review gate accepts.

Its usage is 176 downloads a week against `pi-subagents`' 329 003, so it is the least-used of the
components here. Two honest readings, and the plan should decide rather than assume:

- **Adopt it** as the engine, and write only the queue front-end and this repository's contract
  binding. Smallest build by far, and it is Pi-native.
- **Compose from the parts** (items 3-8 above) and write the engine's shape ourselves, borrowing
  `agent-flow`'s design as the reference. More code, but every piece is independently replaceable
  and independently maintained.

### Two more from the same shelf

`pi-advisor-flow` v0.14.3 (84 versions, 18 693/week, modified the day before) is an "Advanced
Executor/Advisor flow for Pi" — the implementer/reviewer pair the plan needs, as a flow rather
than as a delegation primitive. `pi-lens` v4.4.7 (148 versions, 26 609/week) gives the agent
"real-time code feedback — LSP, linters, formatters, type-checking", which is what makes the
gates cheap inside a turn instead of a batch at the end.

And one deliberate refusal: `@langfuse/pi-observability-plugin` v0.1.2 would give the run
traces for free, but it ships the run's content to a third-party service — and the content
includes **issue bodies, which are attacker-writable on a public repository**. The record stays
on the machine, under `build/diagnostics/issue-<N>/`.

### What nobody provides

Three things are irreducibly ours, and they are the three that are specific to this repository:

1. **The exclusive claim.** No extension does this, and no extension could: it depends on the
   fact that adding a label is idempotent, so a label cannot be a lock. It is a remote ref
   created with a nonce — see [The claim](#the-claim).
2. **The binding to this repository's contract.** The brief's marker (`## Agent Brief`), the
   review's marker and verdict (`<!-- agent-review -->` / `**Verdict:** pass`), the re-count label
   (`agent-reviewed`), and the registry entry plus the verdict file with the digest
   (`scripts/check-review-verdicts.py --digest`). Every one of those is read from this
   repository's own code, and every one of them changes when that code changes.
3. **The record** under `build/diagnostics/issue-<N>/`.

Everything else in this plan is assembly.

### How to judge "stable"

Three signals, and they disagree often enough to be worth stating:

| Signal | What it says | Trap |
|---|---|---|
| **versions published** | 156, 262, 473 — a long history of shipping | A high count also means a fast-moving API. `pi-harness-runtime` has the most versions of anything here (473) and its orchestration mode is explicitly beta |
| **last modified** | within days = maintained | `pi-worktree` (the unprefixed one, v1.3.3) has not moved since **2026-03-14** — seven months — while `@signalridge/pi-worktree` moved on 2026-10-12 |
| **downloads per week** | 329 003 for `pi-subagents` means someone depends on it | Downloads measure popularity, not fitness. `@signalridge/pi-worktree` at 47/week may still be exactly right |

So: prefer the first-party examples, prefer the high-version, high-download packages, and treat
a package whose last release is months old as a candidate to read rather than to depend on.

## The plan

Two phases. Phase 1 is the whole unattended system. Phase 2 is the learning
loop on top of it.

### Phase 1 — the extension

#### Where it lives

**Its own repository**, `pi-afk-runner`, published as a Pi extension.

A Pi extension is not a script in a checkout — it is a package Pi loads. That
forces the shape: `package.json`, a version, a build, a release. Putting that
inside this repository would put a JavaScript toolchain into a Kotlin
Multiplatform application repository that has none, and would make the
extension's code live under the same review and gates as the app it operates
on. A separate repository keeps those apart.

The binding between the two repositories is a **contract**, and it is written
down on both sides:

| This repository guarantees | The extension relies on |
|---|---|
| `ready-for-agent` is the queue selector | the label exists and is human-applied |
| a brief is a comment opening with `## Agent Brief` | `AGENT-BRIEF.md:42` |
| a review is a comment opening with `<!-- agent-review -->` and a verdict line | `scripts/ci/check-agent-review.py:137-238` |
| `agent-reviewed` re-counts the review gate | `.github/PULL_REQUEST_TEMPLATE.md:38-39` |
| a landed change carries a registry entry and a verdict file | `scripts/check-issue-registry.py:28-35`, `scripts/check-review-verdicts.py:19-29` |
| a worktree needs `scripts/setup-worktree.sh` | `scripts/setup-worktree.sh:5-8` |

#### The package

The package is **thin**. It installs the components it composes and writes only the three
irreducible things.

```
pi-afk-runner/
├── package.json            # "keywords": ["pi-package"], deps on the components
├── tsconfig.json
├── src/
│   ├── extension.ts        # the Pi extension: the queue front-end and the contract binding
│   ├── cli.ts              # the standalone entry point: one issue, then exit
│   ├── claim.ts            # the exclusive claim — ours, nobody else's
│   ├── contract.ts         # this repository's markers, verdicts and labels — ours
│   └── prompts/            # 01-brief.md, 02-implement.md, 03-verify.md — ours
├── test/                   # vitest
└── .github/workflows/      # the extension's own CI
```

Everything else — the worktree, the delegation, the guard, the cron, the pull request, the
structured verdict, the auto-commit, the system prompt — is **installed, not written**:

```bash
pi install npm:pi-subagents                       # the reviewer: 329k downloads a week
pi install npm:@quintinshaw/pi-dynamic-workflows  # the multi-step workflow
pi install npm:@gotgenes/pi-permission-system     # the guard
pi install npm:agents-cron                         # cron, isolated runs, the run store
pi install npm:@signalridge/pi-worktree            # the worktree
pi install npm:@narumitw/pi-github-pr              # the pull request and its checks
```

**Two entry points, one codebase.**

- `src/extension.ts` — loaded by Pi. It is the live chat: a human starts a Pi
  session with the extension loaded, and the extension does the same work the
  unattended runner does, with the human able to steer.
- `src/cli.ts` — a standalone process. `node dist/cli.js --run-once` does one
  issue and exits; a timer or a systemd unit runs it. It creates its own
  `AgentSession` through the SDK.

Both drive the same `runner.ts`. The difference is who owns the session and
whether a human is watching.

#### The run

```
select → claim → worktree → session → push → PR → review → re-count → done
```

One invocation works one issue and stops.

#### The session

The extension creates the session with the worktree as its `cwd`:

```ts
const session = await createAgentSession({
  cwd: worktreePath,
  model,                 // from the runner's configuration
  systemPrompt: guardrails,   // built natively, never read from a path that may be missing
  extensions: [runnerExtension], // so the guardrail hook applies
});
```

Then the prompt array, which is the extension's own loop rather than a CLI
convention:

```ts
for (const prompt of [brief, implement, verify]) {
  await session.prompt(prompt);
  // await resolves when the turn settles
}
```

`session.prompt` resolves when the turn is done, so the array is sequential by
construction and each turn sees the previous one's context.

**The guardrail goes into the system prompt, not into a prompt file.** The
issue body is untrusted external data — the repository is **public**, so issue
bodies are attacker-writable — and a rule that sits in a user turn competes
with the injection. The extension holds the rule as a string and puts it in
the system prompt, where the body has no standing.

The body reaches the agent fenced and labelled as data:

```
<issue-body source="github-issue" trust="untrusted">
...the body, verbatim...
</issue-body>
```

Only issues carrying `ready-for-agent` are ever picked up, and that label is
applied by a human.

#### The prompt files

Three files, each a task-specific rule set. They live in the extension's
repository, because they are the extension's content.

- `prompts/01-brief.md` — read the issue and its comments, find the agent brief
  (the comment opening with `## Agent Brief`), activate the skill the brief
  names, and plan the work without touching a file yet.
- `prompts/02-implement.md` — implement; run the repo's gates; commit — but
  do not push.
- `prompts/03-verify.md` — re-read the diff, fix what the gates found, and say
  why in the commit message.

The gates the agent runs are the set the repository's own review skill names
(`.agents/skills/code-review-pr-workflow/SKILL.md:40-50`): `./check.sh`,
`just tcheck-evals`, `just docs-audit`. **Not** `just gate` — it is three
steps, and the third runs the Maestro agenda flows on a device
(`.just/tests/mod.just:190-220`, ADR `2026-10-04-one-gate-recipe.md`), which an
unattended host does not have. `just gate` is also not a superset: the same ADR
records that it does not run `docs-audit` or `find-unwired-surfaces`.

#### The claim

The runner marks the issue `agent-in-progress` so a human can see which issue
is in flight, and then it creates an **exclusive ref** — the claim.

**Why the claim cannot be the label.** Adding a label is idempotent: a second
add exits 0, so both runners "win" it. Measured on this host, 2026-10-30,
against a throwaway repository:

```
$ git push origin "$BASE:refs/heads/afk/claim-42"     # first claim
exit=0
$ git push origin "$OTHER:refs/heads/afk/claim-42"     # a different object
 ! [rejected]  (non-fast-forward)
exit=1
```

The rejection only happens when the object differs — git answers
"Everything up-to-date" and exits **0** when the ref already points at the
same commit. So the claim carries a **nonce** in its message, which makes its
object unique, and the push is then rejected no matter what.

The claim also **records the base commit** the claim was taken from, which the
reclaim path needs and which the issue's own metadata cannot supply —
`origin/main` has moved on by then.

The label goes on **before** the claim ref. The label is what moves the issue's
last-update time, and that time is the staleness signal; the ref is what makes
the claim exclusive. The reverse order leaves a claim whose staleness clock
never started.

If the claim push is rejected, the runner removes the label it just added and
moves on to another issue: it lost the race, and it must not leave a claim
behind.

#### The work

A worktree on `agent/issue-<N>` from the base commit — the mechanics come
from `@signalridge/pi-worktree` — and then `scripts/setup-worktree.sh` run in
it before any other command there, because that script does what a generic
worktree extension cannot: the `core.bare` repair, the per-worktree
`core.hooksPath`, the `.env` copy and the private Gradle home
(`scripts/setup-worktree.sh:5-8,182-183,268-333`). The agent commits locally;
the runner pushes, fast-forwarding the branch from the base to the agent's
commits.

#### The pull request and its review

The pull request closes the issue. If it lands a change in this repository it
also carries that change's registry entry and its verdict file, with the
`documents` digest computed by `scripts/check-review-verdicts.py --digest <slug>`
— the same implementation the gate uses, so the recorded value and the checked
value cannot disagree.

A second session reviews the pull request — the delegation comes from
`pi-subagents`, which runs each delegate in an isolated context in its own
process — and posts a comment that opens with `<!-- agent-review -->` and
carries a verdict line. The verdict is structured output, not prose:
`examples/extensions/structured-output.ts` shows the tool that ends the turn on
a tool call without paying for another model turn. The runner then applies
`agent-reviewed` to the **pull request**, which is what re-triggers its CI.

The runner reviews **once**. The gate decides on the newest marked comment, so
a second review overrides the first — a `pass` after a `fail` would turn a red
pull request green on a review of a different diff.

#### The release path

A failure the runner can see releases the claim: the label comes off, the
worktree is removed, and the claim ref is deleted. The work branch, if it
carries commits, is **left** — the work is real, and a later run opens the pull
request from it.

#### The reclaim path

A claim whose issue has no pull request, no work branch and no recent activity
is taken over. The runner deletes the stale claim ref, re-claims, and starts
the work again from the brief.

**Trigger.** A cron entry, every N minutes. The scheduling, the isolated run and
the run records come from `agents-cron`, whose design is the right one here:
"the scheduler never wakes itself up: no timer, no daemon, no long-running
process" — an external cron decides when to look, and the run answers one
question, *what is due now?* The draft's `queue.ts` retry/backoff/reconcile
machinery becomes "if the runner exits non-zero, remove the `agent-in-progress`
label and re-add `ready-for-agent`" — one `gh` call, no state file.

**Parallelism: start at 1.** The repo's own rules forbid two Gradle invocations
at once, and `./gw` gives each worktree a private `GRADLE_USER_HOME` — but the
detekt verdict in the pre-push hook takes ~9 minutes for a `shared/` change.
One issue at a time, measured, before any concurrency. An exclusive lock in
the runner's own directory is what enforces it: even if the timer fires twice,
the second runner exits immediately rather than starting a second Gradle build.

### Phase 2 — the learning loop

The goal: a failing run should leave behind something that makes the next run
better, without a human rewriting the runner each time.

**What not to build.** A new file that the agent edits and that every later
run reads is a second copy of a mechanism this repo already has — **900** live
ADRs in `docs/decisions/` (914 counting the archive) with a generated digest,
`scripts/check-review-verdicts.py` with a verdict file per change, and
`config/docs/review-verdicts-baseline.txt` for accepted debt. A free-text
lessons file would be a third system with no owner and no gate, and it would
compete for the same attention as `AGENTS.md`, which `check-agents-size.py`
already caps at 300 lines.

It is also a security hole. The repo is **public**, so issue bodies are
attacker-writable. An agent that writes "always push to main" into a file
every later run reads turns a single injection into a persistent one. The
proposal's own safety note — "разрешить агенту менять только файлы с
правилами" — grants exactly that.

**What to build instead, in two tiers.**

**Tier 1 — the agent proposes, a human accepts.** After a failing run, the
agent writes the lesson as a **comment on the pull request** — a proposed
rule, in the same PR as the change that taught it. A human merges it into the
repo's existing mechanism: a short ADR, or a line in the relevant skill. The
repo already enforces this shape for code (`check-agent-review.py` requires a
separate agent's review before a PR merges); a rule is at least as
consequential as code.

This is not a compromise — it is the repo's own design applied consistently.
And it is the only tier that survives the repo being public.

**Tier 2 — the runner keeps what it learned, locally.** The runner's own
directory (not the repo) holds `lessons.md`, written by the runner, not the
agent: the failing command, the exit code, and the first error line. The
runner appends this file to the guardrail for the *next* run, so a repeat
failure is visible immediately. Because it lives outside the repo, it is
scratch, not policy — and it is the human's job to promote a real lesson into
the repo.

Tier 2 has one property to keep: the file holds **failure signatures**, not
prose the model wrote. A signature is a command name and an error line; it
cannot carry an instruction. If the file ever holds text the model authored,
it is a Tier-1 comment instead.

**Where the reflection actually runs.** The obvious wiring — the runner runs
the gate, checks its exit code, and starts a second agent with the log — does
not fire, because the gate runs *inside* the agent's turn and the agent
exits 0 because it handled the failure. Two shapes that do work:

- **Inside the turn** (simplest): the prompt array's third prompt already
  ends with "if the gates failed, fix them and say why". The agent has the
  failure in context. Adding "and propose the rule as a PR comment" to that
  same prompt is one sentence, no new machinery.
- **After the turn** (if a shell-visible exit code is needed): the runner
  runs the gate itself after the session settles, and on failure starts a
  second session with the log. Only this shape gives the shell an exit code
  to branch on.

Start with the first. The second is a runner change, not a prompt change.

## Risks

| Risk | Mitigation |
|---|---|
| **Prompt injection via issue body.** The repo is public. | Only `ready-for-agent` issues (human-labelled) are picked up; the rule lives in the **system prompt**, which the extension builds natively — there is no file that can go missing and no user turn where it competes with the injection; the body is fenced as data; the agent cannot push outside `agent/*`; no auto-merge, ever. |
| **The claim is not exclusive because the object repeats.** | The nonce in the claim ref's message makes the object unique, and a test plants two claims with the same base and asserts the second is rejected. |
| **Two runners, one issue.** | The exclusive claim ref is the lock: it cannot be created twice. The local lock in the runner's own directory is the second layer, for two runners on one host. |
| **A killed run strands the issue.** | The claim is reclaimed after the staleness window; the runner reads the claimed issues as well as the queue. |
| **The agent's context is lost when a run dies.** | It is unrecoverable — the session is gone with the process. The work is redone from the brief, which is the contract. |
| **The runner exits 0 and does nothing.** | A lock file in an unwritable directory makes the lock fail, and the branch exits **successfully**. The runner would look healthy while selecting nothing. The lock path is `XDG_RUNTIME_DIR` (writable, verified), and the failure is loud. |
| **The runner sees a truncated queue.** | `gh issue list` returns 30 results by default; the repo has 76 ready issues. `--limit 500` is not optional. |
| **Compaction kills a turn mid-array.** | `compaction_start` is a typed event the runner sees; the runner waits for `agent_settled` and treats `aborted: true` as "the remaining prompts did not run". |
| **The agent breaks the build and pushes anyway.** | The prompt requires the gates to pass first; the pre-push hook runs the detekt verdict as a second, independent check. |
| **Cost.** | One model call per turn — so a three-prompt array plus a review is four calls, not one. Keep the array short. |
| **A bot PR that cannot satisfy the agent-review gate.** | The review is posted and then **re-counted** by applying the `agent-reviewed` label — posting the comment alone leaves the PR red, because a comment-triggered run has its checks counted on the default branch, not on the PR. |
| **A bot PR that is missing the change's registry entries.** | A PR that lands an OpenSpec change needs `.specify/issues/<slug>.yaml` and, once the contract is written, `.specify/reviews/<slug>.md` naming that PR. The prompts carry it; the runner decides per issue whether the change is one that needs them. |
| **A `fail` verdict is treated as a retry.** | It is not: the pull request is left red, no second verdict is posted, and no other issue is taken. |
| **The extension's agent works in the wrong directory.** | The session's `cwd` **is** the worktree — `AgentSessionConfig.cwd` — so there is no `cd` and no inherited directory to get wrong. |
| **A third-party extension runs code inside the agent.** | Six packages are installed, and Pi's own documentation says to review third-party package source before installing it. Pin exact versions, read the source of each before the first run, and keep the number of packages as small as the components allow — the first-party examples need no install at all. |
| **Two installed extensions fight over the same event.** | Pi runs handlers in load order, and the gate/review contract is *ours* — it is implemented in `contract.ts` and asserted by our tests, not inherited from whichever extension happened to register last. |
| **A lesson written by an agent becomes a permanent instruction.** | The agent proposes rules as PR comments; a human merges them into the ADR/skill mechanism. The runner's local `lessons.md` holds failure signatures (command, exit code, error line), not model-authored prose — a signature cannot carry an instruction. |

---

## What the draft got right, and what to keep

Keep: the issue→worktree→agent→PR shape; fencing the issue body as data;
`needs_human` as a terminal state; never auto-merging; the `gh` CLI over raw
API calls; a separate process per issue so one crash loses one issue.

Keep, and this is the draft's best insight: **Pi extensions are the right
frame.** The draft's error was not the language and not the framework — it was
the `state.ts`/`queue.ts`/`worktree.ts`/`runner.ts` split, a TypeScript core
that reimplemented what the SDK already gives.

Drop: the TypeScript core and its file split; the SQLite/JSON state store
(GitHub labels already hold the state, and a human can see it); the explicit
`git fetch` inside the worktree step (the runner fetches once); the
`origin/main` vs `main` distinction (use `origin/main` — the repo's `main` is
a local branch that may be behind).

Also drop: a `.agents/rules/lessons-learned.md` that the agent edits and every
later run reads. The repo already has 900 live ADRs with a generated digest,
plus a verdict file per change; a third rules file has no owner and no gate.
And on a public repo it is a persistent prompt-injection channel — see Phase 2.

## Not verified

- The cost of one full agent run on one of the 76 `ready-for-agent` issues.
- Whether the SDK's `createAgentSession` is stable across Pi versions, or
  whether the runner should pin a Pi version and check it at start-up. The
  hobby projects in this space (`pi-ops` and the rest) all pin one version.
- Whether an extension loaded into a session can also create *further* sessions
  through the SDK without the two interfering — the runner session and the
  work session are different sessions, and the extension's hooks must not
  fire in the wrong one.
- Whether `session.prompt()` resolves on the turn's end or only on the whole
  array's end. The three-turn sequence assumes the former; if it is the
  latter, the array is sent as one prompt and the array becomes a convention
  again.
- Whether `pi`'s RPC mode is worth using at all now that the extension owns
  the session. It is the fallback for driving Pi from *outside* Pi — a CI
  runner with no TTY, for instance — and it is not needed for the extension
  path.
- **Whether the components compose.** Six packages, each with its own idea of
  what a turn is and who owns the session, is a claim to be tested, not a
  fact. The first thing to build is a spike that installs them all and runs
  one issue end to end.
- **Whether `@drix10/agent-flow` should be the engine.** Its README describes
  this plan's shape almost exactly, Pi included, and its guard blocks tool
  calls directly on Pi — but it is used 176 times a week against
  `pi-subagents`' 329 003. Adopt it, or compose from the parts and borrow its
  design? The spike answers this too, and cheaper than an argument does.

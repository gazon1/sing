---
title: "NotebookLM stays outside the app; the external agent drives it and the brief is a note"
date: 2026-11-25
status: accepted
tags: [ai, knowledge, notes, mcp, research]
---

# NotebookLM stays outside the app; the external agent drives it and the brief is a note

## Context

The Knowledge Brief contract accepted in
[2026-11-22](2026-11-22-a-knowledge-brief-is-a-note-attached-to-its-owner.md) says in one
line that **Sing does not launch the provider**: an external agent researches, and the app
stores and shows a note with a `## Sources` section. [#3182](https://github.com/gazon1/sing/issues/3182)
then asks for a project Knowledge Wiki, and [#3183](https://github.com/gazon1/sing/issues/3183)
correctly refuses to let [#3189](https://github.com/gazon1/sing/issues/3189) implement a
NotebookLM integration before someone decides what "integration" means. Three candidates:

1. the external agent uses NotebookLM and writes a brief through the existing MCP surface;
2. the built-in AI connects to NotebookLM over an optional remote MCP connection;
3. JVM Desktop launches the `notebooklm` CLI through an isolated adapter.

There is a tested reference for (3) — [`gazon1/10x-chat`](https://github.com/gazon1/10x-chat)
`src/knowledge/` — with a provider-neutral contract, shell-free process invocation, JSON
output, normalized errors and an unknown-state signal (`mayHaveRun`). The temptation is to
port it. `notebooklm-py` is an unofficial client of undocumented APIs, so it also must not
become a persistence layer.

The spike that answered this is [the report](../research/3183-notebooklm-integration-boundary.md).
It ran against `notebooklm-py` **0.8.6** on this host. The live half — create a notebook,
ingest a source, get a cited answer — **was not executed**: the profile's local cookies are
valid but the Google session is expired, and re-authenticating needs a human browser login.

## Idea

1. **Port the 10x-chat adapter** into `core/process` behind a Kotlin port, reusing the
   existing `Subprocess`/`ProcessTree` seam and `mayHaveRun` semantics.
2. **Add an MCP client** to the built-in agent so the model can call a NotebookLM MCP server
   as one more provider.
3. **Decide it stays outside.** Change nothing in the app; keep the documented workflow, and
   write down what would have to become true to revisit it.

## Decision

Option 3. **NotebookLM is not integrated into Sing.** The external agent drives it, and the
brief stays a note. No new dependency, no subprocess in `commonMain`, no MCP client host, no
change to `docs/architecture/knowledge-briefs.md`.

The decision rests on three findings from the spike, not on caution:

**Asking a question is a write to state the app does not own.** `notebooklm ask` without
`--new` continues the notebook's *current* conversation and, on success, persists the new
id into the profile's context.json (`cli/chat_cmd.py:418`, `set_current_conversation`) —
the same file that holds the current notebook. The 10x-chat adapter marks `ask` as
`mutating: true` for this reason, but passes neither `--new` nor `-c`, so it inherits
whatever conversation is current and then moves it. Two callers on one profile interleave.
The repair that makes it explicit, `ask --new`, is documented as **destructive**: it deletes
the notebook's server-side conversation and the turns are not recoverable. An app holding
that button can destroy research a user did in the NotebookLM web UI, in a notebook they
share. That is the same class of harm the proposal pipeline exists to prevent, and it is
outside the app's data, so no proposal can guard it.

**The MCP surface does not start here, and Sing has no client.** `notebooklm-mcp` raises
`ModuleNotFoundError: fastmcp` in this installation — the `mcp` extra is not installed. Sing
imports `io.modelcontextprotocol` in `jvmMain` for the **server** only; `Client` and the
transports appear exclusively in `mcp-server`'s test. The build file states the SDK is
JVM-only and must not reach `commonMain`/`androidMain`. So option 2 is a new runtime
dependency, a new client host inside the agent, and a provider surface unverified on this
machine — for a capability that must be absent on Android by construction.

**The subprocess seam is cheaper than assumed, but the deadline is where the cost is.**
`Subprocess.runCapturing` already invokes without a shell and `ProcessTree` kills a tree with
a grace period. What it has no concept of is a deadline: `runCapturing` reads to EOF and
waits, and `awaitExit(t)` returns `null` without killing. Adding one is small. Honouring it
is not — a CLI killed mid-`source add` leaves the source possibly created, and the
`UNCONFIRMED_WRITE` / `mayHaveRun` machinery that makes that safe is a correctness surface
neither platform needs while the agent does the research. A shell-free spawn is not the same
thing as a safe adapter.

**WeKnora is rejected without a deployment.** Adopting it as the canonical store is a second
mandatory database beside Notes, which the epic's first invariant forbids. The decision does
not depend on its features, so nothing was benchmarked.

**No provider abstraction.** Nothing in `commonMain` gains a `KnowledgeProvider` interface
"for later". The 2026-11-22 ADR refused to put a provider in the domain; a port with no
implementation on either side is that refusal undone in a different shape.

## Rationale

The boundary was never "we lack a client". It is that the app's promise — a write asks, and
a failed write cannot destroy anything — cannot be extended to a store the app does not own.
The spike found the concrete instance of that: the one operation that looks like a read
(`ask`) is a mutation of shared server-side state with a destructive reset. Option 1 costs a
correctness surface and buys nothing (the agent already can). Option 2 costs a dependency and
a client host and buys a surface that does not run. Option 3 costs nothing and keeps the
promise that already holds.

The issue anticipated this outcome and asked for it explicitly if it were the honest one:
"If no viable in-app integration is justified, explicitly recommend keeping NotebookLM in the
external-agent workflow; do not force a provider abstraction."

The live half not being run does not weaken the decision. Its purpose was to confirm the
contract could be met at all; the contract is confirmed offline against the installed version,
and the findings that decide the boundary are about *state ownership*, which no amount of
successful citation output would change.

## Consequences

- **#3189 gets a no-go.** It stays open or is closed as "will not do"; it is not blocked work.
  The failure-semantics table in the report is there so a future go decision starts from the
  observed contract, not from re-reading upstream master.
- **The Knowledge Wiki work is unaffected.** #3184–#3188 (articles on Notes, the project
  library, list/read/query tools, build/refresh through proposals, cross-article review) do
  not depend on this outcome. Knowledge works when NotebookLM is absent, which was already
  an invariant; now it is the only supported configuration.
- **`docs/architecture/knowledge-briefs.md` is unchanged.** Its contract was not altered by
  this decision, so per the issue's own acceptance criterion it is not touched.
- **Android gains nothing and loses nothing.** No Python, no shell, no new dependency, and
  the MCP SDK's JVM-only rule stands undamaged.
- **The reuse signal is recorded, not consumed.** 10x-chat's adapter is accurate against
  0.8.6 — its error-code map covers every code the CLI emits, including the
  `UNCONFIRMED_WRITE` override that replaces retry advice on an unconfirmed write. If a future
  need arises, it is a specification to port, and the report says which parts it gets wrong
  (implicit conversation, no readiness wait).
- **A revisit has explicit gates.** Someone must need NotebookLM without an agent; a live
  disposable-account smoke test must be in the loop; and `ask` must have a non-destructive
  explicit-conversation mode, or the workflow must never reuse a notebook the web UI drives.
- **One thing is now known and was not before:** the local profile can look healthy
  (`doctor` all green, 23 cookies) while the online session is dead, and only
  `auth check --test` says so. That matters for any future diagnostic UI.

## Links

- [Spike report](../research/3183-notebooklm-integration-boundary.md)
- Issue [#3183](https://github.com/gazon1/sing/issues/3183) — this decision; parent [#3182](https://github.com/gazon1/sing/issues/3182); consumer [#3189](https://github.com/gazon1/sing/issues/3189) — no-go
- [2026-11-22-a-knowledge-brief-is-a-note-attached-to-its-owner.md](2026-11-22-a-knowledge-brief-is-a-note-attached-to-its-owner.md) — the contract this decision preserves
- `docs/architecture/knowledge-briefs.md` — the workflow, unchanged
- [gazon1/10x-chat `src/knowledge/notebooklm.ts`](https://github.com/gazon1/10x-chat/blob/56641644478fbd7d4ef5a0c62d884a007e4c1543/src/knowledge/notebooklm.ts) — the adapter held in reserve
- `shared/src/jvmMain/kotlin/com/singularity/todo/core/process/Subprocess.kt` — the seam a future adapter would reuse

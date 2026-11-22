---
title: "The agent CLI owns its standard output, and `open` waits for the screen to settle"
date: 2026-11-22
tags: [introspection, cli, agent, output-contract]
status: accepted
---

## Context

Writing the agent procedure for headless inspection (#3044) meant running the delivered
driver from the CLI, in the shape the procedure would document. Two things the reference
document promised did not hold, and neither had a test that could have noticed:

- `agent open` printed the application's own log lines — `kotlin-logging: initializing…`,
  `Debug: (AuthGateway) …` — to **standard output before the JSON**. `agent screens` was
  clean, because nothing there touches the container; any command that starts Koin was not.
  The documented `… | jq` pipe failed on every such command with `jq: parse error`, and an
  agent that parses standard output strictly would have called the host broken.
- `agent open` returned `status: "loading"` — in the CLI, **always**. The session is created
  and its state collector starts on another dispatcher, so the snapshot read at
  construction time is the `Loading` the adapter starts from. Nothing else occupied the
  process, so the collector had not run by the time the result was printed. The MCP host did
  not show this, because it stays alive and the next `screen_snapshot` sees the settled
  state. Only the CLI — one process, one command — made it visible, which is exactly the
  surface the procedure uses.

Both were found by running the documented commands, not by reading the code. The existing
suite drives `ScreenInspectorRuntime` and polls `awaitLoaded` itself, so it modelled the
caller as a poller and never as the CLI.

## Idea

Take the two defences the MCP server's protocol channel already had and apply them to the
CLI's result channel, and give `open` the wait its own caller would otherwise perform.

The MCP server reserves standard output (`ProtocolStdout.reserve()`) and points the log at
standard error (`StderrLogWriter`), because a protocol client parses every line. The CLI has
the same reader and the same requirement; it simply never entered that branch of
`runToolServer`.

For the settle: an agent's first observation should be one it can act on. "Poll until it is
not `loading`" is knowledge the caller should not have to hold, and a procedure that spelled
it out would be documenting a race rather than a workflow.

## Decision

- `runToolServer` installs stderr logging and reserves standard output **before** dispatching
  to `ScreenInspectorCli`, and hands the reserved stream in as the CLI's result sink
  (`ScreenInspectorCli.run(..., resultOut)`). `printJson` writes there; `System.out` is no
  longer the result channel.
- The result sink is a `ThreadLocal`, not a plain field. The production model is one command
  per process, so a field looked correct; the test suite runs classes concurrently, and a
  shared field let one running test redirect another test's result. `run` sets the value and
  removes it in a `finally`. The first version of the CLI test also redirected `System.out`,
  which is the same hazard one layer out; it now asserts the reserved stream alone.
- `ScreenInspectorRuntime.open` is `suspend` and waits for the session to leave `loading`
  (5 s deadline, 20 ms poll). At the deadline it returns whatever the state is, which is
  `loading` — the state is genuinely unknown then, and `error` is a different answer. That
  branch is `awaitSettledStatus`, extracted so a test can drive the deadline with a clock it
  controls: a fake that never settles proves the wait runs to its deadline and returns, and a
  fake that settles proves it stops early.
- The runtime test asserts that `open` itself reports `loaded`, so the property is pinned
  where a poller cannot hide it.
- The Android host (`ScreenInspectorHost`, whose `handle` is synchronous by design — a binder
  thread has one request and one answer) reaches the suspend `open` through the `runBlocking`
  bridge it already uses for `invoke` and `wait`. That is the same boundary the file's own
  suppression note describes, not a new pattern.

## Rationale

The alternative — document "pipe through `jq`, and if you see a parse error ignore the first
few lines" or "call `snapshot` twice before you trust it" — teaches the caller to work
around a defect. A procedure that says "the first read is `loading`, here is a journey with
eight redundant snapshot steps" is a procedure that will be copied and then drift.

The CLI is not a new output owner: it was always supposed to be JSON-only (the reference
document says so), and the code path just stopped one line short of making that true. The
reservation is the mechanism already in the repository; this change reuses it rather than
inventing a second one.

The settle wait is bounded and returns rather than throws at the deadline, because a screen
that never settles is a real state an agent may need to report, and `operation_timeout`
belongs to the caller's own deadline, not to `open`.

## Consequences

- Every CLI command that starts Koin now prints exactly one JSON line to standard output;
  the `-Duser.home` copy, the profile flags and `| jq` work as documented.
- `open` costs up to 5 s on a screen that never settles, and its result is `loading` rather
  than a premature `loaded`.
- `ScreenInspectorRuntime.open` is `suspend`; the test harness runs its body under one
  `runBlocking`, the same bridge a CLI process uses.
- The MCP tools are unchanged in behavior (`ScreenInspectorTools` was already `suspend`), so
  the host and the CLI now agree on what `open` returns.
- The procedure lives in the skill
  `singularity-todo-headless-screen-inspection`; the reference document
  `docs/architecture/screen-inspector.md` names the stdout contract it now keeps.

## Links

- Issue: [#3044](https://github.com/gazon1/sing/issues/3044)
- Reference: `docs/architecture/screen-inspector.md`
- Skill: `.agents/skills/singularity-todo-headless-screen-inspection/SKILL.md`
- Related: `2026-11-20-the-local-screen-inspector-host-is-explicit.md`,
  `2026-11-21-the-host-inspects-a-real-graph-session-not-the-window.md`

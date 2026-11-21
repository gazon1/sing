---
title: "The host inspects a real-graph session, not the window"
date: 2026-11-21
tags: [introspection, mcp, host, scope, android]
status: accepted
---

## Context

Issue #3042 has two acceptance lines that a first implementation can satisfy on paper and
miss in practice:

- "Android debug/test and JVM Desktop development/test can expose the same logical contract";
- "A real running app session can be inspected and an action can be invoked through its
  existing intent path".

The change that introduced the opt-in host — ADR
`2026-11-20-the-local-screen-inspector-host-is-explicit.md` — delivered the JVM Desktop side
and documented Android as deferred. Review of that change (PR #3065) asked two questions that
the ADR answered too generously:

1. Does the host inspect the desktop session the user currently has on screen?
2. Is Android actually covered, or is the requirement simply restated in the ADR?

Both have the same shape: a requirement whose words ("a real running app session", "the same
logical contract") can be read as satisfied by something adjacent to what was asked for. This
records the honest reading.

## Decision

1. **The host inspects a real-graph session, not the on-screen window, and the docs say so.**
   The host is the tool-server process: it builds its own Koin container from the application's
   real module list and opens its own `TaskDetailCoordinator` from it. That coordinator is the
   production class and its actions reach `TaskDetailCoordinator.onIntent`, so the contract and
   the behavior path are real — but a Desktop window and the host process are two processes with
   two graphs and two sessions. The host cannot observe transient on-screen state such as an
   unsaved editor buffer. No document claims otherwise any more.
2. **A live-window attach is not built here.** It needs a transport from the GUI process to the
   tool-server process (a loopback socket, since the Issue forbids anything reachable beyond
   local), or a host that runs inside the GUI process instead of `--mcp-server` mode. Both are
   larger than an opt-in gate, both reopen the threat model (a listening socket inside the app),
   and the CLI/MCP contract does not need them: the contract is about an agent operating a
   screen, not about mirroring a specific window. The gap is recorded rather than papered over.
3. **Android stays out of #3042 and gets its own issue.** The inspector runtime is `jvmMain`
   code; an Android host needs a lifecycle seam (the app process, not a `main` entry point),
   a debug-only gate that cannot ship, and its own tests. #3042 is not presented as complete on
   the strength of a comment in an ADR: the Android criterion is tracked in
   [#3074](https://github.com/gazon1/sing/issues/3074), and #3042 remains open until it lands.
4. **The release gate is enforced, not merely defaulted.** ADR
   `2026-11-20-the-local-screen-inspector-host-is-explicit.md` relied on "the release pipeline
   passes no property". That is a convention: `-P` reaches any task. The packaging tasks in
   `:desktopApp` and `:mcp-server` now refuse to build an artefact with the host compiled in,
   and each launcher module carries a test that reads the *generated* constant rather than
   supplying `false` to itself.

## Rationale

- An acceptance criterion is a claim about behavior, and the two readings above differ in
  behavior, not in wording. Writing the weaker reading down is what lets the stronger one be
  scoped and priced instead of assumed.
- The alternative to recording the gap is the failure mode the reviews found: a test that passes
  `false` to itself and an ADR that says "the running application" about a second process. Both
  look like evidence and neither is.
- Enforcing the release gate in Gradle costs one `doFirst` per packaging task and removes a
  class of mistake a human has to remember. A pipeline convention is exactly the thing that
  silently changes.

## Consequences

- `docs/architecture/screen-inspector.md` states that the host inspects a real-graph session
  built from the application's modules, and names the on-screen gap.
- A developer who wants a host-enabled build still gets `:desktopApp:run`, `:mcp-server:run`,
  `:mcp-server:jar` and the test tasks; only the packaging tasks refuse.
- The Android criterion is tracked separately; #3042 stays open until both platforms expose the
  contract.
- Should a live-window attach become a requirement, it is a new decision with a new transport,
  not a reinterpretation of this one.

## Links

- Issue: https://github.com/gazon1/sing/issues/3042
- Epic: https://github.com/gazon1/sing/issues/3038
- Opt-in host: `docs/decisions/2026-11-20-the-local-screen-inspector-host-is-explicit.md`
- Contract: `docs/decisions/2026-11-18-the-reusable-screen-introspection-contract.md`
- Reference: `docs/architecture/screen-inspector.md`
- Implementation: `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/introspection/`,
  `mcp-server/build.gradle.kts`, `desktopApp/build.gradle.kts`

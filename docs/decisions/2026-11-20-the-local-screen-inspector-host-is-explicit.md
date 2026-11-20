---
title: "The local screen-inspector host is explicit"
date: 2026-11-20
tags: [introspection, mcp, agent, host, safety]
status: accepted
---

## Context

Issue #3042 sits in the middle of the headless introspection rollout (#3038, delivered in
pieces through #3040 and #3041). After #3041, the headless screen contract is a real
transport: the inspector runtime lives in the tool-server module and is exposed both as MCP
tools on the existing `:mcp-server` / desktop `--mcp-server` server and as a JSON CLI mode of the
same entrypoint.

The remaining acceptance line for the rollout is the host: the contract has to be reachable
from a real running development/test application, through a host that is explicit, opt-in,
release-safe, and fail-closed — not from a test-created coordinator or an implicit process.

The platform split this decision makes: the host is the process the running app hands to the
agent, and the in-process bridge over the real coordinator intent path is the only bridge that
needs building. For the desktop app that host process is the app itself, in `--mcp-server` mode;
for Android the running app hosts the agent in-process, over the same adapter. The opt-in below
is expressed at the tool-server process boundary, which is where the JVM/desktop host decision
lives.

## Decision

1. **The local app host is the tool-server process the running app hands to the agent.** No new
   transport, no second MCP server, no loopback socket: the existing stdio/MCP channel *is* the
   transport, and the in-process bridge (the tool-server runtime over the real coordinator path) is
   the only bridge that needs building — the alternative #3041 rejected as "no demonstrated need
   beyond existing tooling". #3042 keeps it.
2. **The host is opt-in, through the `--inspector` flag on the tool-server process**
   (`McpServerCommandProvider.INSPECTOR_HOST_FLAG`). Without the flag the host does not start, in any
   build. The flag is read whole from the process arguments, the same way the profile selector is —
   never defaulted, never partially matched.
3. **The start-up resolves the opt-in and the server reads the resolved value.** The opt-in travels
   into `ToolServerStartupContext` as `inspectorHostOptIn` and resolves to `inspectorHostEnabled`;
   `BuildTheServer` registers the screen inspector tools only when the host may run, and otherwise
   leaves the toolbox without them.

## Rationale

- An explicit flag keeps the safety property testable and the failure mode boring: the decision is
  one boolean, resolved once, and a process that did not opt in starts no host — so no host is started
  by default, and a release build is no different from a debug one until the opt-in is made. That is
  the "development/test builds or sessions" line the safety requirements draw, expressed without a
  second source of truth.
- The CLI host needs no flag of its own: it is entered through an explicit command
  (`agent`, `screen-inspector`, `--agent`, `--screen-inspector`), so its opt-in is structural.

## Threat model

The host is a local development/test surface, and the threat model it is written against is the
one the introspection contract already carries — the opt-in does not relax it:

- no access to arbitrary files, no shell execution, no credentials or tokens, no unrestricted app
  data;
- actions are allow-listed and validated before dispatch, with `additionalProperties: false`;
- dispatch goes through the real screen/coordinator intent path, never a bypass;
- nothing about the host is reachable from outside a local process.

## Consequences

- The desktop app exposes the host through its existing `--mcp-server` mode plus the flag:
  `--mcp-server --inspector --profile-id=<id>`.
- The standalone server exposes the same contract the same way:
  `--inspector --profile-id=<id>` (plus the MCP client driving it).
- Release builds carry the host but it is off unless opted in; an opted-in release process is a
  development/test session by construction.
- Tests pin the fail-closed default: no opt-in → no host; opt-in → host may run.

## Links

- Issue: https://github.com/gazon1/sing/issues/3042
- Epic: https://github.com/gazon1/sing/issues/3038
- Contract: `docs/decisions/2026-11-18-the-reusable-screen-introspection-contract.md`
- Reference: `docs/architecture/screen-inspector.md`
- Implementation: `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/` (`ToolServerStartupContext`,
  `ToolServerStartup`, `BuildTheServer`, `ToolServerMain`, `McpServerCommandProvider`) and
  `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/introspection/`

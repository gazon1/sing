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
needs building. For the desktop app that host process is the app itself, in `--mcp-server` mode.
The opt-in below is expressed at the tool-server process boundary, which is where the
JVM/desktop host decision lives.

## Decision

1. **The local app host is the tool-server process the running app hands to the agent.** No new
   transport, no second MCP server, no loopback socket: the existing stdio/MCP channel *is* the
   transport, and the in-process bridge (the tool-server runtime over the real coordinator path) is
   the only bridge that needs building — the alternative #3041 rejected as "no demonstrated need
   beyond existing tooling". #3042 keeps it.
2. **The host is compiled in only when the build asks for it.** `:mcp-server` and `desktopApp`
   generate a `*InspectorHostBuildConfig` constant from the Gradle property
   `singularity.inspector.host.enabled` (default `false`). The release pipeline passes no such
   property, so a release artifact carries the host out and no launch argument can turn it on.
   The value is a build input, never a JVM system property, so a user cannot flip it at launch.
3. **The host is opt-in at runtime through the `--inspector` flag on the tool-server process**
   (`McpServerCommandProvider.INSPECTOR_HOST_FLAG`). Without the flag the host does not start, even
   in a build that compiled the capability in. The flag is read whole from the process arguments,
   the same way the profile selector is — never defaulted, never partially matched.
4. **The `agent` CLI is a host too, and the build gate applies to it.** It needs no `--inspector`
   flag — entering the command is the opt-in — but a build compiled without the capability answers
   `unsupported` and exits 2 before it parses a command or starts a container. A release build
   therefore has no CLI dispatch path either.
5. **The entry point resolves the opt-in and the server applies the resolved value.** The entry
   point (desktop `main`, standalone `Main`) passes the compiled-in capability to `runToolServer`;
   `runToolServer` ANDs it with the flag and hands the result to the start-up as
   `inspectorHostEnabled`; `BuildTheServer` registers the screen inspector tools only when it is
   true. Otherwise the toolbox has no `screen_*` tool at all, and the server's `instructions` do
   not advertise them.

## Rationale

- Two independent gates keep the safety property testable and the failure mode boring: the build
  decides whether the capability exists at all, and the process decides whether to use it. A
  release build is not "off by default" in the sense of a dormant feature — the host is not there.
  A development process that did not pass the flag starts no host either. That is the
  "development/test builds or sessions" line the safety requirements draw, expressed without a
  second source of truth.
- A JVM property would be the wrong seam: `-D` is user-overridable at launch, so a release jar
  could be opted in by anyone who knows the property name. The Gradle property is fixed when the
  artifact is compiled.
- The CLI host needs no flag of its own: it is entered through an explicit command
  (`agent`, `screen-inspector`, `--agent`, `--screen-inspector`), so its opt-in is structural. It
  is still build-gated: a release build refuses the command, because the capability is not there.
- The test-created coordinator #3039 used was sufficient to prove the contract, but it is not the
  running application's graph. The tool-server host reuses the real Koin graph, the real
  coordinator and the real intent path, so what an agent inspects is the application, not a
  fixture.

## Lifecycle

The host has no background service of its own; it lives exactly as long as the tool-server
process:

- **Start** — the process is launched with `--inspector` from a build that compiled the capability
  in; the start-up registers the `screen_*` tools on the existing MCP server.
- **Discover** — `screen_list` reports the supported screens and parameter schemas;
  `screen_sessions` reports open sessions.
- **Invoke** — `screen_open` binds a session to the real screen path, `screen_invoke` dispatches a
  named, validated action, and `screen_wait` observes completion.
- **Disconnect** — the MCP client closes its stdio transport (or the process is terminated).
- **Stop** — the runtime closes its sessions; a shutdown hook closes any session still open. No
  work outlives the process, and nothing listens on a socket.

## Threat model

The host is a local development/test surface, and the threat model it is written against is the
one the introspection contract already carries — the opt-in does not relax it:

- no access to arbitrary files, no shell execution, no credentials or tokens, no unrestricted app
  data;
- actions are allow-listed and validated before dispatch, with `additionalProperties: false`;
- dispatch goes through the real screen/coordinator intent path, never a bypass;
- nothing about the host is reachable from outside a local process (the transport is stdio, not a
  network socket);
- an unregistered tool is not a hidden capability: without the opt-in there is no `screen_*` tool
  on the server, and an unknown-tool error is the fail-closed answer.

## Platforms

- **JVM Desktop** — delivered here. The host is the existing desktop / `:mcp-server` tool-server
  process, guarded by the build capability and the `--inspector` flag. The application always uses
  the real runtime graph.
- **Android** — the same logical contract is what the Android screen path already implements (the
  canonical contract and the Task Detail adapter live in `commonMain`), and the intended Android
  host is the same in-process bridge. **This change does not wire an Android host**: the inspector
  runtime lives in `jvmMain` and is not compiled into the Android target. Android exposure is a
  separate vertical slice; this ADR does not claim it.

## Consequences

- The desktop app exposes the host through its existing `--mcp-server` mode plus the flag, in a
  build that compiled the capability in:
  `-Psingularity.inspector.host.enabled=true` and `--mcp-server --inspector --profile-id=<id>`.
- The standalone server exposes the same contract the same way: build with
  `-Psingularity.inspector.host.enabled=true`, then launch with `--inspector --profile-id=<id>`.
- Release builds do not pass the property, so the host is not compiled in and no argument can
  enable it.
- Tests pin both gates: the resolution is false without either one, and the registration seam
  registers no `screen_*` tool when the resolved value is false.

## Links

- Issue: https://github.com/gazon1/sing/issues/3042
- Epic: https://github.com/gazon1/sing/issues/3038
- Contract: `docs/decisions/2026-11-18-the-reusable-screen-introspection-contract.md`
- Reference: `docs/architecture/screen-inspector.md`
- Implementation: `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/` (`ToolServerStartupContext`,
  `ToolServerStartup`, `BuildTheServer`, `ToolServerMain`, `McpServerCommandProvider`) and
  `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/introspection/`

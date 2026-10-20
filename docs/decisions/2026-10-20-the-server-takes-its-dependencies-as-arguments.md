---
title: The server takes its dependencies as arguments
date: 2026-10-20
status: accepted
tags: [mcp, di, testing]
---

# The server takes its dependencies as arguments

## Context

The MCP server's entry point reached into the global Koin container seven times: for the
database, for the profiles, for the remote configuration, for the tool list, for the
recorder, for the ADR storage, and for the directory the call records are written to.

That is not unusual in itself — an application resolves its graph and then uses it. What
made it a problem is that the *server* could not be exercised without the container: a
test of the server's own decisions needed a global container, and a global container is
global state. The first test of the kill switch failed twice for that reason alone — a
test that started and stopped the global Koin context raced with the tests running
beside it in the same JVM, and the failure it produced ("a Koin application has already
been started") had nothing to do with the switch.

The `ToolRegistrar` had already been moved in the same direction: it takes its tool list
as an argument instead of fetching it, and the test of the kill switch is now a plain
function of its arguments.

## Idea

- **A.** Leave it; the container is the application's architecture and the server is part
  of it.
- **B.** Resolve the dependencies once, in one place, and pass them on as arguments.
- **C.** Replace the container with hand-written wiring in the server.

## Decision

**B.**

`ServerDependencies` holds the server's dependencies — the tools, the database, the
remote configuration, the profiles, the recorder, the ADR storage and the data
directory — and `ServerDependencies.fromKoin()` is the **one** place in the server that
reads the global container. Everything downstream takes what it needs as an argument:
`verifyDatabase(dependencies)`, `bootstrapProfiles(dependencies, profileId)`,
`readToolConfiguration(dependencies)`, `buildServer(dependencies, …)`.

C was not taken. The container is the application's architecture, and a server that
wired its own graph would be a second graph — a second set of bindings, a second set of
scopes, and a second thing to keep in step with the app. The dependency *source* is not
the problem; the *reach* is.

## Rationale

The point is testability, and the testability is what proves the decisions are right.
With the dependencies as arguments, a test of the server's behaviour — which tools are
exposed, what the configuration status is, what happens to a flag that names no tool —
is a plain call, with no container and no global state, and therefore no race.

It also makes the *shape* of the server visible. Seven `GlobalContext.get()` calls in one
file is a smell that the file's own readers can see; a class named `ServerDependencies`
with seven named fields is a list of what the server needs, which is a thing you can
review.

The optional dependencies stay optional: the recorder and the ADR storage are absent in
some deployments, and a server that cannot record calls or answer the ADR resources
still serves its tools.

## Consequences

- The global container is read in exactly one place in the server.
- The server's decisions can be tested without a container, and the tests that exist now
  are the ones that found the inert kill switch.
- A new dependency has to be added to `ServerDependencies` and to the one factory — two
  places, both visible, instead of a lookup that could be forgotten.
- The data directory moved with the rest: the registry snapshot writer takes the
  directory as an argument, so it no longer needs the container either.

## Links

- `mcp-server/src/main/kotlin/com/singularity/todo/mcp/ServerDependencies.kt`
- `mcp-server/src/main/kotlin/com/singularity/todo/mcp/Main.kt` — the entry point
- `mcp-server/src/test/kotlin/com/singularity/todo/mcp/ToolRegistrarTest.kt` — the test
  that the global state used to break
- `docs/architecture/mcp-server.md` — the server, as it is now

---
title: MCP stdio server blocking lifecycle — session.onClose + Job.join
date: 2026-09-08
status: accepted
tags: [mcp, kotlin-sdk, stdio, coroutines, lifecycle]
---

# MCP stdio server blocking lifecycle — `session.onClose` + `Job.join()`

## Context

`:mcp-server` JVM module started, printed `"singularity-todo MCP server started"`, then exited within ~1 second. Connecting clients (ZCode CLI, Claude Code) saw an empty stdout stream and reported `"Connection closed"`.

The natural assumption was that `Server.createSession(transport)` should block — it is the only call site in the SDK that "starts the MCP protocol loop", and the SDK's class name `ServerSession` suggests long-lived session ownership. But the bytecode of `ServerKt::createSession` shows it is a `suspend` function that schedules three child coroutines on an internally-managed `SupervisorScope` and **returns immediately**. There is no caller-visible blocking primitive.

Three internal coroutines run inside `StdioServerTransport`:
- `readerPump` — reads `input.readAtMostTo(...)` → pushes to `readChannel`
- `processorPump` — parses JSON-RPC frames → invokes `onMessage` callbacks
- `writerPump` — drains `writeChannel` → writes JSON-RPC frames to `outputSink`

All three are children of `transport.effectiveScope`, a `SupervisorScope` the SDK owns internally. When `main()` returns, the JVM tears the process down and these coroutines die with the only thread that was running them. The result: parent MCP client reads EOF on its end of the stdout pipe and treats this as a broken connection.

The upstream `kotlin-sdk:0.15.0` ships two complete reference stdio servers (`samples/weather-stdio-server`, `samples/kotlin-mcp-server`), both of which document the correct pattern, but neither file documents *why* the pattern is necessary — only that it is.

## Decision

**Pattern (canonical, taken verbatim from `samples/weather-stdio-server/src/main/kotlin/.../McpWeatherServer.kt:55-62`):**

```kotlin
fun main(args: Array<String>): Unit = runBlocking {
    // … startup validation …
    val server = buildServer()

    val transport = StdioServerTransport(
        input = System.`in`.toByteReadChannel().asSource().buffered(),
        output = System.out.asByteWriteChannel().asSink().buffered(),
    )

    val session = server.createSession(transport)   // returns immediately
    val done = Job()
    session.onClose { done.complete() }             // session-level close hook
    done.join()                                      // ← this is what holds the JVM up
}
```

Why session-level (not server-level) `onClose`:
- `session.onClose` fires when this particular session's transport closes.
- `server.onClose` fires when the last session of the server closes — equivalent in this single-session stdio case, but session-level is more precise and matches the canonical sample.
- We do **not** install a `Runtime.addShutdownHook` for `Server.close()` because the session onClose will propagate the close up to the server via its own `ServerSessionRegistry.removeSession` hook.

`done.join()` is the blocking primitive that holds `runBlocking` open. Until the SDK closes the transport (which it does on stdin EOF, on unrecoverable read/write errors, or on explicit `session.close()`), `done` stays incomplete and the coroutine suspends — keeping the JVM alive.

## Rationale

**Why not `awaitCancellation()`?** Equivalent in effect for stdio, but `Job.join()` gives us a single, named, easy-to-read extension point if we ever need to add per-session cleanup (close another scope, flush WAL again, log disconnect). Both stdio samples use `Job.join()` for exactly this reason.

**Why not a server-level `onClose`?** Functionally identical here, but session-level matches the upstream sample and reads as "this session is over" rather than "the server is over (because all sessions closed)".

**Why no `System.out.flush()` at startup?** A previous attempt added `System.out.flush()` before and after transport creation as a workaround for an early-exit hypothesis. It was a red herring — the real cause was the missing blocking primitive, and `flush` does nothing to keep the JVM up.

**Why the inner three coroutines don't suffice on their own:** They are coroutines, not threads. The JVM has no obligation to keep a process alive when only background coroutines remain. `runBlocking { … done.join() }` adds a real, on-stack continuation that pins the main thread, just like `Thread#join()` would.

## Consequences

- Process exit semantics change from "instant" to "on stdin EOF or session error". A passing test asserts the process stays alive ≥3s with empty stdin.
- `Runtime.getRuntime().addShutdownHook { server.close() }` becomes redundant for normal EOF exits — `onClose → done.complete() → done.join() returns → runBlocking exits → JVM exits cleanly`. We keep the shutdown hook only as a backstop for SIGTERM.
- MCP client (ZCode CLI) now sees the `initialize` roundtrip succeed and can list/call tools.
- The downstream `ToolRegistrar` and tools still run inside `runBlocking { koogTool.execute(args) }` per call — coroutine scope inside the request handler, no change.
- `./gradlew :mcp-server:test` now includes a regression test (`McpServerEndToEndTest.server_blocks_until_stdin_closes`) that asserts `process.isAlive` after 3s of empty stdin. If anyone removes the blocking primitive, this test fails.

## Links

- SDK reference: `samples/weather-stdio-server/src/main/kotlin/io/modelcontextprotocol/sample/server/McpWeatherServer.kt:55-62`
- SDK reference: `samples/kotlin-mcp-server/src/main/kotlin/io/modelcontextprotocol/sample/server/server.kt:194-209`
- SDK source: `kotlin-sdk-server/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/server/StdioServerTransport.kt` (authoritative pipeline reference — `start()`, `readerPump()`, `processorPump()`, `writerPump()`, `transitionToStoppedNaturally()`)
- Local code: `mcp-server/src/main/kotlin/com/singularity/todo/mcp/Main.kt`
- Test: `mcp-server/src/test/kotlin/com/singularity/todo/mcp/McpServerEndToEndTest.kt`

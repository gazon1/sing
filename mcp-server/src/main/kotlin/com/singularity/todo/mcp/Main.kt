package com.singularity.todo.mcp

/**
 * The standalone tool server's entry point.
 *
 * The server itself lives in the shared JVM runtime — `runToolServer` in
 * `com.singularity.todo.mcp.ToolServerMain` — because the desktop application runs the
 * same server for an external agent, and two servers would be two toolsets that drift.
 *
 * This module is what remains of the server as a *product*: a self-contained jar that
 * an editor can spawn and speak MCP to.
 *
 * ```
 * ./gradlew :mcp-server:run --args="--profile=ai-agent"
 * ```
 *
 * The argument contract is in `specs/014-acp-client-agent-activity/contracts/tool-server-cli.md`:
 * `--profile-id` works against a profile given on the command line and never opens the
 * stored active-profile state; `--profile` keeps its legacy label meaning and is
 * deprecated.
 */
fun main(args: Array<String>): Unit = runToolServer(args)

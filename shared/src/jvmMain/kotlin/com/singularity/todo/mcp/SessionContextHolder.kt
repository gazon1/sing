package com.singularity.todo.mcp

import com.singularity.todo.core.observability.ToolCallContext
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession

/**
 * Who is connected, and in which session.
 *
 * Late-bound, because the session is created *after* the tools are registered, and the
 * tool calls it describes happen after that. A holder rather than a constructor
 * argument, for the same reason the registrar takes a supplier: the value is not known
 * when the thing that needs it is built.
 */
internal class SessionContextHolder {
    @Volatile
    var session: ServerSession? = null

    fun current(): ToolCallContext {
        val session = this.session
        return ToolCallContext(
            sessionId = session?.sessionId,
            client = session?.clientVersion?.let { "${it.name}@${it.version}" },
        )
    }
}

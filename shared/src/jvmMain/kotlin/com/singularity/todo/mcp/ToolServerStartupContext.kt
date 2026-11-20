package com.singularity.todo.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * The state the start-up's steps share.
 *
 * A step reads what the steps before it produced and writes what it produces. The
 * fields are nullable because the order decides when each of them is filled, and the
 * order is written down in one place: [ToolServerStartupOrder].
 *
 * A step that reads a field the order has already filled reads it with `checkNotNull`.
 * A step that runs before the step that writes the field is an order that was edited,
 * and it says so there, rather than skipping the step and letting the server start
 * without what it needed.
 */
internal class ToolServerStartupContext(
    /**
     * What the caller asked for: a profile id, a legacy label, or nothing.
     */
    val selection: ProfileSelection,
    /**
     * The process entry point's container starter.
     *
     * It is supplied by the launcher rather than built here: this module is a library
     * used by two processes, and a library does not own the composition root.
     */
    val koinStartup: ToolServerKoinStartup,
    /**
     * The explicit opt-in that lets the local screen-inspection host run in this process.
     *
     * The host is opt-in: a process that did not opt in starts no host, in any build. The
     * opt-in is build-bound — a release build cannot host — and it is an input to the decision,
     * not the decision: the start-up resolves it and the host reads the resolved value.
     */
    val inspectorHostOptIn: Boolean = false,
) {
    /**
     * The profile the caller named, or null when the caller named none.
     */
    val fixedProfileId: String? = (selection as? ProfileSelection.FixedId)?.id

    /**
     * The legacy label the caller passed, or null when it passed none.
     */
    val legacyLabel: String? = (selection as? ProfileSelection.LegacyLabel)?.name

    /**
     * The dependencies the graph resolved.
     *
     * Written by the step that resolves them; null until that step has run.
     */
    var dependencies: ServerDependencies? = null
        internal set

    /**
     * The tool configuration the remote flags asked for.
     *
     * Written by the step that reads it; null until that step has run.
     */
    var configuration: ToolConfiguration? = null
        internal set

    /**
     * The holder the session is bound to.
     *
     * It exists from the start: the registrar is built with a supplier of the session,
     * and the session is created after the tools are registered.
     */
    val sessionContext = SessionContextHolder()

    /**
     * Whether the local screen-inspection host may run in this process.
     *
     * The host is opt-in and nothing else: an explicit opt-in from this process, and no
     * default. A process that did not opt in resolves to false here, and a release build
     * resolves to false however it was asked.
     */
    val inspectorHostEnabled: Boolean
        get() = inspectorHostOptIn

    /**
     * The server, once it is built.
     *
     * Written by the step that builds it; null until that step has run.
     */
    var server: Server? = null
        internal set

    /**
     * The server, ready to serve.
     *
     * The step that builds the server is in the order, and the order is written down in
     * one place. So the server is there — and if it is not, the order was edited without
     * the runner being told, which is a defect in the sequence rather than a start-up
     * failure a client could be told about.
     */
    fun ready(): ToolServerStartup.Ready =
        ToolServerStartup.Ready(
            server = checkNotNull(server) { "The start-up's order does not build a server." },
            sessionContext = sessionContext,
        )
}

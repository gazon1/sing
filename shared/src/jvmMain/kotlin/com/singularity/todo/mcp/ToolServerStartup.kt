package com.singularity.todo.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * What the server's start-up decided.
 *
 * ## Why the outcome is a value and not an exit
 *
 * The start-up used to be one function that reserved the protocol channel, parsed the
 * arguments, refused the ones it could not obey, started the container, opened the
 * database and built the server — and two of its refusals were `exitProcess` calls in
 * the middle of that sequence. So "what does the server do when it cannot start?"
 * could only be answered by reading the whole function, and the answer was not a
 * thing a test could hold.
 *
 * Now the start-up returns what it decided: a server that is ready to serve, or a
 * refusal that names the code the client is told, the message the client is told and
 * the code the process leaves with. The entry point acts on the outcome; the outcome
 * never acts on the process.
 */
internal sealed interface ToolServerStartup {

    /**
     * The server is up: its container is started, its database is open, its tools are
     * registered and its shutdown hook is installed.
     *
     * It has not served a frame yet — that is the entry point's next step.
     *
     * @property server the server, with every tool registered on it.
     * @property sessionContext the holder the session is bound to, once it exists.
     */
    data class Ready(val server: Server, val sessionContext: SessionContextHolder) : ToolServerStartup

    /**
     * The server refused to start.
     *
     * @property code the JSON-RPC error code the client is told.
     * @property message what the client is told. Neutral by contract: it says what
     *   happened, and it carries no path, no stack trace and no exception message.
     * @property exitCode the code the process leaves with.
     */
    data class Refused(val code: Int, val message: String, val exitCode: Int) : ToolServerStartup
}

/** The server could not start: its dependencies or its database are unavailable. */
internal const val EXIT_STARTUP = 1

/** The server was asked for something it cannot do: conflicting or unknown arguments. */
internal const val EXIT_USAGE = 2

/**
 * Starts the server: everything that has to have happened before the protocol loop
 * begins.
 *
 * ## What this owns
 *
 * The container, the database, the profiles, the tool configuration and the server
 * itself — and the refusals the server can answer before it serves anything. What it
 * does not own is the protocol channel and the serving loop: the entry point reserves
 * the channel, because both the refusal and the transport speak on it, and it holds
 * the process up until the client leaves.
 *
 * ## Why the refusals come back as values
 *
 * The client is told one thing and the process leaves with another, and both belong
 * to the refusal rather than to the step that found it. So a refusal travels as
 * [ToolServerStartup.Refused] — the code and the message the client sees, and the exit
 * code the process uses — and the entry point is the only thing that acts on it.
 *
 * ## Why the steps are walked rather than written out
 *
 * The order is a contract with the shutdown hook, with the refusals and with the
 * client, and it is written down in [ToolServerStartupOrder]. This function walks that
 * list; it does not repeat it.
 */
internal suspend fun startToolServer(selection: ProfileSelection): ToolServerStartup {
    val context = ToolServerStartupContext(selection)
    for (step in ToolServerStartupOrder.steps) {
        val refusal = step.run(context)
        if (refusal != null) return refusal
    }
    return context.ready()
}

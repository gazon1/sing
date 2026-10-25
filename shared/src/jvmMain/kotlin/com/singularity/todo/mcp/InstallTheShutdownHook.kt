package com.singularity.todo.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import org.koin.core.context.stopKoin

/**
 * The step that closes the server and the container when the process is on its way out.
 *
 * It is the last step of the start-up, and it is last for a reason: it closes the
 * server, so the server has to exist.
 */
internal object InstallTheShutdownHook : ToolServerStartupStep {

    override val title: String = "install the shutdown hook"

    override suspend fun run(context: ToolServerStartupContext): ToolServerStartup.Refused? {
        val server = checkNotNull(context.server) {
            "The order does not build the server before this step."
        }
        install(server)
        return null
    }
}

/**
 * Closes the server and the container when the process is on its way out.
 *
 * A shutdown hook has no coroutine context of its own, and the JVM is on its way out:
 * the hook has to block until the session is closed, because after it returns the JVM
 * tears the process down whatever the session was doing.
 */
@Suppress("NoRunBlocking") // a shutdown hook has to block: the JVM is leaving
private fun install(server: Server) {
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking { server.close() }
            stopKoin()
        },
    )
}

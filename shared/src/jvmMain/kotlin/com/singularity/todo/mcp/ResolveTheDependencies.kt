package com.singularity.todo.mcp

/**
 * The step that resolves the server's dependencies.
 *
 * Resolving the tool list builds every collaborator the server has, and any of them can
 * fail to build — a missing binding, a host that cannot be read, a database that cannot
 * be opened. Left unguarded, such a failure escaped `main`: a stack trace on standard
 * error and an exit, with **nothing on the protocol channel at all**. A client waiting
 * for an answer saw the connection close and no reason, which is the one answer a
 * client cannot act on.
 *
 * So the failure is caught here, the cause goes to the log, and the client is told the
 * one thing that is true: the server could not start.
 */
internal object ResolveTheDependencies : ToolServerStartupStep {

    override val title: String = "resolve the dependencies"

    override suspend fun run(context: ToolServerStartupContext): ToolServerStartup.Refused? {
        val dependencies = resolve() ?: return ToolServerStartup.Refused(
            code = -32000,
            message = "The server could not start: its dependencies are unavailable.",
            exitCode = EXIT_STARTUP,
        )
        context.dependencies = dependencies
        return null
    }

    // The same one-place failure as the rest of the start-up: a dependency that cannot be
    // built is a start-up failure, and the caller gets a JSON-RPC error and a non-zero exit
    // code, not a stack trace.
    @Suppress("TooGenericExceptionCaught") // start-up has to fail in one place, whatever the failure was
    private fun resolve(): ServerDependencies? = try {
        ServerDependencies.fromKoin()
    } catch (e: Throwable) {
        startupLog.e(e) { "The server's dependencies could not be resolved: ${e.message}" }
        null
    }
}

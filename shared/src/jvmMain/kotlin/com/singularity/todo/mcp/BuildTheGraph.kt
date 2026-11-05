package com.singularity.todo.mcp

/**
 * The step that starts the container.
 *
 * The binding set is owned by the process entry point (see [ToolServerKoinStartup]): a
 * library does not start the application's container, and doing it here also made this
 * module the Koin compiler plugin's aggregator. This step only turns the launcher's
 * starter into a start-up outcome.
 *
 * On failure, logs the cause and refuses with `-32000`: the client is told one thing,
 * and the process leaves with the code the refusal carries.
 */
internal object BuildTheGraph : ToolServerStartupStep {
    override val title: String = "build the graph"

    override suspend fun run(context: ToolServerStartupContext): ToolServerStartup.Refused? {
        if (!startTheContainer(context)) {
            return ToolServerStartup.Refused(
                code = -32000,
                message = "The server could not start: its dependencies are unavailable.",
                exitCode = EXIT_STARTUP,
            )
        }
        return null
    }

    // The server's start-up has to fail in one place, whatever the failure was: a missing
    // dependency, a locked store, a broken native library. The caller gets a JSON-RPC error
    // and a non-zero exit code, not a stack trace.
    @Suppress("TooGenericExceptionCaught") // start-up has to fail in one place, whatever the failure was
    private fun startTheContainer(context: ToolServerStartupContext): Boolean =
        try {
            context.koinStartup.start(context.fixedProfileId)
            true
        } catch (e: Throwable) {
            startupLog.e(e) { "Koin initialization failed: ${e.message}" }
            false
        }
}

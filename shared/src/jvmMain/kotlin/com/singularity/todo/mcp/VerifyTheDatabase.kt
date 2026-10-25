package com.singularity.todo.mcp

import kotlinx.coroutines.CancellationException

/**
 * The step that opens the database and asks it a question.
 *
 * Eagerly opens the Room database and runs a single SELECT to surface schema/migration
 * problems at startup instead of mid-session.
 *
 * On failure, logs the cause and returns false, like every other start-up failure.
 */
internal object VerifyTheDatabase : ToolServerStartupStep {

    override val title: String = "verify the database"

    override suspend fun run(context: ToolServerStartupContext): ToolServerStartup.Refused? {
        val dependencies = checkNotNull(context.dependencies) {
            "The order does not resolve the dependencies before this step."
        }
        if (!verify(dependencies)) {
            return ToolServerStartup.Refused(
                code = -32001,
                message = "The server could not open its database.",
                exitCode = EXIT_STARTUP,
            )
        }
        return null
    }

    // The same one-place failure as the start-up: the database either opens or it does not.
    @Suppress("TooGenericExceptionCaught") // the database either opens or it does not
    private suspend fun verify(dependencies: ServerDependencies): Boolean = try {
        dependencies.database.profileDao().count()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        startupLog.e(e) { "Database initialization failed: ${e.message}" }
        false
    }
}

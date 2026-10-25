package com.singularity.todo.mcp

import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.feature.profile.ProfileId
import org.koin.core.context.startKoin

/**
 * The step that starts the container.
 *
 * The binding set is deliberately the SAME one the desktop app uses — a hand-rolled
 * subset here drifted and left `SyncWorkScheduler`/proposal DAOs unbound, which killed
 * `ToolRegistrar` at startup. Profile data isolation is handled post-Koin by
 * [com.singularity.todo.feature.profile.ProfileBootstrapper.run] via the `user_id` scope
 * on each DAO query; the `--profile=NAME` argument is a label, not a directory suffix.
 *
 * On failure, logs the cause and refuses with `-32000`: the client is told one
 * thing, and the process leaves with the code the refusal carries.
 */
internal object BuildTheGraph : ToolServerStartupStep {

    override val title: String = "build the graph"

    override suspend fun run(context: ToolServerStartupContext): ToolServerStartup.Refused? {
        if (!startTheContainer(context.fixedProfileId)) {
            return ToolServerStartup.Refused(
                code = -32000,
                message = "The server could not start: its dependencies are unavailable.",
                exitCode = EXIT_STARTUP,
            )
        }
        return null
    }

    /**
     * Starts Koin with the shared JVM platform module and the shared domain module.
     *
     * A process that was told which profile to serve never opens the store that holds
     * the active profile — the store takes a file lock, and the application is holding
     * it. That is decided by the module list, not by anything the server does later:
     * the profile repository is built without a store, and the tools read their one
     * setting through the narrow port, which answers with the default rather than
     * pulling the application's settings store in with it.
     *
     * The active profile has a file of its own — `profile_state.preferences_pb` — so
     * the settings file this server does open, for the AI configuration, does not
     * say anything about which profile the user is working in.
     */
    // The server's start-up has to fail in one place, whatever the failure was: a missing
    // dependency, a locked store, a broken native library. The caller gets a JSON-RPC error
    // and a non-zero exit code, not a stack trace.
    @Suppress("TooGenericExceptionCaught") // start-up has to fail in one place, whatever the failure was
    private fun startTheContainer(fixedProfileId: String?): Boolean = try {
        startKoin {
            modules(
                listOf(platformModule(), coreLoggingModule()) +
                    domainModule(fixedProfileId?.let(ProfileId::fromString)),
            )
        }
        true
    } catch (e: Throwable) {
        startupLog.e(e) { "Koin initialization failed: ${e.message}" }
        false
    }
}

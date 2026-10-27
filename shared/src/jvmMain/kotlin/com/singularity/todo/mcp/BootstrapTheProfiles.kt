package com.singularity.todo.mcp

import com.singularity.todo.feature.profile.ProfileBootstrapper
import kotlinx.coroutines.CancellationException

/**
 * The step that seeds the default profiles and points the stored one at the label.
 *
 * After Koin is up and the database is reachable, this ensures the default and agent
 * profiles exist and points the store at the agent profile when `--profile=ai-agent` (or
 * any other agent-like flag) was passed. This makes subsequent tool calls write under
 * the AI Agent's UUID instead of Personal's.
 */
internal object BootstrapTheProfiles : ToolServerStartupStep {

    override val title: String = "bootstrap the profiles"

    override suspend fun run(context: ToolServerStartupContext): ToolServerStartup.Refused? {
        val dependencies = checkNotNull(context.dependencies) {
            "The order does not resolve the dependencies before this step."
        }
        bootstrap(dependencies, context.legacyLabel)
        return null
    }
}

/**
 * Seeds default profiles (Personal + AI Agent) and switches the active profile
 * to one matching the host's `--profile=NAME` argument, when reasonable.
 *
 * Mapping rule:
 *  - null / "default" / "personal" → leave whatever DataStore had (usually Personal)
 *  - "ai-agent" / "agent" → seed & activate "AI Agent"
 *  - any other name → don't activate (the user opted into a custom profile that
 *    the bootstrapper is unaware of)
 *
 * If activating AI Agent and the DB already has rows owned by the un-scoped
 * local userId (from earlier dogfooding runs before profiles existed), retro-migrate
 * them under the AI Agent's scoped userId so they become visible after switching
 * in the UI. Safe to run repeatedly: only touches rows owned by `user_id = LOCAL_UID`.
 */
// The profile bootstrap is best-effort: a failure here leaves the server on the
// personal/default profile, which is a working server.
@Suppress("TooGenericExceptionCaught") // best-effort: the server works on the default profile
private suspend fun bootstrap(dependencies: ServerDependencies, profileCliArg: String?) {
    try {
        val bootstrapper: ProfileBootstrapper = dependencies.profileBootstrapper
        val activateName = profileNameToActivate(profileCliArg)

        val result = bootstrapper.run(
            seedExtras = listOf(ProfileBootstrapper.SeedProfile.AI_AGENT),
            activateName = activateName,
        )
        // --profile-id needs no row migration: the agent profile writes under its own
        // scoped id, and migrating live rows here would hide them from the app.
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        startupLog.e(e) { "Profile bootstrap failed: ${e.message}" }
        // Non-fatal: the rest of the server can still operate against the
        // personal/default profile.
    }
}

/**
 * The profile the legacy label activates, or null when the label activates none.
 *
 * The label is a name whose value only one label ever had. `ai-agent` — and its
 * spelling `agent` — activates the "AI Agent" profile; `default` and `personal`
 * leave the stored one alone; every other label is ignored, exactly as it was
 * before this change, because a manifest in the wild passes it and a silently
 * reinterpreted label serves the wrong profile.
 *
 * The mapping is a value rather than a branch inside the bootstrap, so that the
 * legacy meaning can be checked without a database, a container and a process.
 */
internal fun profileNameToActivate(legacyLabel: String?): String? = when (legacyLabel?.lowercase()) {
    "ai-agent", "agent" -> "AI Agent"

    // "default", "personal", and every label nobody knows: the stored profile stays.
    else -> null
}

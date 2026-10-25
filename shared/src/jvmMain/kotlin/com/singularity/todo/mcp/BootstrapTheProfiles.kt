package com.singularity.todo.mcp

import com.singularity.todo.core.process.Subprocess
import com.singularity.todo.feature.profile.ProfileBootstrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File

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

        // The un-scoped local userId, read from the session BEFORE the switch.
        //
        // It must not come from `ProfileAwareCurrentUser.current`: that is a cached
        // StateFlow that a background collector rewrites, and `bootstrapper.run` is
        // suspend, so it gives that collector a chance to run. Once it has, `current`
        // is already the agent-scoped id, and the migration below would build
        // newUserId = "{agent}/{agent}/{user}" — an id no profile matches, leaving
        // every migrated row unreachable. Whether that happened depended on dispatcher
        // timing. The session is not touched by the switch, so this read is exact.
        val localUserId: String = dependencies.currentUser
            .liveLocalUserId
            .first()
            .value

        val result = bootstrapper.run(
            seedExtras = listOf(ProfileBootstrapper.SeedProfile.AI_AGENT),
            activateName = activateName,
        )
        result.activated?.let { activated ->
            // Retro-migrate rows from the unscoped local user id.
            val agentId = activated.value
            retromigrateRowsToAgentScope(
                localUserId = localUserId,
                newUserId = "$agentId/$localUserId",
            )
        }
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

/**
 * Move rows whose user_id equals the un-scoped local user into the AI Agent
 * scoped user_id (`"{AI Agent id}/{local user id}"`). Idempotent: no-op if
 * no rows match or if the sqlite3 CLI is unavailable.
 *
 * Done via shell `sqlite3` because this is a one-off bootstrap migration
 * touching DB rows that pre-date the profile model.
 *
 * The shell-out goes through [Subprocess] rather than a `ProcessBuilder` of its own:
 * spawning a process is confined to that one file so that every subprocess in the
 * repository is drained and closed the way that does not deliver SIGPIPE to the child.
 */
private fun retromigrateRowsToAgentScope(localUserId: String, newUserId: String) {
    // Always use the default DB path — Desktop, Android, and MCP all share it now.
    val dbPath = System.getProperty("user.home") + "/.singularity-todo/singularity-todo.db"
    val file = File(dbPath)
    if (!file.exists()) return
    val sql = buildString {
        for (table in listOf("tasks", "notes", "projects", "tags")) {
            append(
                "UPDATE $table SET user_id='",
            ).append(newUserId).append("' WHERE user_id='").append(localUserId).append("';")
        }
    }
    // sqlite3 prints affected row counts to stdout when group statements use
    // "--changes" (we'll skip that for portability). Better: count before/after by
    // another route, but for the bootstrap we just trust the operation succeeded if
    // the exit was 0 — which is exactly what a non-null result means here.
    val result = Subprocess.runCapturing(listOf("sqlite3", dbPath), stdin = sql)
    if (result != null) {
        startupLog.i { "Retro-migrated rows to AI Agent scope ($dbPath)" }
    } else {
        startupLog.w { "Retro-migration did not run: sqlite3 is unavailable or the update failed" }
    }
}

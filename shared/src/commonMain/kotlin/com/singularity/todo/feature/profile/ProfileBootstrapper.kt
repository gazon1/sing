package com.singularity.todo.feature.profile

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import kotlinx.coroutines.flow.first

/**
 * First-run / per-launch profile setup.
 *
 * On Android/Desktop the user creates profiles through the ProfileSwitcherScreen.
 * On the CLI/MCP host (`java -jar :mcp-server.jar --profile=...`) there is no
 * UI, so this bootstrapper is responsible for:
 *
 * 1. Seeding the canonical 'Personal' default profile (idempotent — if the table
 *    already has a default profile, this is a no-op).
 * 2. Seeding any extra named profiles requested by the host (e.g. 'AI Agent' on
 *    the MCP `ai-agent` profile).
 * 3. Activating the profile that matches the host's `--profile=NAME` argument.
 *
 * Why this lives in `commonMain` and not in `:mcp-server`:
 * - Android and Desktop can also benefit from seeded defaults on first launch
 *   (none of the call sites are MCP-specific).
 *
 * Idempotency: safe to call on every app start.
 */
class ProfileBootstrapper(
    private val repository: ProfileRepository,
    private val logger: Logger = Logger.withTag("ProfileBootstrapper"),
) {

    /**
     * Run seed + activate in one shot.
     *
     * @param seedExtras extra profiles to ensure exist (name, emoji, colorIdx).
     *                   Each is only created if no profile with that name exists.
     * @param activateName set the active profile to the one with this name, if
     *                     it exists after seeding. Pass null to leave whatever
     *                     DataStore already had.
     * @return [ProfileBootstrapResult] describing what was created and which profile
     *         was activated, if any. Callers (e.g. MCP server) can read the activated
     *         id directly without a separate `.first()` call — avoiding a potential
     *         race between bootstrap and subsequent tool calls.
     *
     * @throws Throwable the exception from [ProfileRepository.switchTo] when the profile
     *         was found but could not be activated. Not a best-effort step: the returned
     *         id drives a row migration in the MCP host, so reporting an id for a switch
     *         that failed would move data into a scope the server is not running under.
     *         A name that was not found after seeding is not an error and reports `null`.
     */
    suspend fun run(
        seedExtras: List<SeedProfile> = emptyList(),
        activateName: String? = null,
    ): ProfileBootstrapResult {
        val alreadyExisted = repository.observeAll().first().associateBy { it.name }
        val seedTuples = seedExtras.map { Triple(it.name, it.emoji, it.colorIdx) }
        repository.ensureDefaults(extraProfiles = seedTuples)
        val profiles = repository.observeAll().first().associateBy { it.name }
        val activated = if (activateName != null) {
            profiles[activateName]?.also { profile ->
                // Unwrapped, and it has to be. The returned id is not a status the caller
                // logs — mcp/Main.kt:164 reads it and, on a non-null id, runs
                // retromigrateRowsToAgentScope, which moves every row owned by the
                // unscoped local user id into a scope keyed on this profile. A switch
                // that failed but still returned an id therefore migrated the rows into a
                // namespace the server is not running under. Returning null instead would
                // conflate "nothing asked for a switch" with "the switch failed", which
                // are different situations and which the caller cannot otherwise tell
                // apart.
                //
                // Throwing is safe: the only production caller already wraps the whole
                // bootstrap in a try/catch that logs and continues against the default
                // profile (mcp/Main.kt:175-179). So the throw lands in a handler that
                // exists, says why, and does not reach the migration.
                repository.switchTo(profile.id).getOrThrow()
                logger.i { "ProfileBootstrapper: activated profile (${profile.id.value})" }
            } ?: run {
                logger.w { "ProfileBootstrapper: '$activateName' not found after seed" }
                null
            }
        } else {
            null
        }
        return ProfileBootstrapResult(
            created = profiles.keys - alreadyExisted.keys,
            activated = activated?.id,
        )
    }

    /**
     * Immutable result carrier returned by [ProfileBootstrapper.run].
     *
     * @property created Set of profile names that were created during this bootstrap run
     *                  (i.e. did not exist before).
     * @property activated The activated [ProfileId] if a profile was activated, or null
     *                    if [run] was called with `activateName = null`.
     */
    data class ProfileBootstrapResult(val created: Set<String>, val activated: ProfileId?)

    /** Compact carrier for the (name, emoji, colorIdx) tuple. */
    data class SeedProfile(val name: String, val emoji: String, val colorIdx: Int) {
        companion object {
            /** Default 'AI Agent' profile: 🤖 on green. */
            val AI_AGENT = SeedProfile(name = "AI Agent", emoji = "🤖", colorIdx = 1)
        }
    }
}

package com.singularity.todo.feature.profile

import co.touchlab.kermit.Logger
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
     */
    suspend fun run(seedExtras: List<SeedProfile> = emptyList(), activateName: String? = null) {
        val seedTuples = seedExtras.map { Triple(it.name, it.emoji, it.colorIdx) }
        repository.ensureDefaults(extraProfiles = seedTuples)
        if (activateName != null) {
            // Look up the id by name. first() suspends until the Flow emits at least once.
            val match = repository.all().first()
                .firstOrNull { it.name == activateName }
            if (match != null) {
                repository.switchTo(match.id)
                logger.i { "ProfileBootstrapper: activated '$activateName' (${match.id.value})" }
            } else {
                logger.w { "ProfileBootstrapper: '$activateName' not found after seed" }
            }
        }
    }

    /** Compact carrier for the (name, emoji, colorIdx) tuple. */
    data class SeedProfile(val name: String, val emoji: String, val colorIdx: Int) {
        companion object {
            /** Default 'AI Agent' profile: 🤖 on green. */
            val AI_AGENT = SeedProfile(name = "AI Agent", emoji = "🤖", colorIdx = 1)
        }
    }
}

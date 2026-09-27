package com.singularity.todo.feature.profile

import kotlin.time.Instant

/**
 * A named, isolated namespace for a user's data.
 *
 * Each profile owns its own tasks, notes, projects, tags, and LLM usage records.
 * The active profile is stored in DataStore (not Room) so it survives DB resets.
 *
 * @param id Stable ULID — never changes after creation
 * @param name User-facing name, e.g. "Personal" or "AI Agent"
 * @param emoji Single emoji, e.g. "🏠" or "🤖"
 * @param colorIdx Index into a preset color palette (0-7), or -1 for default
 * @param isDefault True for the automatically-created initial profile
 * @param createdAt Instant of creation
 * @param updatedAt Instant of last rename / colour change
 */
data class Profile(
    val id: ProfileId,
    val name: String,
    val emoji: String,
    val colorIdx: Int,
    val isDefault: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /** Creates the default "Personal" profile shown on first launch. */
        fun createDefault(clock: kotlin.time.Clock): Profile {
            val now = clock.now()
            return Profile(
                id = ProfileId.default,
                name = "Personal",
                emoji = "🏠",
                colorIdx = 0,
                isDefault = true,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}

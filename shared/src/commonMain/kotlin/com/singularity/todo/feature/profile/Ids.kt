package com.singularity.todo.feature.profile

import com.singularity.todo.core.ids.nextId

/**
 * Stable, unique identifier for a user profile.
 * ULID — sortable by creation time, 26-char Crockford base32.
 */
@JvmInline
value class ProfileId(val value: String) {
    companion object {
        fun generate() = ProfileId(nextId())
        fun fromString(value: String) = ProfileId(value)
        val default = ProfileId("default")
    }
}

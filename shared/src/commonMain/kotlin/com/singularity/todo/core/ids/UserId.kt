package com.singularity.todo.core.ids

/**
 * User identity, used across all features.
 * Moved from feature/tasks/Ids.kt to avoid cross-feature import cycles.
 */
@JvmInline
value class UserId(val value: String) {
    companion object {
        fun generate() = UserId(nextId())
        fun fromString(value: String) = UserId(value)
        val anonymous = UserId("anonymous")
    }
}

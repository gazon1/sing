package com.singularity.todo.feature.tags.domain

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.text.visibleLength

/**
 * Pure domain logic for tag validation.
 *
 * No dependencies — fully testable without mocks or Compose.
 */
object TagDomain {

    /**
     * Maximum tag name length, counted in visible characters.
     *
     * Not `String.length` — see [visibleLength]. An emoji counts once here, which
     * is what a person reading "max 100 characters" expects.
     */
    const val MAX_NAME_LENGTH: Int = 100

    /**
     * Validates tag creation input.
     * @return null if valid, error message otherwise.
     */
    fun validateName(name: String): AppError.Validation? = when {
        name.isBlank() -> AppError.Validation("Tag name cannot be blank", code = "tag.name.blank")

        name.visibleLength() > MAX_NAME_LENGTH -> AppError.Validation(
            "Tag name too long (max $MAX_NAME_LENGTH characters)",
            code = "tag.name.too_long",
        )

        else -> null
    }

    /**
     * Validates tag color (ARGB int).
     */
    fun validateColor(color: Int): AppError.Validation? = when {
        color == 0 -> AppError.Validation("Color cannot be transparent (ARGB=0)", code = "tag.color.transparent")
        else -> null
    }

    /**
     * Runs every tag write-rule, returning the first violation.
     *
     * Create and update must agree on what a valid tag is — otherwise a value
     * the create path rejects becomes reachable later through a rename. Both
     * use cases call this, so the rule lives in exactly one place.
     *
     * @return null if valid, the first error otherwise.
     */
    fun validate(name: String, color: Int): AppError.Validation? = validateName(name) ?: validateColor(color)
}

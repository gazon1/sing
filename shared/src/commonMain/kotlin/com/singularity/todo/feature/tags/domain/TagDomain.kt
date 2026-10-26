package com.singularity.todo.feature.tags.domain

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.text.visibleLength
import com.singularity.todo.feature.tags.Tag

/**
 * Pure domain logic for tag validation and lookup.
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
     * The reason a blank name is refused, stated once.
     *
     * The picker shows this next to its create action, so the rule the domain
     * enforces is the rule the user reads — a second wording would be a second
     * rule.
     */
    const val BLANK_NAME_MESSAGE: String = "Tag name cannot be blank"

    /**
     * The tag whose name matches [name], or null when there is none.
     *
     * Names are compared after trimming and case-insensitively: "Invoice" and
     * "invoice" are one label to a person, and nothing else in the app says so
     * — there is no unique index on `tags.name`, no rule in this object before
     * this one, and no check in `TagsRepositoryImpl.create`, which creates
     * whatever it is handed. That is why the rule lives here: the tags screen
     * and the picker have to share it rather than each grow their own copy.
     */
    fun findByName(tags: List<Tag>, name: String): Tag? {
        val wanted = name.trim()
        if (wanted.isEmpty()) return null
        return tags.firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }
    }

    /**
     * Validates tag creation input.
     * @return null if valid, error message otherwise.
     */
    fun validateName(name: String): AppError.Validation? = when {
        name.isBlank() -> AppError.Validation(BLANK_NAME_MESSAGE, code = "tag.name.blank")

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

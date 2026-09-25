package com.singularity.todo.feature.tags.domain

import com.singularity.todo.core.error.AppError

/**
 * Pure domain logic for tag validation.
 *
 * No dependencies — fully testable without mocks or Compose.
 */
object TagDomain {

    /**
     * Validates tag creation input.
     * @return null if valid, error message otherwise.
     */
    fun validateName(name: String): AppError.Validation? = when {
        name.isBlank() -> AppError.Validation("Tag name cannot be blank")
        name.length > 100 -> AppError.Validation("Tag name too long (max 100 characters)")
        else -> null
    }

    /**
     * Validates tag color (ARGB int).
     */
    fun validateColor(color: Int): AppError.Validation? = when {
        color == 0 -> AppError.Validation("Color cannot be transparent (ARGB=0)")
        else -> null
    }
}

package com.singularity.todo.core.validation

import com.singularity.todo.core.error.AppError

/**
 * Validates that [input] is not blank (empty or whitespace-only).
 * @param field field name for the error message.
 */
fun requireNotBlank(input: String, field: String): String =
    input.takeIf { it.isNotBlank() }
        ?: throw AppError.Validation("$field must not be blank")

/**
 * Validates that [input]'s length does not exceed [maxLength].
 * @param field field name for the error message.
 */
fun requireMaxLength(input: String, maxLength: Int, field: String): String =
    input.takeIf { it.length <= maxLength }
        ?: throw AppError.Validation("$field must be at most $maxLength characters")

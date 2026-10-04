package com.singularity.todo.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Android is the only platform where a testTag is mapped to a UIAutomator
 * `resource-id`, and the property that does the mapping does not exist in
 * common Compose. See the commonMain declaration for the full rationale.
 */
actual fun Modifier.exposeTestTagsAsResourceId(): Modifier = semantics { testTagsAsResourceId = true }

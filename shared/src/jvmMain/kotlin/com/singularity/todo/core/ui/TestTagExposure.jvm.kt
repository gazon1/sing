package com.singularity.todo.core.ui

import androidx.compose.ui.Modifier

/**
 * No-op: the desktop Compose tests read the semantics tree directly, where
 * there is no `resource-id` mapping to enable. See the commonMain declaration
 * for the full rationale.
 */
actual fun Modifier.exposeTestTagsAsResourceId(): Modifier = this

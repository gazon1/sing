package com.singularity.todo.core.ui

import androidx.compose.ui.Modifier

/** Desktop: no resource-id layer — testTags are already addressable. */
actual fun Modifier.mapTestTagsAsResourceIds(): Modifier = this

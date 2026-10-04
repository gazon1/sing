package com.singularity.todo.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/** Android: enable the mapping — see [mapTestTagsAsResourceIds]. */
actual fun Modifier.mapTestTagsAsResourceIds(): Modifier =
    semantics { testTagsAsResourceId = true }

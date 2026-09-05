package com.singularity.todo.feature.genui.render

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.singularity.todo.core.ui.TestTags

/**
 * Marks a GenUI component so it is discoverable by UI Automator ([android_ui_resolve])
 * and by Compose tests ([composeTestRule.onNodeWithTag]).
 *
 * Usage:
 * ```
 * modifier = Modifier.genuiTag("btn_apply", "Button: Apply")
 * ```
 *
 * On Android the [testTag] is visible to UI Automator via `android_ui_resolve`.
 * For full UI Automator support also ensure accessibility labels are set.
 */
fun Modifier.genuiTag(name: String, description: String): Modifier =
    this
        .testTag(TestTags.genUi(name))
        .semantics { contentDescription = description }

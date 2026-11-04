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
fun Modifier.genuiTag(
    name: String,
    description: String,
): Modifier =
    this
        .testTag(TestTags.genUi(name))
        .semantics { contentDescription = description }

/**
 * Marks a GenUI component with a tag and no description of its own.
 *
 * For a node that merges its descendants — a Surface, a Card, a sheet. A content description
 * on such a node replaces the descriptions of everything below it: the body and the buttons
 * leave the accessibility tree, and a flow that selects them by text stops finding them. The
 * merged node keeps the descriptions of its children instead.
 */
fun Modifier.genuiTag(name: String): Modifier = testTag(TestTags.genUi(name))

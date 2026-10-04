package com.singularity.todo.core.ui

import androidx.compose.ui.Modifier

/**
 * Re-asserts Compose's testTag → platform resource-id mapping for the subtree
 * it decorates.
 *
 * Needed inside popup windows (DropdownMenu, ModalBottomSheet): each popup is
 * its own platform window with its own semantics root, so the app-root
 * `testTagsAsResourceId = true` from the shell never reaches it and every
 * testTag declared there is invisible to Android UI automation.
 *
 * Android actual enables the mapping; the desktop actual is a no-op because
 * desktop automation reads testTags directly from the semantics tree.
 */
expect fun Modifier.mapTestTagsAsResourceIds(): Modifier

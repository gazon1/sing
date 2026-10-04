package com.singularity.todo.core.ui

import androidx.compose.ui.Modifier

/**
 * Makes `Modifier.testTag` values inside this subtree visible to Android UI
 * automation as `resource-id`s.
 *
 * ## Why this exists
 *
 * Android only maps a Compose testTag to a UIAutomator `resource-id` when the
 * semantics property `testTagsAsResourceId` is set. The app root sets it once
 * (`App.kt`), which covers the main window — and nothing else.
 *
 * Any surface that renders into **its own window** does not inherit it:
 * dialogs (`AlertDialog`), modal bottom sheets, and popup/context menus. Inside
 * those, every testTag is silently invisible to Maestro and UIAutomator. The
 * failure mode is nasty, because nothing errors: a flow taps
 * `id: dialog_confirm`, Maestro reports "element not found", and the tag in
 * `TestTags.kt` still looks perfectly correct — it *is* correct, for the
 * desktop Compose tests, which read the semantics tree directly.
 *
 * ## Why a function and not a parameter
 *
 * The alternative is what `MenuBottomSheet` does: take a `modifier` parameter
 * and have each caller pass `Modifier.semantics { testTagsAsResourceId = true }`.
 * That works, and it is what the codebase grew organically. But it puts the
 * burden on every call site, and a forgotten one is invisible until a flow
 * fails on a device. Applying it *inside* the shared surface makes forgetting
 * impossible, which is the whole point.
 *
 * On JVM it is a no-op: the desktop tests read the semantics tree, where
 * `testTagsAsResourceId` has no meaning.
 *
 * Apply it to the **content** of a window-owning surface, or to the tagged node
 * itself — either works, because the property is read from the node's merged
 * semantics.
 */
expect fun Modifier.exposeTestTagsAsResourceId(): Modifier

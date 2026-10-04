package com.singularity.todo.core.ui

import androidx.compose.ui.Modifier

/**
 * Re-asserts Compose's testTag → platform resource-id mapping for the subtree
 * it decorates, so a `Modifier.testTag` inside a **window-owning surface** is
 * visible to Android UI automation as a `resource-id`.
 *
 * ## Why this exists
 *
 * Android only maps a Compose testTag to a UIAutomator `resource-id` when the
 * semantics property `testTagsAsResourceId` is set. The app root sets it once,
 * which covers the main window — and nothing else.
 *
 * Any surface that renders into **its own window** does not inherit it:
 * dialogs (`AlertDialog`), modal bottom sheets, popup and context menus, and
 * `Popup`-hosted snackbars. Inside those, every testTag is silently invisible
 * to Maestro and UIAutomator. The failure mode is nasty, because nothing errors:
 * a flow taps `id: dialog_confirm`, Maestro reports "element not found", and the
 * tag in `TestTags.kt` still looks perfectly correct — it *is* correct, for the
 * desktop Compose tests, which read the semantics tree directly.
 *
 * ## Why a function and not a parameter
 *
 * The alternative is to take a `modifier` parameter on each surface and have
 * every caller pass the semantics property. That works, and it is what the
 * codebase grew organically into. But it puts the burden on every call site,
 * and a forgotten one is invisible until a flow fails on a device. Applying it
 * *inside* the shared surface makes forgetting impossible, which is the whole
 * point.
 *
 * ## Where to apply it
 *
 * Apply it to the **content** of a window-owning surface, or to the tagged node
 * itself — either works, because the property is read from the node's merged
 * semantics. A surface that carries no tagged control needs none yet; that
 * changes the moment a sheet puts one inside.
 *
 * The Android actual enables the mapping; the desktop actual is a no-op because
 * desktop automation reads testTags straight from the semantics tree.
 *
 * Enforced by `UiAutomationSelectorTest` — a tagged control inside a
 * window-owning surface without this call fails the build.
 */
expect fun Modifier.mapTestTagsAsResourceIds(): Modifier

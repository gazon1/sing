package com.singularity.todo.core.ui.onboarding

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Remembers where highlighted elements are, so the overlay can point at them.
 *
 * ## Why a registry and not a parameter
 *
 * The overlay draws over the whole app while the elements it points at are laid out
 * somewhere deep inside a screen. Threading a coordinate down two or three composable
 * layers to the element, and back up again, couples the screen's internals to the
 * onboarding feature. Registering an anchor is one modifier at the element and nothing
 * else, and it survives the element moving between screens.
 *
 * The bounds are in **root** coordinates because that is the frame the overlay draws in.
 * `boundsInRoot` rather than `positionInRoot`: the latter gives only the top-left corner
 * and leaves the size to be measured separately, which is how a hole ends up one notch
 * too small and a circle that does not quite cover its button.
 */
@Stable
class SpotlightAnchorRegistry {

    private val anchors = mutableStateMapOf<SpotlightAnchorId, Rect>()

    /** Current root-space bounds of [id], or null while it has not been laid out. */
    operator fun get(id: SpotlightAnchorId): Rect? = anchors[id]

    internal fun register(id: SpotlightAnchorId, bounds: Rect) {
        anchors[id] = bounds
    }

    internal fun unregister(id: SpotlightAnchorId) {
        anchors.remove(id)
    }
}

/**
 * Marks this composable as the target of the [SpotlightStep] anchored to [id].
 *
 * Apply it to the element itself, not to a parent: the overlay cuts a hole around
 * whatever rectangle is registered, so a parent would highlight everything the child
 * happens to contain.
 *
 * Re-registering on every layout pass is intentional. Bounds change when the window
 * resizes, when the soft keyboard opens, and when the list above the element scrolls —
 * a hole computed once and cached would drift off its target in all three cases, which
 * reads as the tutorial pointing at the wrong button.
 */
fun Modifier.spotlightAnchor(
    id: SpotlightAnchorId,
    registry: SpotlightAnchorRegistry,
): Modifier = this.onGloballyPositioned { registry.register(id, it.boundsInRoot()) }

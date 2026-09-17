package com.singularity.todo.feature.agenda.presentation.components

/**
 * Abstraction over the drag-and-drop reorder library.
 *
 * Both the platform library (sh.calvin on Android/JVM) and the custom
 * `pointerInput` implementation expose the same callbacks through this interface.
 * This allows the caller to configure behavior (haptic, auto-scroll) without
 * caring which underlying implementation is active.
 *
 * @param T The type of items being reordered.
 */
interface ReorderableConfig<T> {
    /**
     * Called when the user starts dragging [item].
     *
     * Use for: haptic feedback, setting dragging state.
     */
    fun onDragStarted(index: Int)

    /**
     * Called when the dragged item crosses a boundary — i.e. the target index
     * changed from [fromIndex] to [toIndex].
     *
     * Use for: haptic feedback on crossing, updating the drag preview position.
     */
    fun onBoundaryCross(fromIndex: Int, toIndex: Int)

    /**
     * Called on each animation frame while the user is dragging near a list edge.
     *
     * @param scrollDirection Direction: negative = scroll up, positive = scroll down.
     * @param speed Speed multiplier in range `[0, 1]`. 0 = at the far edge, 1 = at threshold.
     * @param scrollDeltaUs The accumulated scroll delta in microseconds since last call.
     *        Implementations should call `animateScrollBy` with this value to achieve
     *        smooth deceleration.
     */
    fun onAutoScroll(scrollDirection: Float, speed: Float, scrollDeltaUs: Long)

    /**
     * Called when the drag gesture ends.
     *
     * Use for: resetting drag state, cleanup.
     */
    fun onDragEnded()
}

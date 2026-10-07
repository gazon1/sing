package com.singularity.todo.core.ui.onboarding

import androidx.compose.ui.geometry.Rect

/**
 * The cut-out the scrim leaves around the target.
 *
 * Deliberately a plain value over a [Rect] plus a radius, **not** a
 * `androidx.compose.ui.graphics.Path`. A `Path` is backed by the graphics stack, so
 * constructing one outside a running composition needs Skia loaded — which means the
 * interesting part of this feature (where the hole goes, and how it moves) could not be
 * unit-tested on the JVM at all. The `Path` is built in the composable, from these
 * numbers; everything testable lives here.
 */
data class SpotlightHole(val bounds: Rect, val cornerRadius: Float) {
    /** Grows the hole on every side, keeping the radius. */
    fun expandedBy(padding: Float): SpotlightHole =
        copy(bounds = bounds.inflate(padding), cornerRadius = cornerRadius + padding)
}

/**
 * The hole for [bounds] under [shape].
 *
 * A circle is inscribed in the *larger* square centred on the bounds, so a wide target
 * gets a circle that actually contains it rather than one clipped to its height. Radius
 * [Rounded.radiusFraction] is applied to the smaller side and then clamped: an
 * unclamped fraction of half the smaller side exceeds what Compose's rounded-rectangle
 * corner accepts and renders as a different shape than the geometry says.
 */
fun spotlightHole(bounds: Rect, shape: SpotlightShape): SpotlightHole = when (shape) {
    is SpotlightShape.Circle -> {
        val side = maxOf(bounds.width, bounds.height)
        val inset = (side - bounds.width) / 2f
        val centred = Rect(
            left = bounds.left - inset,
            top = bounds.top - (side - bounds.height) / 2f,
            right = bounds.right + inset,
            bottom = bounds.bottom + (side - bounds.height) / 2f,
        )
        SpotlightHole(centred, cornerRadius = side / 2f)
    }

    is SpotlightShape.Rounded -> {
        val smaller = minOf(bounds.width, bounds.height)
        SpotlightHole(
            bounds = bounds,
            cornerRadius = (smaller * shape.radiusFraction).coerceAtMost(smaller / 2f),
        )
    }
}

/**
 * Linear interpolation between two holes.
 *
 * [from] is the hole as it was, [to] the hole as it should be, [fraction] in `0..1`.
 *
 * The radius is clamped at zero because interpolating from a collapsed hole (the state
 * used to open and close the overlay) would otherwise produce a negative radius partway
 * through, and a negative corner radius does not render as "sharp" — it renders as
 * whatever the backend does with an invalid rect, which is not something to depend on.
 */
fun lerpHole(from: SpotlightHole, to: SpotlightHole, fraction: Float): SpotlightHole {
    val t = fraction.coerceIn(0f, 1f)
    return SpotlightHole(
        bounds = lerpRect(from.bounds, to.bounds, t),
        cornerRadius = lerp(from.cornerRadius, to.cornerRadius, t).coerceAtLeast(0f),
    )
}

private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

private fun lerpRect(from: Rect, to: Rect, t: Float): Rect = Rect(
    left = lerp(from.left, to.left, t),
    top = lerp(from.top, to.top, t),
    right = lerp(from.right, to.right, t),
    bottom = lerp(from.bottom, to.bottom, t),
)

/**
 * The collapsed hole the overlay animates out of and back into.
 *
 * Same centre as [hole], zero size, so the scrim appears to close over the target rather
 * than sliding in from a corner. Sized at [CLOSED_SIZE] rather than exactly zero because
 * a zero-size `Path` has no fillable area and the scrim would blink instead of fading.
 */
fun closedHole(hole: SpotlightHole): SpotlightHole {
    val cx = hole.bounds.center.x
    val cy = hole.bounds.center.y
    return SpotlightHole(
        bounds = Rect(
            left = cx - CLOSED_SIZE / 2f,
            top = cy - CLOSED_SIZE / 2f,
            right = cx + CLOSED_SIZE / 2f,
            bottom = cy + CLOSED_SIZE / 2f,
        ),
        cornerRadius = CLOSED_SIZE / 2f,
    )
}

private const val CLOSED_SIZE = 1f

/** Padding between the target's bounds and the edge of the hole, in pixels. */
const val SPOTLIGHT_HOLE_PADDING: Float = 8f

/**
 * Where the card sits relative to the hole, when there is room.
 *
 * [Above] and [Below] are hints rather than guarantees: [resolveCardPlacement] flips or
 * pins them when the space runs out, which is the normal case on a phone.
 */
enum class SpotlightCardPlacement { Above, Below }

/**
 * Resolves where the card can actually go.
 *
 * Order matters: try the preferred side first, flip to the other if it does not fit, and
 * pin to the bottom edge if neither does. The last case is common rather than
 * exceptional — a target near the middle of a small screen has no clear space either
 * side — so it gets a deliberate answer instead of a card drawn off-screen.
 */
fun resolveCardPlacement(
    preferred: SpotlightCardPlacement,
    holeTop: Float,
    holeBottom: Float,
    cardHeight: Float,
    availableHeight: Float,
    minGap: Float,
): SpotlightCardPlacement {
    val aboveFits = holeTop - minGap - cardHeight >= 0f
    val belowFits = holeBottom + minGap + cardHeight <= availableHeight
    return when {
        preferred == SpotlightCardPlacement.Above && aboveFits -> SpotlightCardPlacement.Above
        preferred == SpotlightCardPlacement.Below && belowFits -> SpotlightCardPlacement.Below
        belowFits -> SpotlightCardPlacement.Below
        aboveFits -> SpotlightCardPlacement.Above
        else -> SpotlightCardPlacement.Below
    }
}

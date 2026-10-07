package com.singularity.todo.feature.attachments.viewer

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pinch-zoom is the first gesture surface in this project and the precedent is a single
 * unrelated list, so the arithmetic is pinned here rather than left to manual checking
 * on two platforms. The failure mode being guarded against is an image that grows
 * without bound until it cannot be panned back into view.
 */
@Tag("fast")
class ZoomTransformTest {

    @Test
    fun `the identity transform is one times scale and no offset`() {
        val t = ZoomTransform()
        assertEquals(1f, t.scale)
        assertEquals(Offset.Zero, t.offset)
    }

    @Test
    fun `pinching out scales up`() {
        assertEquals(2f, ZoomTransform().zoomBy(2f, Offset.Zero).scale)
    }

    @Test
    fun `pinching in below one times clamps to one`() {
        // Shrinking past the original size is not meaningful for a Fit image; letting
        // it go leaves the user holding a smaller-than-real picture with no way to tell.
        assertEquals(1f, ZoomTransform(scale = 1f).zoomBy(0.5f, Offset.Zero).scale)
        assertEquals(1f, ZoomTransform(scale = 2f).zoomBy(0.1f, Offset.Zero).scale)
    }

    @Test
    fun `scale is clamped at the maximum`() {
        val t = ZoomTransform().zoomBy(100f, Offset.Zero)
        assertEquals(ZoomTransform.MAX_SCALE, t.scale)
    }

    @Test
    fun `repeated pinching still cannot exceed the maximum`() {
        var t = ZoomTransform()
        repeat(20) { t = t.zoomBy(2f, Offset.Zero) }
        assertEquals(ZoomTransform.MAX_SCALE, t.scale)
    }

    @Test
    fun `a zero-scale gesture does not change the scale`() {
        // A pinch reported as exactly 1.0 happens on every frame of a two-finger drag
        // that is not really pinching. Treating it as a scale change compounds.
        assertEquals(1f, ZoomTransform().zoomBy(1f, Offset(10f, 10f)).scale)
    }

    @Test
    fun `zooming about a point keeps that point under the fingers`() {
        val before = ZoomTransform(scale = 1.5f, offset = Offset(20f, -10f))
        val centroid = Offset(100f, 60f)

        // The image-space point that currently sits under the centroid. Deriving it
        // from the transform is the whole assertion — asserting a hard-coded number
        // here would test arithmetic I had already decided on rather than the property.
        val underFingers = Offset(
            x = (centroid.x - before.offset.x) / before.scale,
            y = (centroid.y - before.offset.y) / before.scale,
        )

        val after = before.zoomBy(2f, centroid)
        val mapped = Offset(
            x = underFingers.x * after.scale + after.offset.x,
            y = underFingers.y * after.scale + after.offset.y,
        )

        assertEquals(centroid.x, mapped.x, 0.01f)
        assertEquals(centroid.y, mapped.y, 0.01f)
    }

    @Test
    fun `a zoom about the origin does not move the origin`() {
        val after = ZoomTransform().zoomBy(2f, Offset.Zero)
        assertEquals(Offset.Zero, after.offset)
        assertEquals(2f, after.scale)
    }

    @Test
    fun `pinching twice keeps the point under the fingers on the second pinch too`() {
        // The regression this pins: a formula that multiplies by the new scale instead
        // of by the ratio is correct only while scale == 1. The first pinch out of a
        // reset viewer therefore looked right, and every pinch after it slid the image
        // away from the fingers — a bug that reads as "the gesture is just imprecise",
        // which is why it survived manual checking on one device.
        var t = ZoomTransform()
        repeat(3) {
            val centroid = Offset(120f, 80f)
            val underFingers = Offset(
                x = (centroid.x - t.offset.x) / t.scale,
                y = (centroid.y - t.offset.y) / t.scale,
            )
            t = t.zoomBy(1.4f, centroid)
            val mapped = Offset(
                x = underFingers.x * t.scale + t.offset.x,
                y = underFingers.y * t.scale + t.offset.y,
            )
            assertEquals(
                centroid.x,
                mapped.x,
                0.01f,
                "pinch ${it + 1} drifted in x",
            )
            assertEquals(
                centroid.y,
                mapped.y,
                0.01f,
                "pinch ${it + 1} drifted in y",
            )
        }
    }

    @Test
    fun `pinching in after zooming out is still consistent`() {
        // The other direction: shrinking must keep the focal point too, not just
        // growing.
        val start = ZoomTransform(scale = 4f, offset = Offset(-30f, 12f))
        val centroid = Offset(60f, 60f)
        val underFingers = Offset(
            x = (centroid.x - start.offset.x) / start.scale,
            y = (centroid.y - start.offset.y) / start.scale,
        )
        val after = start.zoomBy(0.5f, centroid)
        val mapped = Offset(
            x = underFingers.x * after.scale + after.offset.x,
            y = underFingers.y * after.scale + after.offset.y,
        )
        assertEquals(centroid.x, mapped.x, 0.01f)
        assertEquals(centroid.y, mapped.y, 0.01f)
    }

    @Test
    fun `panning adds to the offset`() {
        val t = ZoomTransform().panBy(10f, -5f)
        assertEquals(Offset(10f, -5f), t.offset)
    }

    @Test
    fun `reset returns to the identity`() {
        val t = ZoomTransform(scale = 3f, offset = Offset(40f, 20f)).reset()
        assertEquals(1f, t.scale)
        assertEquals(Offset.Zero, t.offset)
    }

    @Test
    fun `normalizing at one times drops a stray offset`() {
        // Zoomed all the way out, any residual offset is drift: the image is not centred
        // and there is no gesture left to correct it.
        val t = ZoomTransform(scale = 1f, offset = Offset(37f, 12f)).normalized()
        assertEquals(Offset.Zero, t.offset)
    }

    @Test
    fun `normalizing above one times keeps the offset`() {
        val t = ZoomTransform(scale = 2f, offset = Offset(37f, 12f)).normalized()
        assertEquals(Offset(37f, 12f), t.offset)
    }

    @Test
    fun `clampScale bounds both ends`() {
        assertEquals(ZoomTransform.MIN_SCALE, ZoomTransform.clampScale(0.01f))
        assertEquals(ZoomTransform.MAX_SCALE, ZoomTransform.clampScale(99f))
        assertEquals(2f, ZoomTransform.clampScale(2f))
    }

    @Test
    fun `the scale bounds are ordered`() {
        assertTrue(ZoomTransform.MIN_SCALE < ZoomTransform.MAX_SCALE)
        assertEquals(1f, ZoomTransform.MIN_SCALE)
    }
}

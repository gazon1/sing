package com.singularity.todo.feature.attachments.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.singularity.todo.core.ui.preview.PreviewThemed

/**
 * Pan and zoom, as two numbers.
 *
 * Kept as a value rather than two fields in the composable because the clamping rules
 * are the part with bugs in it, and they are only testable if they are not buried in a
 * gesture handler. A pinch that grows without bound leaves the image off-screen with no
 * way back; a double-tap that does not reset leaves it zoomed into one pixel.
 */
data class ZoomTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero) {

    /**
     * Apply a pinch: [centroid] is the gesture's focal point, [zoom] the scale change.
     *
     * The offset is scaled about the centroid and then the centroid is added back, so
     * the point under the fingers stays under the fingers. Doing only the first half —
     * the usual mistake — makes the image slide out from under the pinch.
     */
    fun zoomBy(zoom: Float, centroid: Offset): ZoomTransform {
        val nextScale = clampScale(scale * zoom)
        // Solving `p * scale + offset == centroid` for the offset that keeps the same
        // image point `p` under the centroid after the scale change:
        //
        //     nextOffset = centroid - (centroid - offset) * (nextScale / scale)
        //
        // The ratio matters. Multiplying by `nextScale` instead — the version this had
        // first — only agrees with this when `scale == 1`, so it was correct for the
        // first pinch out of a reset viewer and wrong from then on, sliding the image
        // out from under the fingers on every subsequent gesture.
        val ratio = nextScale / scale
        val nextOffset = centroid - (centroid - offset) * ratio
        return copy(scale = nextScale, offset = nextOffset)
    }

    /** Apply a pan in pixels. */
    fun panBy(dx: Float, dy: Float): ZoomTransform = copy(offset = offset + Offset(dx, dy))

    /** Return to the identity transform — used by the reset affordance and double-tap. */
    fun reset(): ZoomTransform = ZoomTransform()

    /** At or below 1× the offset is meaningless, so it is dropped. */
    fun normalized(): ZoomTransform =
        if (scale <= MIN_SCALE) ZoomTransform(1f, Offset.Zero) else this

    companion object {
        const val MIN_SCALE: Float = 1f
        const val MAX_SCALE: Float = 5f

        fun clampScale(value: Float): Float = value.coerceIn(MIN_SCALE, MAX_SCALE)
    }
}

private fun Offset.times(newScale: Float): Offset = Offset(x = x * newScale, y = y * newScale)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    title: String,
    imageModel: Any?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // rememberSaveable, not remember: the process can be recreated while this screen is
    // open (config change on Android, and the desktop window can be closed and
    // reopened against the same saved state), and a viewer that resets its zoom every
    // time is the kind of small betrayal people notice.
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    // A file that cannot be decoded is reported rather than left as a black rectangle.
    // The tile that led here showed a thumbnail, so the file existed and something
    // about it is wrong; a silent black screen reads as "the app is broken".
    var decodeFailed by rememberSaveable(imageModel) { mutableStateOf(false) }

    val transform = remember(scale, offsetX, offsetY) {
        ZoomTransform(scale, Offset(offsetX, offsetY))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (imageModel == null) {
                ViewerNotice("No local copy")
            } else if (decodeFailed) {
                ViewerNotice("This image could not be decoded")
            } else {
                AsyncImage(
                    model = imageModel,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(imageModel) {
                            // Named parameters, not positional destructuring. The lambda
                            // is `(centroid, pan, zoom, rotation)`; a positional
                            // `{ _, pan, zoom, centroid -> }` compiles into silently
                            // swapped values the moment the signature is misread, and the
                            // symptom is a viewer that zooms when you drag.
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val next = transform.normalized()
                                    .zoomBy(zoom, centroid)
                                    .panBy(pan.x, pan.y)
                                    .normalized()
                                scale = next.scale
                                offsetX = next.offset.x
                                offsetY = next.offset.y
                            }
                        }
                        .graphicsLayer {
                            scaleX = transform.scale
                            scaleY = transform.scale
                            translationX = transform.offset.x
                            translationY = transform.offset.y
                        },
                    onState = { state ->
                        if (state is coil3.compose.AsyncImagePainter.State.Error) {
                            decodeFailed = true
                        }
                    },
                )
            }
        }
    }
}

/** Centred text over the black viewer background. */
@Composable
private fun ViewerNotice(message: String) {
    Text(
        text = message,
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ImageViewerMissingPreview() = PreviewThemed(darkTheme = true) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        ViewerNotice("No local copy")
    }
}

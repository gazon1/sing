package com.singularity.todo.core.ui.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.preview.PreviewThemed

/** How dark the dimming layer is. Dark enough to read by, light enough to see through. */
private val SCRIM_COLOR = Color(0xCC000000)

/** Gap between the hole and the card, and between the card and the window edge. */
private val CARD_GAP = 16.dp

/** Horizontal inset of the card from the window edge. */
private val CARD_INSET = 24.dp

/**
 * Upper bound on the card's height.
 *
 * The placement calculation needs to know whether the card fits before it has been
 * measured, so the card is capped at this height and the estimate uses the cap. An
 * estimate above the real height is conservative in the right direction: it may choose
 * the roomier side when the card would have fitted either way, and never puts a card
 * off-screen.
 */
private val CARD_MAX_HEIGHT = 260.dp

/**
 * Dims the screen, cuts a hole around [step]'s target, and shows the explanation.
 *
 * Draws nothing when [state.bounds] is null. A scrim with no hole covers the app and
 * gives no clue what it is waiting for, which reads as a broken screen rather than as
 * "not ready yet" — so the honest thing is to draw nothing until the target is measured.
 *
 * @param stepPosition zero-based position; drives the "2 of 3" line and the last button's label.
 */
@Composable
fun SpotlightOverlay(
    state: SpotlightTourState,
    step: SpotlightStep,
    stepPosition: Int,
    stepCount: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bounds = state.bounds ?: return
    val target = spotlightHole(bounds, step.shape).expandedBy(SPOTLIGHT_HOLE_PADDING)

    // Morph the hole open rather than cross-fading the whole overlay, so the eye is led
    // out from the target. Animating from a hole of zero *size* rather than zero radius
    // keeps a fillable area throughout, which a zero-size path does not have.
    val progress by animateFloatAsState(
        targetValue = if (state.isRunning) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "spotlightHole",
    )
    val hole = lerpHole(closedHole(target), target, progress)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val gapPx = with(density) { CARD_GAP.toPx() }
        val cardHeightPx = with(density) { CARD_MAX_HEIGHT.toPx() }
        val placement = resolveCardPlacement(
            preferred = SpotlightCardPlacement.Below,
            holeTop = hole.bounds.top,
            holeBottom = hole.bounds.bottom,
            cardHeight = cardHeightPx,
            availableHeight = constraints.maxHeight.toFloat(),
            minGap = gapPx,
        )

        SpotlightScrim(hole = hole, modifier = Modifier.fillMaxSize())

        // Swallow taps so a tour does not let a press reach a control whose effect the
        // user currently cannot see. `indication = null` keeps it from looking pressed.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = ::absorbScrimTap,
                )
                .semantics { liveRegion = LiveRegionMode.Polite },
        )

        SpotlightCard(
            step = step,
            stepPosition = stepPosition,
            stepCount = stepCount,
            onNext = onNext,
            onSkip = onSkip,
            modifier = Modifier
                .align(
                    if (placement == SpotlightCardPlacement.Above) {
                        Alignment.TopCenter
                    } else {
                        Alignment.BottomCenter
                    },
                )
                .padding(
                    horizontal = CARD_INSET,
                    vertical = CARD_GAP,
                ),
        )
    }
}

/**
 * What the scrim's click target does, which is nothing, deliberately.
 *
 * A named function rather than `{}` at the call site: the empty lambda says "a handler
 * was meant here and is missing", which is exactly the reading this layer is working to
 * avoid. The name says what it is — a tap landing on the dimmed screen is absorbed, not
 * forwarded to the control underneath whose effect the user cannot currently see.
 */
private fun absorbScrimTap() = Unit

/** The dimming layer with the hole cut out of it. */
@Composable
private fun SpotlightScrim(hole: SpotlightHole, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val scrim = Path().apply {
            // EvenOdd is what makes the second contour a hole rather than a second fill.
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(
                RoundRect(
                    rect = hole.bounds,
                    cornerRadius = CornerRadius(hole.cornerRadius),
                ),
            )
        }
        drawPath(scrim, SCRIM_COLOR)
    }
}

/** The explanation card. */
@Composable
private fun SpotlightCard(
    step: SpotlightStep,
    stepPosition: Int,
    stepCount: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .sizeIn(maxWidth = 420.dp, maxHeight = CARD_MAX_HEIGHT)
            .testTag(TestTags.Onboarding.SPOTLIGHT_CARD),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = step.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${stepPosition + 1} of $stepCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onSkip, modifier = Modifier.testTag(TestTags.Onboarding.SPOTLIGHT_SKIP)) {
                    Text("Skip")
                }
                TextButton(onClick = onNext, modifier = Modifier.testTag(TestTags.Onboarding.SPOTLIGHT_NEXT)) {
                    Text(if (stepPosition == stepCount - 1) "Got it" else "Next")
                }
            }
        }
    }
}

@Preview
@Composable
private fun SpotlightOverlayPreview() = PreviewThemed {
    SpotlightOverlay(
        state = SpotlightTourState(
            stepIndex = 0,
            bounds = Rect(left = 200f, top = 96f, right = 296f, bottom = 192f),
        ),
        step = SpotlightContent.STEPS.first(),
        stepPosition = 0,
        stepCount = SpotlightContent.STEPS.size,
        onNext = {},
        onSkip = {},
    )
}

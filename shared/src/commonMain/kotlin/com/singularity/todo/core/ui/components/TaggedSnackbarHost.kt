package com.singularity.todo.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds

/**
 * A [SnackbarHost] whose action button is addressable by testTag.
 *
 * ## Why this exists instead of Material3's `SnackbarHost`
 *
 * `SnackbarHost` renders the action label internally and exposes no slot for
 * it, so the only way for a UI test to reach that button was to select it by
 * its text. That is a selector the translator owns.
 *
 * `tasks/06-delete-undo.yaml` selected `text: "Undo"`. On this project's
 * Russian-locale emulator the button reads "Отменить" and the flow failed at
 * `extendedWaitUntil` — not because the feature was broken (the undo works,
 * and a Russian user gets a working button) but because the *selector* had
 * moved. No gate looked at it: the flow had never been run.
 *
 * The same class of bug appeared twice in one session, once here and once where
 * a testTag was built from a localised label. Both are now static checks; see
 * `UiAutomationSelectorTest`.
 *
 * Everything else about the snackbar is Material3's own — this only re-frames
 * the action slot so a modifier can reach it.
 *
 * ## The button the shell floats over the content
 *
 * The shell's floating action button is drawn over the screens, and a screen's
 * snackbar is drawn at the bottom of that screen — under the button. The host
 * keeps the space the button takes clear at the bottom, so the action stays
 * visible and tappable (#2752). The space comes from the shell
 * ([LocalFabBottomSpace]), so it is zero on a route without a button.
 *
 * The same host is also used inside a `Popup` (`NotificationHost`): a popup is
 * its own window, drawn above the button, so it never needed the clearance —
 * but it consumes the same value, and the lift it gets there is cosmetic.
 *
 * ## Countdown variant
 *
 * When [countdownProgress] is not null, a [LinearProgressIndicator] is shown
 * below the snackbar surface to visualise the remaining undo window. The
 * indicator animates smoothly between values.
 */
@Composable
fun TaggedSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    countdownProgress: Float? = null,
) {
    // The shell's button floats over the content, and a snackbar is content:
    // without this the action lands under the button, and a tap on it opens the
    // button instead of the action (#2752). The shell publishes the space its
    // button takes; the host keeps that much clear at the bottom. Zero when no
    // button is on screen.
    val fabBottomSpace = LocalFabBottomSpace.current
    SnackbarHost(
        hostState = hostState,
        modifier = modifier.padding(bottom = fabBottomSpace),
    ) { data ->
        TaggedSnackbar(data, countdownProgress)
    }
}

@Composable
private fun TaggedSnackbar(
    data: SnackbarData,
    countdownProgress: Float?,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = countdownProgress ?: 1f,
        label = "undo_countdown",
    )

    Box {
        SnackbarWithAction(data)
        if (countdownProgress != null) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier =
                    Modifier
                        .width(300.dp)
                        .height(3.dp)
                        .padding(horizontal = 16.dp)
                        .align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun SnackbarWithAction(data: SnackbarData) {
    val actionLabel = data.visuals.actionLabel
    if (actionLabel == null) {
        Snackbar(modifier = Modifier.mapTestTagsAsResourceIds()) {
            Text(
                text = data.visuals.message,
                // The message is addressable too, for the same reason the action
                // button is: the copy is the translator's, and a flow that waits
                // for "Backup created" waits forever on a Russian device.
                modifier =
                    Modifier
                        .testTag(TestTags.SNACKBAR_MESSAGE)
                        .mapTestTagsAsResourceIds(),
            )
        }
        return
    }
    Snackbar(
        modifier = Modifier.mapTestTagsAsResourceIds(),
        action = {
            TextButton(
                onClick = data::performAction,
                modifier =
                    Modifier
                        .testTag(TestTags.SNACKBAR_ACTION)
                        .mapTestTagsAsResourceIds(),
            ) {
                Text(actionLabel)
            }
        },
    ) {
        Text(
            text = data.visuals.message,
            modifier =
                Modifier
                    .testTag(TestTags.SNACKBAR_MESSAGE)
                    .mapTestTagsAsResourceIds(),
        )
    }
}

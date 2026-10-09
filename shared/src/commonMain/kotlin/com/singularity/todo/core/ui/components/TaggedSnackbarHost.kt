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
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
    ) { data ->
        TaggedSnackbar(data, countdownProgress)
    }
}

@Composable
private fun TaggedSnackbar(data: SnackbarData, countdownProgress: Float?) {
    val animatedProgress by animateFloatAsState(
        targetValue = countdownProgress ?: 1f,
        label = "undo_countdown",
    )

    Box {
        SnackbarWithAction(data)
        if (countdownProgress != null) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
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
            Text(data.visuals.message)
        }
        return
    }
    Snackbar(
        modifier = Modifier.mapTestTagsAsResourceIds(),
        action = {
            TextButton(
                onClick = data::performAction,
                modifier = Modifier
                    .testTag(TestTags.SNACKBAR_ACTION)
                    .mapTestTagsAsResourceIds(),
            ) {
                Text(actionLabel)
            }
        },
    ) {
        Text(data.visuals.message)
    }
}

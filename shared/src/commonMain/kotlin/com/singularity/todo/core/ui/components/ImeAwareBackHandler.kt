package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * What a back press means on a screen that can hold the keyboard.
 *
 * The platform's contract is that a back press with the keyboard open closes
 * the keyboard, and only a back press with the keyboard closed leaves the
 * screen. An app that treats the two as one throws the user's work away:
 * the keyboard hides *and* the screen goes, and the half-typed title is
 * gone.
 *
 * Measured on a real phone (2026-10-28, realme RMX3938, SDK 35): the task
 * editor received the back press with the IME up, closed, and the title that
 * had been typed into it was lost.
 */
enum class BackAction {
    /** The keyboard is open: the back press closes the keyboard. */
    HideKeyboard,

    /** The keyboard is closed: the back press means what the screen says it means. */
    Leave,
}

/**
 * What a back press means, given whether the keyboard is on the screen.
 *
 * @param imeVisible whether the keyboard is showing.
 * @return what the back press does.
 */
fun backAction(imeVisible: Boolean): BackAction =
    if (imeVisible) BackAction.HideKeyboard else BackAction.Leave

/**
 * A back press that closes the keyboard before it leaves the screen.
 *
 * The screen's own meaning of "back" — leaving, asking whether to discard a
 * draft, going back a step — stays where it was: in [onBack]. This only
 * decides whether that meaning applies *now*, and the answer is the platform's
 * answer: with the keyboard open, the keyboard is what a back press is for.
 *
 * @param enabled whether the gesture is listened for at all.
 * @param onBack what the back press means when the keyboard is closed.
 */
@Composable
fun ImeAwareBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
) {
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val keyboard = LocalSoftwareKeyboardController.current
    PlatformBackHandler(enabled = enabled) {
        when (backAction(imeVisible)) {
            BackAction.HideKeyboard -> keyboard?.hide()
            BackAction.Leave -> onBack()
        }
    }
}

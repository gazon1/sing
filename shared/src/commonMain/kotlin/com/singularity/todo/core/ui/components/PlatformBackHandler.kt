package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Composable

/**
 * The platform's own back gesture, as a composable.
 *
 * ## Why this is a seam
 *
 * Only Android has a system back gesture. Desktop has a window, a title bar and
 * a keyboard shortcut, and none of them is the phone's back button. A screen
 * that wants to react to "the user wants to go back" has to say so in a way
 * that exists on both, and this is that way: on Android it delegates to
 * `androidx.activity.compose.BackHandler`, on desktop it does nothing.
 *
 * The seam lives in `core/ui` rather than in a feature because *every* feature
 * has editors, and the editor is where the question "what does back mean here?"
 * is asked. A seam per feature would be the same seam twice.
 *
 * ## What it does not decide
 *
 * The handler decides nothing about *meaning*. It says: "a back happened, here
 * is what to do". Whether a back closes a screen, asks a question, or closes
 * the keyboard is the caller's business — see [ImeAwareBackHandler] for the
 * decision a screen with a keyboard has to make.
 *
 * @param enabled whether the gesture is listened for at all. A screen that has
 *   nothing to do with a back press turns it off rather than swallowing it.
 * @param onBack called when the gesture happens.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

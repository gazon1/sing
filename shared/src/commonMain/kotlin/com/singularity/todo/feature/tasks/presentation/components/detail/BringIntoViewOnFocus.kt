package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalDensity

/**
 * Keeps a focused field visible inside its scroll parent when the IME appears
 * or changes size.
 *
 * A single bring-into-view request on focus is not enough on Android: the IME
 * animates in after focus, so the field can be visible at the moment of focus
 * and end up hidden once the viewport shrinks. Re-requesting while focused and
 * the IME inset changes brings the field back above the keyboard.
 */
fun Modifier.bringIntoViewOnFocus(): Modifier =
    composed {
        val requester = remember { BringIntoViewRequester() }
        var isFocused by remember { mutableStateOf(false) }
        val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

        LaunchedEffect(isFocused, imeBottom) {
            if (isFocused) {
                requester.bringIntoView()
            }
        }

        this
            .bringIntoViewRequester(requester)
            .onFocusEvent { isFocused = it.isFocused }
    }

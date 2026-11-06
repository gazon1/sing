package com.singularity.todo.core.ui.text

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * Who opens an internal link when the user taps it.
 *
 * The provider is set once, at the app's root, where the navigation lives. Every
 * screen below it can then offer tappable links without knowing how to navigate.
 *
 * The default does nothing: a link tapped in a preview, in a test, or in a screen
 * that has not been given a handler, must not crash and must not navigate.
 *
 * @see LocalInternalLinkHandler — the provider itself.
 */
typealias InternalLinkHandler = (TextLink.Internal) -> Unit

/**
 * The handler that opens an internal link.
 *
 * @see InternalLinkHandler
 */
val LocalInternalLinkHandler: ProvidableCompositionLocal<InternalLinkHandler> =
    compositionLocalOf { { } }

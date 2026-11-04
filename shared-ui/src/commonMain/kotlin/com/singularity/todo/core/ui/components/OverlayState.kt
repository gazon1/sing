package com.singularity.todo.core.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.singularity.todo.core.serialization.StableJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * State holder coordinating a single active sheet/dialog overlay, an overflow menu toggle,
 * and a [SnackbarHostState] for transient notifications.
 *
 * Used to replace the common pattern of three independent `remember` flags:
 * ```kotlin
 * // Before — three independent flags, easy to leave inconsistent
 * var showSheet by remember { mutableStateOf<Sheet?>(null) }
 * var overflowOpen by remember { mutableStateOf(false) }
 * val snackbarHostState = remember { SnackbarHostState() }
 *
 * // After — one holder, coordinated transitions
 * val overlay = rememberOverlayState(Sheet.serializer())
 * overlay.show(Sheet.PickColor)
 * overlay.dismissSheet()
 * overlay.toggleOverflow()
 * ```
 *
 * @param S The sheet/dialog type, typically a sealed interface that is `@Serializable`.
 * @param initialSheet the sheet a restored holder reopens, or `null` for a fresh one.
 * @param initialOverflowOpen whether a restored holder reopens the overflow menu.
 */
@Stable
class OverlayState<S : Any>(
    initialSheet: S? = null,
    initialOverflowOpen: Boolean = false,
) {
    private var active by mutableStateOf(initialSheet)
    private var overflow by mutableStateOf(initialOverflowOpen)

    /** The currently active sheet/dialog, or `null` if none is shown. */
    val sheet: S? get() = active

    /** Whether the overflow menu is currently open. */
    val isOverflowOpen: Boolean get() = overflow

    /** The [SnackbarHostState] for this overlay. */
    val snackbarHostState: SnackbarHostState = SnackbarHostState()

    /**
     * Shows the given sheet/dialog, automatically closing the overflow menu first.
     */
    fun show(sheet: S) {
        active = sheet
        overflow = false
    }

    /** Dismisses any active sheet/dialog. */
    fun dismissSheet() {
        active = null
    }

    /**
     * Toggles the overflow menu. Opening the overflow menu automatically dismisses
     * any active sheet.
     */
    fun toggleOverflow() {
        overflow = !overflow
        if (overflow) active = null
    }

    /** Closes the overflow menu. */
    fun dismissOverflow() {
        overflow = false
    }

    /** Dismisses everything: both the active sheet and the overflow menu. */
    fun dismissAll() {
        active = null
        overflow = false
    }
}

/**
 * Creates an [OverlayState] scoped to the current composable.
 *
 * The open sheet and the overflow menu are remembered with [rememberSaveable], so
 * they survive what a plain `remember` does not: on Android a rotation recreates the
 * activity, and the platform also drops the composition when it reclaims a
 * backgrounded process. A restored screen reopens the same sheet, so a confirmation
 * the user was reading is still there when the screen comes back.
 *
 * The [SnackbarHostState] is deliberately outside the saved state. A snackbar is a
 * message about something that already happened; replaying it into a recreated screen
 * would say it a second time.
 *
 * @param sheetSerializer the serializer of [S]. It is a parameter rather than a
 * reified type argument so that a sheet type which is not `@Serializable` is a
 * compile error at the call site, not an empty screen after a rotation.
 */
@Composable
fun <S : Any> rememberOverlayState(sheetSerializer: KSerializer<S>): OverlayState<S> =
    rememberSaveable(saver = overlayStateSaver(sheetSerializer)) { OverlayState<S>() }

/**
 * The [Saver] behind [rememberOverlayState], exposed so that a restore can be asserted
 * without a Compose runtime.
 *
 * The saved value is the sheet itself, encoded with [StableJson], together with the
 * overflow flag. Saving the sheet — rather than a per-screen token each screen has to
 * map back — keeps the restoration rule in one place: whatever the screen was showing,
 * it is still showing it.
 */
fun <S : Any> overlayStateSaver(sheetSerializer: KSerializer<S>): Saver<OverlayState<S>, String> =
    Saver(
        save = { state ->
            StableJson.encodeToString(
                SavedOverlay(
                    overflow = state.isOverflowOpen,
                    sheet = state.sheet?.let { StableJson.encodeToJsonElement(sheetSerializer, it) },
                ),
            )
        },
        restore = { saved -> restoreOverlay(saved, sheetSerializer) },
    )

/**
 * What a saved [OverlayState] looks like: the overflow flag and the sheet, already
 * encoded, so the holder's type parameter never has to reach the saver's own
 * serializer.
 */
@Serializable
private class SavedOverlay(
    val overflow: Boolean = false,
    val sheet: JsonElement? = null,
)

private fun <S : Any> restoreOverlay(saved: String, sheetSerializer: KSerializer<S>): OverlayState<S> =
    try {
        val stored = StableJson.decodeFromString<SavedOverlay>(saved)
        OverlayState<S>(
            initialSheet = stored.sheet?.let { StableJson.decodeFromJsonElement(sheetSerializer, it) },
            initialOverflowOpen = stored.overflow,
        )
    } catch (_: SerializationException) {
        // A value written by a build whose sheet type has since changed shape, or a
        // truncated one. Opening no sheet is the state the screen had before the sheet
        // was opened, and it is better than taking the screen down during restoration.
        OverlayState<S>()
    }

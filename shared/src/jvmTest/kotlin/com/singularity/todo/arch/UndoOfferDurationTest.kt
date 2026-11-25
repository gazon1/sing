package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The undo offer has to outlive the tap that answers it.
 *
 * The delete flow of the task detail offers an undo in a snackbar, and the
 * snackbar is the only way back from a delete. It used to live four seconds —
 * `SnackbarDuration.Short`, the duration of a notice nobody has to answer —
 * while the tap that presses it needs seconds to arrive on a loaded device.
 * The offer was gone before the tap landed, and the tap hit whatever the
 * snackbar had been covering.
 *
 * The check is on the source, because the lifetime is a constant in the host
 * and not an observable of the flow: the flow can only say that the undo did
 * or did not happen, and it says that for a hundred reasons. Pinning the
 * constant says what the decision actually was. See
 * `docs/decisions/2026-11-26-the-undo-offer-outlives-the-tap.md`.
 */
@Tag("fast")
class UndoOfferDurationTest {
    private val host: String =
        CommonMainKonsistScope.scope.files
            .single { it.path.replace('\\', '/').substringAfterLast('/') == "NotificationHost.kt" }
            .let { KotlinText.stripComments(it.text) }

    /** The body of the branch that handles [branch], comments already stripped. */
    private fun branch(branch: String): String {
        val marker = "is Notification.$branch ->"
        val start = host.indexOf(marker)
        assertTrue(start >= 0, "NotificationHost has no $branch branch")
        return assertNotNull(
            KotlinText.blockBody(host.substring(start + marker.length)),
            "the $branch branch has no body",
        )
    }

    @Test
    fun the_undo_offer_lives_long_enough_to_be_answered() {
        val body = branch("Undo")
        assertTrue(
            "SnackbarDuration.Long" in body,
            "the undo offer is a snackbar with an action and must use " +
                "SnackbarDuration.Long — a four-second offer is gone before the " +
                "tap that answers it arrives: $body",
        )
        assertTrue(
            "SnackbarDuration.Short" !in body,
            "the undo offer must not use SnackbarDuration.Short: $body",
        )
    }

    @Test
    fun a_plain_notice_still_disappears_on_its_own() {
        val body = branch("Toast")
        assertTrue(
            "SnackbarDuration.Short" in body,
            "a notice nobody has to answer stays SnackbarDuration.Short: $body",
        )
    }
}

package com.singularity.todo.core.refresh

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.toAppError
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.sync.SyncOutcome
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.components.Notification

/**
 * The one-shot events of a refresh.
 *
 * There is one event, and the result it carries says what happened. The
 * variants are not "archived" and "failed": a refresh can archive three tasks
 * *and* fail to synchronise, and a vocabulary that cannot say that forces the
 * message to pick the worse of the two facts.
 */
sealed interface RefreshUiEvent : MviEvent {
    /** The refresh finished — successfully or not. */
    data class Finished(val outcome: RefreshOutcome) : RefreshUiEvent
}

/**
 * The one mapping from a result to what the user reads.
 *
 * It lives here rather than in every screen, so that every screen says the
 * same thing about the same outcome. The wording follows
 * `specs/015-pull-refresh-sync-archive/contracts/refresh-outcome.md`.
 *
 * The report is a notice, not a dialog: it confirms an action the user just
 * asked for, so it must not wait for an answer. The failure rows say that the
 * archive step already happened on the device — the user has to know that the
 * work is safe, because the server is the part that failed.
 */
fun RefreshOutcome.toNotification(): Notification {
    val archiveFailure = archived.exceptionOrNull()
    val syncFailure = sync.failureOrNull()

    if (archiveFailure != null) {
        val archiveMessage = "Could not move completed tasks to the archive: ${archiveFailure.toMessage()}"
        return Notification.Toast(
            message = if (syncFailure != null) {
                "$archiveMessage. Sync failed: ${syncFailure.toMessage()}"
            } else {
                archiveMessage
            },
        )
    }

    val moved = archived.getOrDefault(0)
    val movedText = if (moved > 0) {
        if (moved == 1) "1 task moved to the archive" else "$moved tasks moved to the archive"
    } else {
        "Nothing to move to the archive"
    }

    return Notification.Toast(
        message = when {
            syncFailure != null ->
                "$movedText on this device. Sync failed: ${syncFailure.toMessage()}"

            sync is SyncOutcome.NothingToDo ->
                "$movedText. Sync is unavailable without signing in."

            else ->
                "Synced. $movedText."
        },
    )
}

/**
 * The report of a finished refresh.
 */
fun RefreshUiEvent.toNotification(): Notification = when (this) {
    is RefreshUiEvent.Finished -> outcome.toNotification()
}

/**
 * The synchronisation's failure, if it failed.
 *
 * A cycle that ran and lost a phase is a failure; a cycle that never ran
 * because there was no synchronisation to run is not one — that is the
 * signed-out case, and it gets its own message.
 */
private fun SyncOutcome.failureOrNull(): AppError? = when (this) {
    is SyncOutcome.Completed -> (push.exceptionOrNull() ?: pull.exceptionOrNull())?.toAppError()
    is SyncOutcome.CouldNotStart -> error
    SyncOutcome.NothingToDo -> null
}

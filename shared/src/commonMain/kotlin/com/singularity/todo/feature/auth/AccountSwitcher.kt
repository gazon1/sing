package com.singularity.todo.feature.auth

import com.singularity.todo.core.auth.AuthDomain
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.SyncOutcome
import com.singularity.todo.core.sync.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Replacing the signed-in account: deliver, then erase, then sign in.
 *
 * ## Why this is a separate entry point and not a flag on sign-out
 *
 * Signing out keeps local data. Switching accounts erases it. Those are opposite
 * outcomes of the same gesture, and folding them into one method with a boolean would
 * make the destructive one reachable by a caller that thought it was doing the safe one
 * — which is the same reason `signOut` has no parameters here at all.
 *
 * The two live on different objects for the same reason: `signOut` is a verb of
 * [AuthRepository], and this one is not, because it needs three collaborators that
 * repository has no business holding.
 *
 * ## The order, and why erasing first is not an option
 *
 * The departing account's queued work is the user's unsent edits. Erasing first would
 * destroy them locally before anything had sent them, and no later cycle could recover
 * them — they only ever existed on this device. So the queue goes out first, under the
 * departing account's own session, and only a delivery that did not fail is followed by
 * an erase.
 *
 * ## Why a refused switch is the common case on a plane
 *
 * `authRepository.signIn` after the erase can still fail — a wrong password, a provider
 * that is down — and then the user would be signed out of their old account with its data
 * gone. That ordering is the reason the credentials are validated **before** anything is
 * erased, while the old session is still intact and can be restored. See
 * `2026-10-05-switching-users-delivers-then-erases-the-departing-user`.
 *
 * @param progress what the switch is doing, so a slow step cannot be mistaken for a
 * hang. See [SwitchStep].
 */
class AccountSwitcher(
    private val authRepository: AuthRepository,
    private val syncRepository: SyncRepository,
    private val resolver: OwnerRowIdResolver,
    private val eraser: OwnerScopedEraser,
) {

    private val _progress = MutableStateFlow<SwitchStep>(SwitchStep.Idle)
    val progress: StateFlow<SwitchStep> = _progress.asStateFlow()

    /**
     * Signs [email] in, replacing the current account and erasing the one it replaces.
     *
     * Nothing is erased unless the new credentials are accepted **and** the departing
     * account's queued work has been delivered. Any failure leaves the device exactly as
     * it was, still signed in to the same account with the same local data.
     */
    suspend fun switchTo(email: String, password: String): Result<Unit> {
        val departing = (authRepository.currentSession.value as? Session.SignedIn)?.userId
        if (departing == null) {
            return Result.failure(
                AppError.Validation(
                    "Switching accounts requires a signed-in account to replace",
                    code = "auth.switch_not_signed_in",
                ),
            )
        }

        // Each step is a separate function so that the sequence reads top to bottom and
        // the early return lives in one place per step rather than in the middle of the
        // one that follows it. The order is the design: credentials, then delivery, then
        // the erase, then the new session.
        // `mapCatching` twice over, and the nesting is the point: it short-circuits on
        // failure, so a step that did not happen cannot have the next one run after it.
        // `map` would have produced a `Result<Result<Unit>>` and run the whole sequence
        // regardless — which is the exact ordering bug this class exists to prevent.
        val outcome = validateCredentials(email, password)
            .mapCatching { deliver(departing).getOrThrow() }
            .mapCatching { erase(departing).getOrThrow() }
            .mapCatching { signIn(email, password).getOrThrow() }

        return outcome.fold(
            onSuccess = {
                _progress.value = SwitchStep.Done
                Result.success(Unit)
            },
            onFailure = { refuse(it.toAppError()) },
        )
    }

    /**
     * Validates before anything irreversible, so a wrong password cannot leave the
     * device signed out of an account whose data it has just erased.
     */
    private fun validateCredentials(email: String, password: String): Result<Unit> {
        _progress.value = SwitchStep.CheckingCredentials
        return runCatchingResult {
            AuthDomain.validateEmail(email)
            AuthDomain.validatePassword(password)
        }
    }

    /**
     * Sends the departing account's queued work, under the session that owns it.
     *
     * This is the step that cannot be reordered. Erasing first would destroy edits that
     * exist nowhere but this device.
     */
    private suspend fun deliver(departing: UserId): Result<Unit> {
        _progress.value = SwitchStep.Delivering(departing)
        return runCatchingResult {
            switchDeliveryFailure(syncRepository.syncOnce())?.let { throw it }
        }
    }

    private suspend fun erase(departing: UserId): Result<Unit> {
        _progress.value = SwitchStep.Erasing(departing)
        // `erase` returns counts and throws; the trailing `Unit` is what keeps this a
        // `Result<Unit>` step rather than a `Result<EraseCounts>` one, so the chain
        // below does not have to know what was removed.
        return runCatchingResult {
            eraser.erase(resolver.resolve(departing))
            Unit
        }
    }

    /**
     * `getOrThrow()` is what makes this a step rather than a no-op.
     *
     * `runCatchingResult` only catches a *thrown* throwable. Wrapping a call that
     * returns `Result<Unit>` gives `Result<Result<Unit>>`: the inner failure is a
     * successful block returning a failed value, so the chain would sail through it and
     * report the whole switch as done — with the departing account's data erased and
     * nobody signed in. Throwing first is what turns the inner failure into a real one.
     */
    private suspend fun signIn(email: String, password: String): Result<Unit> {
        _progress.value = SwitchStep.SigningIn
        return runCatchingResult { authRepository.signIn(email, password).getOrThrow() }
    }

    /** Records a refusal so a screen can show it rather than spin. */
    private fun refuse(reason: AppError): Result<Unit> {
        _progress.value = SwitchStep.Refused(reason)
        return Result.failure(reason)
    }

    /**
     * Whether a cycle leaves the departing account with nothing unsent.
     *
     * `null` means "delivered": the outcome is a failure to read rather than a statement
     * that everything reached the server, which is what [SyncOutcome.Failure] and a
     * failed `Result` both are. A discarded push response counts as not delivered —
     * `PushSummary.discarded` is work this account has not seen acknowledged, and
     * erasing the rows would lose it.
     */
    private fun switchDeliveryFailure(outcome: SyncOutcome): AppError? = when (outcome) {
        // The cycle never ran — a local read failed. Nothing was sent, so nothing may
        // be erased.
        is SyncOutcome.CouldNotStart -> outcome.error

        // No cycle ran and none will: no active sync scope or the coordinator is closed.
        // Also nothing sent.
        is SyncOutcome.NothingToDo -> AppError.Unknown(
            "Sync is not running, so the queued changes were not sent",
            code = "auth.switch_delivery_skipped",
        )

        is SyncOutcome.Completed -> {
            val push = outcome.push.getOrElse {
                return AppError.Network(
                    "The queued changes could not be sent before switching accounts",
                    code = "auth.switch_delivery_failed",
                )
            }
            if (push.failed > 0) {
                AppError.Network(
                    "${push.failed} queued change(s) could not be sent before switching accounts",
                    code = "auth.switch_delivery_failed",
                )
            } else if (push.discarded > 0) {
                AppError.Network(
                    "${push.discarded} queued change(s) were not acknowledged before switching accounts",
                    code = "auth.switch_delivery_discarded",
                )
            } else {
                null
            }
        }
    }
}

/** What a switch is doing right now, for the UI to show rather than a spinner. */
sealed interface SwitchStep {
    data object Idle : SwitchStep

    /** Validating the incoming credentials, before anything irreversible. */
    data object CheckingCredentials : SwitchStep

    /** Sending the departing account's queued work. */
    data class Delivering(val owner: UserId) : SwitchStep

    /** Removing the departing account's local rows. */
    data class Erasing(val owner: UserId) : SwitchStep

    data object SigningIn : SwitchStep

    data object Done : SwitchStep

    /**
     * The switch did not happen, and why.
     *
     * A state rather than a thrown error because the refusal is an ordinary outcome — a
     * device with no network is the common case — and a screen that has to render it is
     * better served by a value it can draw than by an exception it has to catch.
     */
    data class Refused(val reason: AppError) : SwitchStep
}

/** [AppError] from a throwable, matching the auth repository's own mapping. */
private fun Throwable.toAppError(): AppError =
    this as? AppError ?: AppError.Unknown(message ?: "unknown", code = "auth.switch_failed")

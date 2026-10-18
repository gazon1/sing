package com.singularity.todo.feature.auth

import com.singularity.todo.core.auth.AuthDomain
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Turns a local, account-less session into a real one — and decides what happens to
 * the data in between.
 *
 * ## Why this is a separate entry point rather than a method on the repository
 *
 * Same reason [AccountSwitcher] is not a method on it. This needs a database-backed
 * resolver, a transfer and an eraser; `core/auth` has no business holding any of them,
 * and the invariant that keeps the domain free of storage collaborators is what makes
 * the repository testable in the first place.
 *
 * ## Why the account comes before the data
 *
 * A wrong password is by far the most common outcome, and it must leave the device
 * untouched. Validating first and signing in second means the only thing that can fail
 * before any local row has moved is something the user can fix. Once the account is
 * signed in, the remaining work is local, and its failures are about *this* device
 * rather than about the password they typed.
 *
 * ## Why the choice is never made for the user
 *
 * REQ-UA-005: both outcomes are supported and neither is a default the app may apply
 * silently. [LocalDataDisposition.Keep] moves the rows and they then sync like any other
 * account's; [LocalDataDisposition.Discard] erases them through the existing
 * [OwnerScopedEraser]. A third outcome — moving them and saying nothing — is the one
 * this class exists to make impossible, because it is the one a user cannot detect.
 *
 * ## Why a failed transfer returns the user to their local session
 *
 * The transfer is not atomic (see [OwnerScopedReassigner]), so a failure can leave rows
 * split across two owners. Signing the user out and back in locally is the honest
 * response: they keep everything they had, the account they just typed is closed
 * cleanly, and nothing is claimed half-way. The alternative — staying signed in to an
 * account whose data is missing rows — is a state the user cannot recover from and
 * cannot even see.
 */
class LocalAccountClaimer(
    private val authRepository: AuthRepository,
    private val resolver: OwnerRowIdResolver,
    private val reassigner: OwnerScopedReassigner,
    private val eraser: OwnerScopedEraser,
    private val calculator: OwnerCountsCalculator,
) {

    /**
     * Signs [email] in and hands the local rows to the account or removes them.
     *
     * Returns a [Flow] of [ClaimStep] — every step is emitted as it happens, so the
     * caller (typically [AuthViewModel]) can surface intermediate states (e.g.
     * [ClaimStep.SignOutFailed]) to the UI without a separate observer.
     *
     * The flow completes with the terminal step: [ClaimStep.Done] on success, or
     * [ClaimStep.Refused] when the claim cannot proceed at all.
     *
     * @param disposition what to do with the local data. There is no overload without
     * one — the choice is not a default the caller may forget to make.
     */
    suspend fun claim(
        email: String,
        password: String,
        disposition: LocalDataDisposition,
    ): Flow<ClaimStep> = channelFlow {
        val local = (authRepository.currentSession.value as? Session.Anonymous)?.userId
        if (local == null) {
            send(
                ClaimStep.Refused(
                    AppError.Validation(
                        "Only a local session can be attached to an account",
                        code = "auth.not_local",
                    ),
                ),
            )
            return@channelFlow
        }

        // Validated before anything is signed in, so a malformed address never reaches
        // the network and a wrong password never reaches the database.
        val address = AuthDomain.normalizeEmail(email)
        val prepared = runCatchingResult {
            AuthDomain.validateEmail(address)
            AuthDomain.validatePassword(password)
        }

        send(ClaimStep.SigningIn)
        // One refusal for both pre-move failures, because both leave the device exactly
        // as it was — which is the whole reason they are checked before anything moves.
        val signInFailure = prepared.exceptionOrNull()?.toAppError()
            ?: authRepository.signIn(address, password).exceptionOrNull()?.toAppError()
        if (signInFailure != null) {
            // Nothing has moved, and the local session is still the live one.
            send(ClaimStep.Refused(signInFailure))
            return@channelFlow
        }

        val account = (authRepository.currentSession.value as? Session.SignedIn)?.userId
        if (account == null) {
            send(
                ClaimStep.Restoring(
                    local,
                    AppError.Unauthorized(
                        "Signing in left no session behind",
                        code = "auth.no_session",
                    ),
                ),
            )
            restoreLocalFlow(
                local,
                AppError.Unauthorized(
                    "Signing in left no session behind",
                    code = "auth.no_session",
                ),
            ).collect { send(it) }
            return@channelFlow
        }

        // Apply the chosen disposition; any failure triggers restore.
        try {
            when (disposition) {
                is LocalDataDisposition.Keep -> {
                    val resolved = resolver.resolve(local)
                    val (reassignCounts, _) = calculator.compute(resolved)
                    send(ClaimStep.MovingData(local, account, reassignCounts))
                    reassigner.reassign(resolver.resolveMapping(local, account))
                }

                is LocalDataDisposition.Discard -> {
                    val resolved = resolver.resolve(local)
                    val (_, eraseCounts) = calculator.compute(resolved)
                    send(ClaimStep.RemovingData(local, eraseCounts))
                    eraser.erase(resolved)
                }
            }
            send(ClaimStep.Done(disposition))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            send(ClaimStep.Restoring(local, e.toAppError()))
            restoreLocalFlow(local, e.toAppError()).collect { send(it) }
        }
    }

    /**
     * Performs the chosen disposition.
     *
     * A separate function so the two outcomes read as the pair they are, and so a third
     * one added later has to be added here, where both existing ones are visible.
     */
    private suspend fun apply(
        disposition: LocalDataDisposition,
        local: UserId,
        account: UserId,
    ) {
        when (disposition) {
            is LocalDataDisposition.Keep -> {
                reassigner.reassign(resolver.resolveMapping(local, account))
            }

            is LocalDataDisposition.Discard -> {
                eraser.erase(resolver.resolve(local))
            }
        }
    }

    /**
     * What the local owner holds right now, packaged as the two disposition options.
     *
     * Uses [OwnerCountsCalculator] to compute both [ReassignCounts] and [EraseCounts]
     * in a single pass over the database — the screen needs both before the user chooses,
     * and showing numbers that appear after the click is worse than showing ones that
     * were already there.
     */
    suspend fun summarise(local: UserId): LocalDataDisposition {
        val resolved = resolver.resolve(local)
        val (reassignCounts, eraseCounts) = calculator.compute(resolved)
        val summary = LocalDataSummary(tasks = reassignCounts.tasks, notes = reassignCounts.notes)
        return LocalDataDisposition.Keep(reassignCounts, summary)
    }

    /**
     * Returns the device to the local session it had before [claim] was called, and
     * emits the steps that describe what happened.
     *
     * Signs out first so the credentials typed a moment ago are not left on the device
     * beside a session that says otherwise — an account the user could not get into is
     * not one they asked to stay signed in to.
     */
    private fun restoreLocalFlow(local: UserId, reason: AppError): Flow<ClaimStep> = channelFlow {
        // Observed rather than fired and forgotten: a sign-out that failed leaves the
        // credentials on the device while the session is about to say otherwise, and
        // that is a different problem from the one the user triggered. The local session
        // is still started — they keep their data and stay inside the app — but the
        // next launch will restore the account from the token that never went away,
        // so this has to be on the record rather than silently swallowed.
        val signOutFailure = authRepository.signOut().exceptionOrNull()?.toAppError()
        if (signOutFailure != null) {
            send(ClaimStep.SignOutFailed(local, signOutFailure))
        }

        authRepository.startLocalSession().fold(
            onSuccess = {
                // The sign-out failure is the more serious problem: the account's
                // credentials are still on the device and the next launch will restore
                // the account from them. Emit it as the terminal step so the caller
                // can report it rather than the transfer error.
                if (signOutFailure != null) {
                    send(ClaimStep.Refused(signOutFailure))
                } else {
                    send(ClaimStep.Refused(reason))
                }
            },
            onFailure = {
                // The local session could not be re-established either. The rows are
                // still on disk under `local`, and the login screen regenerates the
                // same id on the next press, so the data is not lost — but the user has
                // to press the button again, and saying so beats reporting silence.
                send(
                    ClaimStep.Refused(
                        AppError.Persistence(
                            "The account could not be attached, and the offline session could not be " +
                                "restored either. Your data is still on this device — continue offline " +
                                "to pick it up.",
                            code = "auth.local_restore_failed",
                            cause = signOutFailure ?: reason,
                        ),
                    ),
                )
            },
        )
    }
}

/**
 * What a local user chooses to do with the data they created without an account.
 *
 * Carries the full per-table counts for each option so the confirmation screen can
 * show exactly what will be moved or removed — and not a generic warning.
 */
sealed class LocalDataDisposition {
    /**
     * Move the rows to the account, so they sync like any other account's data.
     *
     * @param transferCounts what will be moved, for the confirmation screen.
     * @param summary human-readable task/note summary for the initial preview (before
     *   the user has chosen, when the full counts are not yet visible).
     */
    data class Keep(val transferCounts: ReassignCounts, val summary: LocalDataSummary) : LocalDataDisposition()

    /**
     * Remove the rows. The account starts empty.
     *
     * @param eraseCounts what will be erased, for the confirmation screen.
     */
    data class Discard(val eraseCounts: EraseCounts) : LocalDataDisposition()
}

/** What a claim is doing right now, for the UI to show rather than a spinner. */
sealed interface ClaimStep {
    data object Idle : ClaimStep

    /** Signing in, before any local row has moved. */
    data object SigningIn : ClaimStep

    /** Re-filing the local rows onto the account. */
    data class MovingData(val from: UserId, val to: UserId, val counts: ReassignCounts) : ClaimStep

    /** Erasing the local rows. */
    data class RemovingData(val owner: UserId, val counts: EraseCounts) : ClaimStep

    /** Putting the device back on its local session after something failed. */
    data class Restoring(val owner: UserId, val reason: AppError) : ClaimStep

    /**
     * The sign-out on the way back to the local session failed, so the account's
     * credentials are still on the device. The local session is restored regardless;
     * this is here so the caller can say the two disagree.
     */
    data class SignOutFailed(val owner: UserId, val reason: AppError) : ClaimStep

    /**
     * The claim completed and the disposition was applied.
     *
     * @param disposition what was done, so the caller can report the outcome
     *   (e.g. "12 tasks moved to your account" or "All local data removed").
     */
    data class Done(val disposition: LocalDataDisposition) : ClaimStep

    /**
     * The claim did not happen, and why.
     *
     * A state rather than a thrown error because the refusal is an ordinary outcome — a
     * wrong password on a plane is the common case — and a screen is better served by a
     * value it can draw than by an exception it has to catch.
     */
    data class Refused(val reason: AppError) : ClaimStep
}

/** Human-readable outcome message for the UI after a claim completes. */
internal val LocalDataDisposition.toastMessage: String
    get() = when (this) {
        is LocalDataDisposition.Keep -> {
            val total = transferCounts.tasks + transferCounts.notes
            if (total > 0) {
                "${transferCounts.tasks} task${if (transferCounts.tasks != 1) "s" else ""} and " +
                    "${transferCounts.notes} note${if (transferCounts.notes != 1) "s" else ""} " +
                    "moved to your account"
            } else {
                "Your data is now linked to this account"
            }
        }

        is LocalDataDisposition.Discard -> "All local data removed from this device"
    }

/** [AppError] from a throwable, matching the auth repository's own mapping. */
private fun Throwable.toAppError(): AppError =
    this as? AppError ?: AppError.Unknown(message ?: "unknown", code = "auth.claim_failed")

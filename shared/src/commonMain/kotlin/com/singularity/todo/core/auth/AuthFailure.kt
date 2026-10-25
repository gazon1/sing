package com.singularity.todo.core.auth

import com.singularity.todo.core.error.AppError

/**
 * What a failed authentication call means.
 *
 * ## Why these decisions live here and not in the vendor's file
 *
 * The messages and the codes are *this project's*: the dashboard groups the reports
 * by the code, the screens show the message, and a person decides what to do next
 * by reading them. The SDK knows how to call GoTrue; it does not know what
 * `auth.no_session` means here, and it will never learn — the code is ours.
 *
 * So the vocabulary lives here, in a file that does not import the SDK, and the seam
 * answers with it. A file that imports the SDK is a file whose decisions cannot be
 * read — or checked — without the SDK.
 *
 * ## The refresh is the one that had to be spelled out
 *
 * A failed refresh has two answers, and the caller acts differently on them: a
 * rejected token ends the session, anything else leaves it alone. The SDK's
 * vocabulary says *which* failure it was; this file says *what that means*.
 */
internal object AuthFailure {

    /**
     * No project is configured: there is nothing to sign in to.
     *
     * This is not a failure of the sign-in — it is a failure to have a place to sign
     * in to, and the message says what to do about it.
     */
    fun notConfigured(): AppError.Validation = AppError.Validation(
        "No Supabase project is configured, so there is nothing to sign in to.",
        code = "auth.not_configured",
    )

    /**
     * The provider accepted the call and left no session behind.
     *
     * The two are kept distinct in the message but not in the type, because the
     * caller cannot act differently on them: both mean there is no session, and both
     * must leave the store untouched.
     */
    fun noSession(what: String): AppError.Unauthorized = AppError.Unauthorized(
        "The identity provider accepted '$what' but left no session behind",
        code = "auth.no_session",
    )

    /**
     * The magic link was verified, but no session was issued for it.
     *
     * The link was good — the server says so — and there is still nothing to sign in
     * with. That is a different sentence from "the provider left no session behind",
     * and a person who has just clicked a link deserves to be told which of the two
     * happened.
     */
    fun verifiedWithoutSession(): AppError.Unauthorized = AppError.Unauthorized(
        "Magic link verified but no session was issued",
        code = "auth.no_session",
    )

    /**
     * What a failed refresh means for the session.
     *
     * Only a rejection ends it. A timeout, a lost connection or a server that is
     * briefly unwell leaves the session alone: signing the person out over a
     * network blip throws away a session that would have worked a second later.
     * REQ-UA-003 — a transport failure is not a verdict on the token.
     */
    fun refreshOutcome(reason: RefreshFailureReason): RefreshOutcome = when (reason) {
        RefreshFailureReason.Rejected -> RefreshOutcome.SessionIsGone
        RefreshFailureReason.Transient -> RefreshOutcome.KeepSession
    }
}

/**
 * Why a refresh failed.
 *
 * The SDK's vocabulary says *what* went wrong; this says whether the token is still
 * worth anything.
 */
internal enum class RefreshFailureReason {
    /** The server refused the refresh token: the session cannot be recovered. */
    Rejected,

    /** The request did not get an answer about the token: it is still worth trying. */
    Transient,
}

/**
 * What the session does after a failed refresh.
 */
internal enum class RefreshOutcome {
    /**
     * The token was rejected. The repository clears its credentials and the person
     * is signed out — keeping a dead token would mean every later sync fails with a
     * 401 that nothing ever clears.
     */
    SessionIsGone,

    /** The failure was not about the token. The stored session stays for the next attempt. */
    KeepSession,
}

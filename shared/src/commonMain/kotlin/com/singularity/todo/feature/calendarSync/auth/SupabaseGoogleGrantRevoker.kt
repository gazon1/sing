package com.singularity.todo.feature.calendarSync.auth

import com.singularity.todo.core.error.runCatchingCancellable

/**
 * The revoke over the project's own Edge Function.
 *
 * The server holds the refresh token, so the server is where the credential has
 * to die: the function deletes the grant row and revokes the token at Google, in
 * that order — if Google refuses, the row stays, because a deleted row with a live
 * token is a token nobody can cancel any more.
 *
 * ## Why the user id is not used
 *
 * The server resolves the caller from the session, and the row it deletes is the
 * caller's. The id names the profile whose credential the device is forgetting;
 * on the server the two are the same person by construction, and sending the id
 * would be sending something the server already knows.
 */
class SupabaseGoogleGrantRevoker(private val gateway: GoogleTokenGateway) : GoogleGrantRevoker {

    override suspend fun revoke(userId: String): Result<Unit> = runCatchingCancellable {
        gateway.revoke().getOrThrow()
    }
}

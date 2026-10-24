package com.singularity.todo.feature.calendarSync.auth

import com.singularity.todo.core.ids.UserId

/**
 * The access token the calendar calls are made with.
 *
 * ## Why this is not just the credential store
 *
 * An access token lasts about an hour, and a calendar sync has to run unattended —
 * which means the token has to be renewed, and *who* renews it depends on the
 * platform:
 *
 * - **Desktop** renews on the device: a Desktop OAuth client is a public client, so
 *   the refresh needs no secret, and the refresh token is in the device's own keystore.
 * - **Android** renews on the server: the refresh token lives there, next to the
 *   client secret that the renewal needs, and the device asks for a token.
 *
 * A caller that read the store directly would have to know which of the two it is
 * on — and the wrong answer is a token that is dead at the worst possible moment.
 */
interface GoogleAccessTokenProvider {
    /**
     * A token that is valid now.
     *
     * @return null when no account is connected, or when the grant is gone. The
     *   difference is the caller's business; this is the same answer for both.
     */
    suspend fun accessToken(userId: UserId): String?
}

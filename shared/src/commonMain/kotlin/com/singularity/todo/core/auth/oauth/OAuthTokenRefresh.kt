package com.singularity.todo.core.auth.oauth

import kotlinx.io.IOException
import kotlin.time.Clock

/**
 * Token refresh helpers following RFC 6749 §6.
 *
 * [EXPIRY_MARGIN_MS] applies a 60-second safety margin: a token is considered
 * expired this amount before its actual expiry time, so refresh is initiated
 * proactively rather than after the first request fails.
 *
 * Wall-clock time is taken from [Clock.System] by default; tests pass an
 * explicit `nowMs` instead of relying on the real system clock.
 */
object OAuthTokenRefresh {
    /** Refresh tokens are refreshed 60 s before expiry to avoid race conditions. */
    const val EXPIRY_MARGIN_MS = 60_000L

    /**
     * Returns `true` if [data] represents an expired token.
     *
     * If [data.expiresAt] is `0` (unknown expiry), returns [refreshWhenExpiryUnknown].
     * Otherwise returns `true` when `now > expiresAt - EXPIRY_MARGIN_MS`.
     */
    fun isExpired(
        data: OAuthTokenData,
        refreshWhenExpiryUnknown: Boolean,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean = if (data.expiresAt <= 0) {
        refreshWhenExpiryUnknown
    } else {
        nowMs > data.expiresAt - EXPIRY_MARGIN_MS
    }

    /**
     * Returns a new [OAuthTokenData] with the fields from [result] applied.
     * [result.expiresIn] is converted to an absolute expiry time.
     * The refresh token is only updated if [result] provides a new one.
     */
    fun OAuthTokenData.withRefreshResult(
        result: TasksOAuthClient.RefreshResult,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): OAuthTokenData = copy(
        accessToken = result.accessToken,
        refreshToken = result.refreshToken ?: refreshToken,
        expiresAt = result.expiresIn
            ?.let { nowMs + it * 1000 }
            ?: 0L,
    )

    /**
     * Executes [refresh] and wraps any non-IO exceptions in [IOException].
     * IOExceptions are re-thrown as-is so callers can distinguish transient
     * network errors from retryable auth errors.
     */
    inline fun refreshOrThrowIO(refresh: () -> TasksOAuthClient.RefreshResult): TasksOAuthClient.RefreshResult = try {
        refresh()
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e.message, e)
    }
}

/**
 * Placeholder for the result type produced by an OAuth token refresh call.
 * In a real implementation this would come from the HTTP client's response parser.
 * Defined here so [OAuthTokenRefresh] can be tested without a real HTTP stack.
 */
data class TasksOAuthClient(val httpClient: Any = Unit) {
    /**
     * Result of a token refresh call.
     *
     * @property accessToken     New bearer token.
     * @property expiresIn       New token lifetime in seconds (null if provider omits it).
     * @property refreshToken    New refresh token, if the provider rotates it (optional).
     */
    data class RefreshResult(val accessToken: String, val expiresIn: Long?, val refreshToken: String? = null)
}

package com.singularity.todo.core.auth.oauth

import com.singularity.todo.core.auth.oauth.OAuthTokenRefresh.withRefreshResult
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("fast")
class OAuthTokenRefreshTest {

    private companion object {
        val NOW = 1_700_000_000_000L
    }

    @Test
    fun `isExpired returns false when expiry is far in future`() {
        val data = tokenData(expiresAt = NOW + 120_000L)
        assertFalse(OAuthTokenRefresh.isExpired(data, refreshWhenExpiryUnknown = false, nowMs = NOW))
    }

    @Test
    fun `isExpired returns true when within margin`() {
        val data = tokenData(expiresAt = NOW + 30_000L) // less than 60 s margin
        assertTrue(OAuthTokenRefresh.isExpired(data, refreshWhenExpiryUnknown = false, nowMs = NOW))
    }

    @Test
    fun `isExpired boundary is strict - false exactly at margin, true 1ms after`() {
        val data = tokenData(expiresAt = NOW + OAuthTokenRefresh.EXPIRY_MARGIN_MS)
        assertFalse(OAuthTokenRefresh.isExpired(data, refreshWhenExpiryUnknown = false, nowMs = NOW))
        assertTrue(OAuthTokenRefresh.isExpired(data, refreshWhenExpiryUnknown = false, nowMs = NOW + 1))
    }

    @Test
    fun `isExpired returns refreshWhenExpiryUnknown when expiry is zero`() {
        val data = tokenData(expiresAt = 0L)
        assertTrue(OAuthTokenRefresh.isExpired(data, refreshWhenExpiryUnknown = true, nowMs = NOW))
        assertFalse(OAuthTokenRefresh.isExpired(data, refreshWhenExpiryUnknown = false, nowMs = NOW))
    }

    @Test
    fun `withRefreshResult updates accessToken and expiresAt`() {
        val original = tokenData(
            accessToken = "old_access",
            refreshToken = "refresh",
            expiresAt = NOW + 300_000L,
        )
        val result = TasksOAuthClient.RefreshResult(
            accessToken = "new_access",
            expiresIn = 3600L,
            refreshToken = "rotated_refresh",
        )

        val updated = original.withRefreshResult(result, nowMs = NOW)

        assertEquals("new_access", updated.accessToken)
        assertEquals("rotated_refresh", updated.refreshToken)
        assertEquals(NOW + 3_600_000L, updated.expiresAt)
    }

    @Test
    fun `withRefreshResult preserves refreshToken when not rotated`() {
        val original = tokenData(accessToken = "old", refreshToken = "same_refresh", expiresAt = NOW + 60_000L)
        val result = TasksOAuthClient.RefreshResult(accessToken = "new", expiresIn = 3600L, refreshToken = null)

        val updated = original.withRefreshResult(result, nowMs = NOW)

        assertEquals("same_refresh", updated.refreshToken, "refreshToken should be preserved when not rotated")
    }

    @Test
    fun `withRefreshResult zeroes expiresAt when expiresIn is null`() {
        val original = tokenData(accessToken = "old", refreshToken = "same", expiresAt = NOW + 60_000L)
        val result = TasksOAuthClient.RefreshResult(accessToken = "new", expiresIn = null, refreshToken = null)

        val updated = original.withRefreshResult(result, nowMs = NOW)

        assertEquals(0L, updated.expiresAt)
    }

    private fun tokenData(accessToken: String = "access", refreshToken: String = "refresh", expiresAt: Long = 0L) =
        OAuthTokenData(
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenEndpoint = "https://example.com/oauth",
            clientId = "client",
            expiresAt = expiresAt,
        )
}

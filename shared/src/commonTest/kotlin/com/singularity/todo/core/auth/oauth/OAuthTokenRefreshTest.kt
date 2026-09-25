package com.singularity.todo.core.auth.oauth

import com.singularity.todo.core.auth.oauth.OAuthTokenRefresh.withRefreshResult
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("slow")
class OAuthTokenRefreshTest {

    @Test
    fun `isExpired returns false when expiry is far in future`() {
        val data = tokenData(expiresAt = now() + 120_000L)
        assertFalse(OAuthTokenRefresh.isExpired(data, refreshWhenExpiryUnknown = false))
    }

    @Test
    fun `isExpired returns true when within margin`() {
        val data = tokenData(expiresAt = now() + 30_000L) // less than 60 s margin
        assertTrue(OAuthTokenRefresh.isExpired(data, refreshWhenExpiryUnknown = false))
    }

    @Test
    fun `isExpired returns refreshWhenExpiryUnknown when expiry is zero`() {
        val data = tokenData(expiresAt = 0L)
        assertTrue(OAuthTokenRefresh.isExpired(data, refreshWhenExpiryUnknown = true))
        assertFalse(OAuthTokenRefresh.isExpired(data, refreshWhenExpiryUnknown = false))
    }

    @Test
    fun `withRefreshResult updates accessToken and expiresAt`() {
        val original = tokenData(
            accessToken = "old_access",
            refreshToken = "refresh",
            expiresAt = now() + 300_000L,
        )
        val result = TasksOAuthClient.RefreshResult(
            accessToken = "new_access",
            expiresIn = 3600L,
            refreshToken = "rotated_refresh",
        )

        val updated = original.withRefreshResult(result)

        assertEquals("new_access", updated.accessToken)
        assertEquals("rotated_refresh", updated.refreshToken)
        assertEquals(now() + 3_600_000L, updated.expiresAt)
    }

    @Test
    fun `withRefreshResult preserves refreshToken when not rotated`() {
        val original = tokenData(accessToken = "old", refreshToken = "same_refresh", expiresAt = now() + 60_000L)
        val result = TasksOAuthClient.RefreshResult(accessToken = "new", expiresIn = 3600L, refreshToken = null)

        val updated = original.withRefreshResult(result)

        assertEquals("same_refresh", updated.refreshToken, "refreshToken should be preserved when not rotated")
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun tokenData(accessToken: String = "access", refreshToken: String = "refresh", expiresAt: Long = 0L) =
        OAuthTokenData(
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenEndpoint = "https://example.com/oauth",
            clientId = "client",
            expiresAt = expiresAt,
        )
}

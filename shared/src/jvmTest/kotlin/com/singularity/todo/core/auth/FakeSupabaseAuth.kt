@file:OptIn(
    io.github.jan.supabase.annotations.SupabaseExperimental::class,
    io.github.jan.supabase.annotations.SupabaseInternal::class,
)

package com.singularity.todo.core.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.SupabaseSerializer
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.AuthConfig
import io.github.jan.supabase.auth.CodeVerifierCache
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.OtpVerifyResult
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.SessionRefreshInformation
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.admin.AdminApi
import io.github.jan.supabase.auth.event.AuthEvent
import io.github.jan.supabase.auth.jwt.ClaimsRequestBuilder
import io.github.jan.supabase.auth.jwt.ClaimsResponse
import io.github.jan.supabase.auth.mfa.MfaApi
import io.github.jan.supabase.auth.oauth.OAuthApi
import io.github.jan.supabase.auth.passkey.AuthPasskeyApi
import io.github.jan.supabase.auth.providers.AuthProvider
import io.github.jan.supabase.auth.providers.ExternalAuthConfigDefaults
import io.github.jan.supabase.auth.providers.IDTokenProvider
import io.github.jan.supabase.auth.providers.OAuthProvider
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.providers.builtin.SSO
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.auth.user.UserUpdateBuilder
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.logging.SupabaseLogger
import io.ktor.client.statement.HttpResponse
import kotlin.reflect.KType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * The SDK's auth plugin, as a test double.
 *
 * ## Why a fake and not a mock
 *
 * The plugin is an **interface** with fifty-odd members, and the seam's own
 * behaviour — the magic link that must not create an account, the magic-link
 * exchange, the refresh that must be able to say "rejected" — lives exactly where a
 * mock would have to stand in for the provider. A fake that records what it was
 * asked and answers what the test scripted keeps those three claims provable
 * without a Supabase server.
 *
 * ## What it costs
 *
 * The interface changes when the SDK changes, and this file then does not compile —
 * which is the point. A silent change in the SDK's contract shows up as a build
 * failure here rather than as a behaviour difference on a user's device.
 *
 * Every member the seam never calls throws. A member that answers something
 * plausible instead would let a test pass against a seam that does not use it.
 */
class FakeSupabaseAuth(override val sessionManager: SessionManager) : Auth {

    /** The session the plugin currently holds. */
    var session: UserSession? = null

    /** What the plugin is given when it is handed a session. */
    var importSessions = mutableListOf<UserSession>()

    /** The answers the test scripts. */
    var verifyResult: OtpVerifyResult? = null
    var refreshResult: UserSession? = null
    var refreshFailure: Exception? = null

    /** What the plugin was asked to do. */
    var signInWithCalls = 0
    var lastOtpEmail: String? = null
    var lastOtpCreateUser: Boolean? = null
    var verifyCalls = 0
    var refreshCalls = 0
    var lastRefreshToken: String? = null
    var clearSessionCalls = 0

    private val status = MutableStateFlow<SessionStatus>(SessionStatus.NotAuthenticated(false))

    override val sessionStatus: StateFlow<SessionStatus> get() = status

    override val events: SharedFlow<AuthEvent> = MutableSharedFlow()

    override val isAutoRefreshRunning: Boolean get() = false

    override val config: AuthConfig = AuthConfig()

    override val supabaseClient: SupabaseClient get() = error("the fake plugin has no client")

    override val apiVersion: Int get() = 1

    override val pluginKey: String get() = "auth"

    override val logger: SupabaseLogger get() = error("the fake plugin does not log")

    override val serializer: SupabaseSerializer get() = FakeSerializer

    override val admin: AdminApi get() = error("the seam never reaches the admin API")

    override val mfa: MfaApi get() = error("the seam never reaches the multi-factor API")

    override val passkeys: AuthPasskeyApi get() = error("the seam never reaches the passkey API")

    override val oauth: OAuthApi get() = error("the seam never reaches the OAuth API")

    override val codeVerifierCache: CodeVerifierCache get() = error("the seam never reaches the code cache")

    override val authScope: CoroutineScope get() = error("the fake plugin has no scope")

    override suspend fun parseErrorResponse(response: HttpResponse): RestException =
        error("the fake plugin never parses an error response")

    override fun currentSessionOrNull(): UserSession? = session

    override suspend fun <C, R, Provider : AuthProvider<C, R>> signUpWith(
        provider: Provider,
        redirectUrl: String?,
        config: (C.() -> Unit)?,
    ): R? = error("the seam signs up through KMPAuth, not through the plugin")

    @Suppress("UNCHECKED_CAST")
    override suspend fun <C, R, Provider : AuthProvider<C, R>> signInWith(
        provider: Provider,
        redirectUrl: String?,
        config: (C.() -> Unit)?,
    ) {
        signInWithCalls++
        val otp = OTP.Config(FakeSerializer)
        (config as (OTP.Config.() -> Unit)?)?.invoke(otp)
        lastOtpEmail = otp.email
        lastOtpCreateUser = otp.createUser
    }

    override suspend fun signInAnonymously(data: JsonObject?, captchaToken: String?) =
        error("the seam never signs in anonymously")

    override suspend fun linkIdentity(
        provider: OAuthProvider,
        redirectUrl: String?,
        config: ExternalAuthConfigDefaults.() -> Unit,
    ): String? = error("the seam never links an identity")

    override suspend fun linkIdentityWithIdToken(
        provider: IDTokenProvider,
        idToken: String,
        config: (IDToken.Config.() -> Unit),
    ) = error("the seam never links an identity")

    override suspend fun unlinkIdentity(identityId: String, updateLocalUser: Boolean) =
        error("the seam never unlinks an identity")

    override suspend fun retrieveSSOUrl(
        redirectUrl: String?,
        config: SSO.Config.() -> Unit,
    ): SSO.Result = error("the seam never reaches SSO")

    override suspend fun updateUser(
        updateCurrentUser: Boolean,
        redirectUrl: String?,
        config: UserUpdateBuilder.() -> Unit,
    ): UserInfo = error("the seam never updates the user")

    override suspend fun resendEmail(
        type: OtpType.Email,
        email: String,
        captchaToken: String?,
        redirectUrl: String?,
    ) = error("the seam never resends an email")

    override suspend fun resendPhone(type: OtpType.Phone, phone: String, captchaToken: String?) =
        error("the seam never resends a phone code")

    override suspend fun resetPasswordForEmail(email: String, redirectUrl: String?, captchaToken: String?) =
        error("the seam never resets a password by email")

    override suspend fun reauthenticate() = error("the seam never reauthenticates")

    override suspend fun verifyEmailOtp(
        type: OtpType.Email,
        email: String,
        token: String,
        captchaToken: String?,
    ): OtpVerifyResult {
        verifyCalls++
        return verifyResult ?: error("the test did not script a verification answer")
    }

    override suspend fun verifyEmailOtp(
        type: OtpType.Email,
        tokenHash: String,
        captchaToken: String?,
    ): OtpVerifyResult = error("the app's magic link carries a token, not a hash")

    override suspend fun verifyPhoneOtp(
        type: OtpType.Phone,
        phone: String,
        token: String,
        captchaToken: String?,
    ) = error("the seam never verifies a phone code")

    override suspend fun retrieveUser(jwt: String): UserInfo = error("the seam never retrieves a user")

    override suspend fun retrieveUserForCurrentSession(updateSession: Boolean): UserInfo =
        error("the seam never retrieves a user")

    override suspend fun signOut(scope: SignOutScope) = error("the seam signs out through KMPAuth")

    override suspend fun importSession(session: UserSession, autoRefresh: Boolean, source: SessionSource) {
        importSessions += session
        this.session = session
        status.value = SessionStatus.Authenticated(session, source)
    }

    override suspend fun loadFromStorage(autoRefresh: Boolean): Boolean = error("the seam loads the store itself")

    override suspend fun refreshSession(refreshToken: String): UserSession {
        refreshCalls++
        lastRefreshToken = refreshToken
        refreshFailure?.let { throw it }
        return refreshResult ?: error("the test did not script a refresh answer")
    }

    override suspend fun refreshCurrentSession() = error("the seam refreshes with an explicit token")

    override suspend fun clearSession() {
        clearSessionCalls++
        session = null
        status.value = SessionStatus.NotAuthenticated(false)
    }

    override fun setSessionStatus(status: SessionStatus) {
        this.status.value = status
    }

    override fun emitEvent(event: AuthEvent) = error("the fake plugin emits nothing")

    override suspend fun exchangeCodeForSession(code: String, saveSession: Boolean): UserSession =
        error("the app's magic link carries a token, not a code")

    override suspend fun startAutoRefreshForCurrentSession() = error("the seam never starts the auto-refresh")

    override suspend fun getClaims(
        jwt: String?,
        options: ClaimsRequestBuilder.() -> Unit,
    ): ClaimsResponse = error("the seam never reads claims")

    override fun getOAuthUrl(
        provider: OAuthProvider,
        redirectUrl: String?,
        url: String,
        additionalConfig: ExternalAuthConfigDefaults.() -> Unit,
    ): String = error("the seam never builds an OAuth URL")

    override fun stopAutoRefreshForCurrentSession() = error("the seam never starts the auto-refresh")

    override fun autoRefreshInformation(): SessionRefreshInformation? = null

    override suspend fun awaitInitialization() = Unit

    private object FakeSerializer : SupabaseSerializer {
        override fun <T> encode(type: KType, value: T): String = error("the fake plugin encodes nothing")

        override fun <T> decode(type: KType, value: String): T = error("the fake plugin decodes nothing")
    }
}

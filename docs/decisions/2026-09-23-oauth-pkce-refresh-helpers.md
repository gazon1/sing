---
title: "OAuth building blocks: PKCE, OAuthTokenRefresh, IdToken (no-op SupabaseAuthRepository)"
date: 2026-09-23
tags: [auth, oauth, security, pkce]
status: accepted
---

## Context

`SupabaseAuthRepository` in `core/auth/` is a stub — `signUp`/`signIn` return `Result.success(Unit)` without any network call. `Session` holds `accessToken` and `refreshToken`, but there is no PKCE helper, no token refresh logic, and no JWT parsing. Tasks.org has clean, self-contained helpers for all three that work with any OAuth 2.0 provider (Google, Apple, generic OIDC).

The decision is: implement the pure building blocks **without** connecting them to `SupabaseAuthRepository`. The stub remains a stub. The helpers are ready for when real Supabase OAuth calls are wired in.

## Idea

Take from Tasks.app only the pure, transport-agnostic helpers:

1. **PKCE** (`core/auth/oauth/PKCE.kt`): `generateVerifier()` + `generateChallenge(verifier)`, `expect/actual secureRandomBytes` for Android/JVM.
2. **OAuthTokenRefresh** (`core/auth/oauth/OAuthTokenRefresh.kt`): `isExpired(token, refreshWhenUnknown)`, `withRefreshResult()`, `refreshOrThrowIO()`.
3. **IdToken** (`core/auth/oauth/IdToken.kt`): parse JWT payload, extract `email`, `sub`, `preferredUsername`, `login`.
4. **OAuth data model** (`core/auth/oauth/OAuth.kt`): `OAuthConfig`, `OAuthResult`, `OAuthTokenData` (Serializable), `TokenError`, `RedirectState`, `toOAuthTokenData()` extension.

`TasksOAuthClient` from Tasks.app is **not** copied — it uses Ktor; the real Supabase OAuth flow will use the `auth.kt` SDK (already in dependencies) or direct OkHttp calls.

## Decision

### PKCE

```kotlin
// commonMain
object PKCE {
    fun generateVerifier(): String  // 32 random bytes, base64url, no padding
    fun generateChallenge(verifier: String): String  // SHA256 → base64url
}
internal expect fun secureRandomBytes(count: Int): ByteArray

// androidMain / jvmMain: SecureRandom.getInstance("NativePRNGNonBlocking").nextBytes(bytes)
```

### OAuthTokenRefresh

```kotlin
object OAuthTokenRefresh {
    const val EXPIRY_MARGIN_MS = 60_000L  // refresh 60 s before expiry
    fun isExpired(data: OAuthTokenData, refreshWhenExpiryUnknown: Boolean): Boolean
    fun OAuthTokenData.withRefreshResult(result: RefreshResult): OAuthTokenData
    inline fun refreshOrThrowIO(refresh: () → RefreshResult): RefreshResult
}
```

### IdToken

```kotlin
class IdToken(jwt: String) {
    val email: String?
    val sub: String?
    val preferredUsername: String?
    val login: String?
}
```

Parses JWT payload via `Base64.UrlSafe.decode(parts[1])` + `kotlinx.serialization.json.Json`.

### OAuth data model

All in `core/auth/oauth/OAuth.kt`:

```kotlin
data class OAuthConfig(authorizationEndpoint, tokenEndpoint, clientId, redirectUri, scope, state = "")
data class OAuthResult(accessToken, idToken, refreshToken, tokenEndpoint, clientId, expiresIn, grantedScopes)
@Serializable data class OAuthTokenData(accessToken, refreshToken, tokenEndpoint, clientId, expiresAt) {
    fun serialize(): String
    companion object { fun deserialize(data: String): OAuthTokenData }
}
object TokenError { const val REFRESH_FAILED; const val EXCHANGE_FAILED }
object RedirectState { fun encode(nonce, redirectUri): String }  // Base64URL(JSON)
fun OAuthResult.toOAuthTokenData(refreshToken: String): OAuthTokenData
```

## Rationale

- **Pure helpers only**: no network calls, no HTTP client, no Supabase SDK. Each helper is independently testable (unit tests: PKCETest, OAuthTokenRefreshTest, IdTokenTest).
- **`expect/actual` for SecureRandom**: crypto-grade random is platform-specific. The expect/actual pattern is already established in the project for `Clock` and `logDirectory`.
- **`OAuthTokenData` is `@Serializable`**: tokens are stored in `SessionStore` (DataStore). Serialization enables future `SessionStore` to persist them as a structured blob rather than loose strings.
- **No connection to `SupabaseAuthRepository`**: deliberately deferred. The stub stays a stub. Connecting PKCE/OAuthTokenRefresh to Supabase involves real async flows, error handling, and Supabase SDK integration — too large for this PR.

## Consequences

- `core/auth/SupabaseAuthRepository` remains a stub. A follow-up ADR will define the connection contract.
- `SecureStoragePort` (already in the codebase) is the right place to store `OAuthTokenData.serialize()` — `SecureStoragePort.write(KEY_OAUTH_TOKEN, tokenData.serialize())`. This is noted for the follow-up ADR.
- `RedirectState.encode` is useful for OAuth2 authorization code flow with state parameter — the nonce prevents CSRF. The redirect state encoding (Base64URL of JSON) matches the OIDC `state` parameter convention.
- `IdToken` parses but does **not** validate signatures. Signature verification requires the JWKS endpoint and is provider-specific — out of scope for this ADR.

## Links

- `shared/src/commonMain/.../core/auth/oauth/PKCE.kt`
- `shared/src/commonMain/.../core/auth/oauth/SecureRandomBytes.kt`
- `shared/src/androidMain/.../core/auth/oauth/SecureRandomBytes.android.kt`
- `shared/src/jvmMain/.../core/auth/oauth/SecureRandomBytes.jvm.kt`
- `shared/src/commonMain/.../core/auth/oauth/OAuthTokenRefresh.kt`
- `shared/src/commonMain/.../core/auth/oauth/IdToken.kt`
- `shared/src/commonMain/.../core/auth/oauth/OAuth.kt`

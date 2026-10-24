package com.singularity.todo.feature.calendarSync.auth

import android.app.PendingIntent
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.mmk.kmpauth.core.SignInState
import com.mmk.kmpauth.google.GoogleAuthCredentials
import com.mmk.kmpauth.google.GoogleAuthProvider
import com.mmk.kmpauth.google.rememberGoogleSignInState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Google consent on Android.
 *
 * ## What happens, in two steps
 *
 * 1. **Which account?** KMPAuth asks the Credential Manager, and the user picks the
 *    Google account. This is the part that is genuinely hard to do well: the account
 *    chooser, the "no Google account on this device" and "Play services are out of
 *    date" failures, the cancellation semantics — a home-grown implementation gets
 *    those wrong quietly, and the library has them right.
 * 2. **Which scopes?** The app asks for the calendar scope through Google's
 *    authorization API, in *offline* mode. That is the only mode that yields a durable
 *    grant: it hands back a one-time code the server redeems, and the refresh token
 *    behind it never leaves the server.
 *
 * ## Why the app asks for the scope itself
 *
 * KMPAuth can ask for the scope too — it would then fetch a client-side access token
 * with its own consent prompt. That token is not what this feature needs: it lasts
 * about an hour, it cannot be renewed on the device, and the background sync has to
 * run with nobody present. Asking here instead means one consent screen and a grant
 * the server can renew.
 *
 * ## Why there is no redirect URI
 *
 * Google blocked the loopback flow for Android OAuth clients in October 2022, and
 * custom URI schemes are not supported either. The authorization API used here needs
 * neither: Play Services carries the answer back itself.
 */
@Composable
actual fun rememberGoogleConsent(clientId: String?): GoogleConsent {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentClientId by rememberUpdatedState(clientId)
    val currentScopes by rememberUpdatedState(DEFAULT_SCOPES)
    var pending by remember { mutableStateOf<PendingAuthorization?>(null) }

    // The launcher that carries the consent sheet's answer back.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val waiting = pending
        val data = result.data
        if (waiting == null) return@rememberLauncherForActivityResult
        if (data == null) {
            waiting.fail("The consent sheet came back without an answer")
            return@rememberLauncherForActivityResult
        }
        val authorizationResult = Identity.getAuthorizationClient(context)
            .getAuthorizationResultFromIntent(data)
        val code = authorizationResult.serverAuthCode
        if (code.isNullOrBlank()) {
            waiting.fail("Google did not return a code the server could redeem")
        } else {
            waiting.complete(code)
        }
    }

    // KMPAuth's provider is process-wide and the first creation wins, so the client id
    // is fixed for the lifetime of the process. Creating it here — before the sign-in
    // state is composed, which reads it — is what the library requires.
    val signIn: SignInState? = if (clientId != null) {
        GoogleProviderRegistry.create(clientId)
        rememberGoogleSignInState(
            // Exactly KMPAuth's basic scope, and exactly that: the library asks for an
            // access token — with a second consent screen of its own — whenever the
            // scopes are anything other than the basic pair. The app asks for the
            // calendar scope itself, once, through Google's authorization API.
            scopes = listOf("email", "profile"),
            requestAccessToken = false,
            onResult = { result ->
                val waiting = pending
                result
                    .onSuccess {
                        if (waiting == null) return@onSuccess
                        requestOfflineAuthorization(
                            context = context,
                            clientId = currentClientId,
                            scopes = currentScopes,
                            onResolution = { pendingIntent ->
                                launcher.launch(
                                    IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                                )
                            },
                            onAlreadyAuthorized = { code -> waiting.complete(code) },
                            onFailure = { reason -> waiting.fail(reason) },
                        )
                    }
                    .onFailure { error ->
                        waiting?.fail(error.message ?: "The Google sign-in did not finish")
                    }
            },
        )
    } else {
        null
    }

    return remember(clientId) {
        AndroidGoogleConsent(
            signIn = signIn,
            scope = scope,
            isStale = {
                GoogleProviderRegistry.clientId != null &&
                    GoogleProviderRegistry.clientId != currentClientId
            },
            startPending = {
                PendingAuthorization().also { pending = it }
            },
        )
    }
}

/**
 * The client id the process-wide KMPAuth provider was created with.
 *
 * KMPAuth creates its provider once per process and the first creation wins: a second
 * `create` call returns the provider that already exists, with the client id it was
 * first given. The id therefore has to be remembered outside the composition — a screen
 * that is disposed and composed again would otherwise forget which id the provider
 * carries and would sign in against it without a word.
 */
private object GoogleProviderRegistry {
    /** The id the provider carries; null until the provider is created. */
    var clientId: String? = null

    /** Creates the provider, or returns the one that already exists. */
    fun create(clientId: String) {
        GoogleAuthProvider.create(GoogleAuthCredentials(serverId = clientId))
        if (this.clientId == null) this.clientId = clientId
    }
}

/**
 * The consent, as the settings screen drives it.
 */
private class AndroidGoogleConsent(
    private val signIn: SignInState?,
    private val scope: CoroutineScope,
    private val isStale: () -> Boolean,
    private val startPending: () -> PendingAuthorization,
) : GoogleConsent {

    override val isSupported: Boolean get() = signIn != null

    override fun authorize(
        clientId: String,
        scopes: List<String>,
        expectedState: String,
        buildUrl: (redirectUri: String) -> String,
        onResult: (Result<GoogleGrant>) -> Unit,
    ) {
        val state = signIn
        if (state == null) {
            onResult(
                Result.failure(
                    GoogleAuthorizationException(
                        "No Google client id is configured, so there is nothing to authorize against",
                    ),
                ),
            )
            return
        }
        if (isStale()) {
            // The provider was created with a different client id, and the first
            // creation wins. Saying so here is better than a sign-in that goes out
            // against the old id and fails at Google with a message that names
            // neither the setting nor the restart.
            onResult(
                Result.failure(
                    GoogleAuthorizationException(
                        "The Google client id changed. Restart the app to sign in with the new one.",
                    ),
                ),
            )
            return
        }
        val waiting = startPending()
        scope.launch {
            onResult(waiting.await())
        }
        state.launch()
    }
}

/**
 * The consent that is under way.
 *
 * The deferred is what connects the platform's callbacks — the credential manager's
 * result, the activity result — to the one call that started them. Without it the two
 * halves would have to be wired to each other by hand, and a path that forgets to
 * answer would leave the button spinning forever.
 */
private class PendingAuthorization {
    private val deferred = CompletableDeferred<Result<GoogleGrant>>()

    /** The result of the consent, for whoever started it. */
    suspend fun await(): Result<GoogleGrant> = deferred.await()

    /** The consent produced a code. */
    fun complete(code: String) {
        deferred.complete(Result.success(GoogleGrant.ServerAuthCode(code)))
    }

    /** The consent did not produce a code. */
    fun fail(message: String) {
        deferred.complete(Result.failure(GoogleAuthorizationException(message)))
    }
}

/**
 * Asks Google for the scopes, in offline mode.
 *
 * @param onResolution called with the pending intent when the consent sheet has to be
 *   shown; the launcher carries it.
 * @param onAlreadyAuthorized called with the code when the grant was already in place
 *   and no sheet was needed.
 * @param onFailure called with the reason the request could not be made.
 */
private fun requestOfflineAuthorization(
    context: Context,
    clientId: String?,
    scopes: List<String>,
    onResolution: (PendingIntent) -> Unit,
    onAlreadyAuthorized: (String) -> Unit,
    onFailure: (String) -> Unit,
) {
    val id = clientId
    if (id.isNullOrBlank()) {
        onFailure("No Google client id is configured, so there is nothing to authorize against")
        return
    }
    val request = AuthorizationRequest.builder()
        .setRequestedScopes(scopes.map(::Scope))
        .requestOfflineAccess(id)
        .build()
    Identity.getAuthorizationClient(context).authorize(request)
        .addOnSuccessListener { result ->
            if (result.hasResolution()) {
                val pendingIntent = result.pendingIntent
                if (pendingIntent == null) {
                    onFailure("Google asked for a consent screen and then did not provide one")
                } else {
                    onResolution(pendingIntent)
                }
            } else {
                val code = result.serverAuthCode
                if (code.isNullOrBlank()) {
                    onFailure("Google did not return a code the server could redeem")
                } else {
                    onAlreadyAuthorized(code)
                }
            }
        }
        .addOnFailureListener { error ->
            onFailure(error.message ?: "Google refused the authorization request")
        }
}

/** The scopes the calendar sync asks for. */
private val DEFAULT_SCOPES = listOf("https://www.googleapis.com/auth/calendar")

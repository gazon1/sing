@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.auth

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.SecureStorage
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.auth.SupabaseClientProvider
import com.singularity.todo.core.auth.SupabaseConfigResolver
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The login screen telling the user their session could not be read back.
 *
 * ## Why this needed its own test
 *
 * A restore that fails and a device that simply has no session both end at
 * [Session.SignedOut], and the login screen renders identically for both. A user who was
 * signed in yesterday and cannot be today therefore gets a screen that claims nothing was
 * ever there — which is false, and is the version of the bug that survives the fix that made
 * the app start at all.
 *
 * The repository publishes [AuthRepository.restoreFailures] for exactly this; the assertion
 * here is that the screen is told.
 */
@Tag("fast")
class AuthRestoreFailureUiTest {

    private val sessions = MutableStateFlow<Session>(Session.SignedOut)

    // Named `failures` rather than `restoreFailures`: an `override` whose name matches
    // the property it delegates to sends the compiler into the recursive-inference path
    // it reports as "recursive problem", which costs a line here and a lot of confusion
    // the first time someone hits it.
    private val failures = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    private val repository = object : AuthRepository {
        override val currentSession = sessions.asStateFlow()
        override val isLoading = MutableStateFlow(false)
        override val restoreFailures: SharedFlow<Unit> = failures.asSharedFlow()

        override suspend fun signIn(email: String, password: String): Result<Unit> = unused()
        override suspend fun signUp(email: String, password: String): Result<Unit> = unused()
        override suspend fun startLocalSession(): Result<Unit> = unused()
        override suspend fun signOut(): Result<Unit> = unused()
        override suspend fun updatePassword(newPassword: String): Result<Unit> = unused()
        override suspend fun sendMagicLink(email: String): Result<Unit> = unused()
        override suspend fun verifyMagicLink(email: String, token: String): Result<Unit> = unused()

        // A failure rather than a success: reaching one of these would mean the test's
        // understanding of the screen is wrong, and a passing sign-in would hide that.
        private fun unused(): Result<Unit> = Result.failure(UnusedOperation)
    }

    private object UnusedOperation : Throwable("this test never attempts an operation")

    /** No server configured — the point of the fixture, and what `client()` should return. */
    private class EmptyStorage : SecureStorage {
        override suspend fun read(key: String): String? = null
        override suspend fun write(key: String, value: String) = Unit
        override suspend fun delete(key: String) = Unit
    }

    private object FixedIds : IdGenerator {
        override fun next(): String = "dev-id"
    }

    private fun TestScope.viewModel() = AuthViewModel(
        authRepository = repository,
        clients = SupabaseClientProvider(
            resolver = SupabaseConfigResolver(idGenerator = FixedIds),
            store = EmptyStorage(),
        ),
        // `backgroundScope`, not the test scope. `AuthViewModel.init` starts collectors
        // that never finish — the session, the server config, the restore-failure event —
        // and `runTest` waits for every foreground coroutine on its scope. Handing it the
        // test scope therefore fails each test the same way: a minute of waiting, then
        // `UncompletedCoroutinesError`, whatever the assertions underneath said.
        //
        // The collector in each test below stays on the test scope deliberately: it has to
        // be subscribed before the emission, and a background coroutine is not guaranteed
        // to have been scheduled by then. So it is cancelled in a `finally` instead.
        scope = AutoCloseableCoroutineScope(backgroundScope.coroutineContext),
    )

    @Test
    fun `a failed restore reaches the screen as a message`() = runTest {
        // The emission happens before the view model is built, deliberately: that is the
        // production order. The repository is a startup singleton and restores long before
        // the login screen composes, so a view model that only listened from its own
        // construction would never hear about the failure it is meant to explain.
        failures.tryEmit(Unit)

        val viewModel = viewModel()
        val events = mutableListOf<AuthUiEvent>()
        // `backgroundScope`, so the collector is not a foreground coroutine `runTest`
        // would then wait on — it never completes, by design.
        backgroundScope.launch { viewModel.events.collect { events += it } }
        runCurrent()
        advanceUntilIdle()

        val message = events.filterIsInstance<AuthUiEvent.Message>().singleOrNull()
        assertEquals(
            RESTORE_FAILED_MESSAGE,
            message?.message,
            "a restore that failed has to be visible, not folded into a plain sign-out",
        )
    }

    @Test
    fun `a device with no session says nothing`() = runTest {
        // The other half, and the one that keeps the message worth reading when it does
        // appear: silence on a genuine first launch is what makes it diagnostic.
        val viewModel = viewModel()
        val events = mutableListOf<AuthUiEvent>()
        backgroundScope.launch { viewModel.events.collect { events += it } }
        advanceUntilIdle()

        assertEquals(
            emptyList(),
            events.toList(),
            "nothing failed, so there is nothing to report",
        )
    }

    @Test
    fun `the message points at the way back in`() = runTest {
        // "Continue Offline" is the button that recovers a local session, so a message that
        // names the problem without naming the way out leaves a diagnosis and no action.
        failures.tryEmit(Unit)

        val viewModel = viewModel()
        val events = mutableListOf<AuthUiEvent>()
        backgroundScope.launch { viewModel.events.collect { events += it } }
        runCurrent()
        advanceUntilIdle()

        val message = events.filterIsInstance<AuthUiEvent.Message>().single().message
        assertTrue(
            message.contains("sign in", ignoreCase = true),
            "the message has to offer a way forward, got: $message",
        )
    }

    @Test
    fun `a session that restored normally is not reported`() = runTest {
        sessions.value = Session.Anonymous(UserId.fromString("owner-1"))
        val viewModel = viewModel()
        val events = mutableListOf<AuthUiEvent>()
        backgroundScope.launch { viewModel.events.collect { events += it } }
        advanceUntilIdle()

        assertEquals(emptyList(), events.toList())
    }
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.auth

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.auth.SupabaseClientProvider
import com.singularity.todo.core.auth.SupabaseConfigResolver
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.auth.SecureStorage
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.helpers.awaitState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sign-in screen's two new responsibilities: naming the server, and seeding the
 * account's data on first entry.
 *
 * ## Why the "no server" state has to come first
 *
 * A sign-in with no project configured does not fail as a configuration problem.
 * It fails as a network error, the user reads that as a rejected password, and
 * retypes it. Putting the configuration form first is the difference between "here
 * is where your server is" and "your password is wrong", and only one of those is
 * true.
 */
@Tag("fast")
class AuthViewModelTest {

    private val storage = MapStorage()
    private val sessions = MutableStateFlow<Session>(Session.SignedOut)
    private var seedCalls = 0

    private fun TestScope.viewModel(
        signIn: Result<Unit> = Result.success(Unit),
        signUp: Result<Unit> = Result.success(Unit),
        signOut: Result<Unit> = Result.success(Unit),
        /**
         * The session left behind by a successful attempt.
         *
         * Signed-in by default, and that default matters: a fake that returns success
         * while leaving the session signed out is indistinguishable from the one
         * outcome that is *not* a completed sign-in — an account created with an
         * address still to be confirmed — so a view model that treated every success
         * as entry would pass here.
         */
        sessionAfterSuccess: Session = Session.SignedIn(
            userId = UserId.generate(),
            email = "a@b.c",
            accessToken = "access",
            refreshToken = "refresh",
        ),
    ): AuthViewModel {
        val provider = SupabaseClientProvider(
            resolver = SupabaseConfigResolver(idGenerator = FixedIds()),
            store = storage,
        )
        val repository = object : AuthRepository {
            override val currentSession = sessions.asStateFlow()
            override val isLoading = MutableStateFlow(false)
            override suspend fun signIn(email: String, password: String): Result<Unit> {
                if (signIn.isSuccess) sessions.value = sessionAfterSuccess
                return signIn
            }

            override suspend fun signUp(email: String, password: String): Result<Unit> {
                if (signUp.isSuccess) sessions.value = sessionAfterSuccess
                return signUp
            }

            override suspend fun signInAnonymously() = Result.success(Unit)

            /**
             * A sign-out that fails leaves the session standing.
             *
             * That is the shape that made the dropped failure expensive: the user is
             * told nothing, walks away from the screen believing the account is closed,
             * and the session it should have ended is still on the device.
             */
            override suspend fun signOut(): Result<Unit> {
                if (signOut.isSuccess) sessions.value = Session.SignedOut
                return signOut
            }

            override suspend fun migrateAnonymousTo(email: String, password: String) = Result.success(Unit)
        }
        return AuthViewModel(
            authRepository = repository,
            clients = provider,
            onFirstSignIn = { seedCalls++ },
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )
    }

    // ── Naming the server ───────────────────────────────────────────────────

    @Test
    fun `with nothing configured the screen asks for the server first`() = runTest {
        val viewModel = viewModel()

        awaitState { viewModel.stateFlow.value is AuthUiState.SignedOutWithNoServer }
        assertIs<AuthUiState.SignedOutWithNoServer>(viewModel.stateFlow.value)
    }

    @Test
    fun `a stored configuration puts the credential form first`() = runTest {
        storage.values[SupabaseConfigResolver.KEY_URL] = "https://project.supabase.co"
        storage.values[SupabaseConfigResolver.KEY_ANON_KEY] = "anon-key"

        val viewModel = viewModel()

        awaitState { viewModel.stateFlow.value is AuthUiState.Idle }
        assertIs<AuthUiState.Idle>(viewModel.stateFlow.value)
    }

    @Test
    fun `saving a configuration stores it and reveals the credential form`() = runTest {
        val viewModel = viewModel()
        awaitState { viewModel.stateFlow.value is AuthUiState.SignedOutWithNoServer }

        viewModel.onIntent(AuthIntent.SaveServerConfig("https://p.supabase.co", "anon-key"))
        awaitState { storage.values.containsKey(SupabaseConfigResolver.KEY_ANON_KEY) }

        assertEquals("https://p.supabase.co", storage.values[SupabaseConfigResolver.KEY_URL])
        assertEquals("anon-key", storage.values[SupabaseConfigResolver.KEY_ANON_KEY])
        awaitState { viewModel.stateFlow.value is AuthUiState.Idle }
    }

    @Test
    fun `a half-typed configuration is refused and names what is missing`() = runTest {
        val viewModel = viewModel()
        awaitState { viewModel.stateFlow.value is AuthUiState.SignedOutWithNoServer }

        viewModel.onIntent(AuthIntent.SaveServerConfig("https://p.supabase.co", "  "))

        // A stored URL with no key is indistinguishable from "not configured" until
        // the first request fails, and that failure arrives as a network error that
        // points at the wrong thing entirely.
        assertTrue(storage.values.isEmpty(), "a half-configuration must not be stored")
        assertEquals(AuthUiState.SignedOutWithNoServer, viewModel.stateFlow.value)
    }

    // ── Seeding on first entry ──────────────────────────────────────────────

    @Test
    fun `a successful sign-in runs the seed`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(AuthIntent.SignIn("a@b.c", "password123"))
        awaitState { viewModel.stateFlow.value is AuthUiState.Success }

        assertEquals(1, seedCalls)
    }

    @Test
    fun `a successful sign-up runs the seed`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(AuthIntent.SignUp("a@b.c", "password123"))
        awaitState { viewModel.stateFlow.value is AuthUiState.Success }

        assertEquals(1, seedCalls)
    }

    @Test
    fun `a failed sign-in does not seed`() = runTest {
        val viewModel = viewModel(signIn = Result.failure(AppError.Unauthorized("nope")))

        viewModel.onIntent(AuthIntent.SignIn("a@b.c", "password123"))
        awaitState { viewModel.stateFlow.value is AuthUiState.Idle }

        assertEquals(0, seedCalls, "uploading data on behalf of a sign-in that did not happen")
    }

    @Test
    fun `the pending state is cleared after a failure`() = runTest {
        val viewModel = viewModel(signIn = Result.failure(AppError.Unauthorized("nope")))

        viewModel.onIntent(AuthIntent.SignIn("a@b.c", "password123"))

        awaitState { viewModel.stateFlow.value !is AuthUiState.Loading }
        assertTrue(viewModel.stateFlow.value is AuthUiState.Idle)
    }

    @Test
    fun `a seed that fails does not prevent the sign-in`() = runTest {
        val provider = SupabaseClientProvider(SupabaseConfigResolver(FixedIds()), storage)
        val repository = object : AuthRepository {
            override val currentSession = sessions.asStateFlow()
            override val isLoading = MutableStateFlow(false)
            override suspend fun signIn(email: String, password: String): Result<Unit> {
                sessions.value = Session.SignedIn(UserId.generate(), "a@b.c", "access", "refresh")
                return Result.success(Unit)
            }

            override suspend fun signUp(email: String, password: String) = Result.success(Unit)
            override suspend fun signInAnonymously() = Result.success(Unit)
            override suspend fun signOut() = Result.success(Unit)
            override suspend fun migrateAnonymousTo(email: String, password: String) = Result.success(Unit)
        }
        val viewModel = AuthViewModel(
            authRepository = repository,
            clients = provider,
            onFirstSignIn = { error("the queue is full") },
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )

        viewModel.onIntent(AuthIntent.SignIn("a@b.c", "password123"))

        // The user is signed in. A seeding problem is a sync problem, and reporting
        // it as a failed sign-in would send them round the password loop again for
        // something that has already succeeded.
        awaitState { viewModel.stateFlow.value is AuthUiState.Success }
    }

    // ── REQ-UA-014 — a sign-up with no session is not a completed sign-in ────

    @Test
    fun `a sign-up awaiting confirmation does not enter the application`() = runTest {
        val viewModel = viewModel(sessionAfterSuccess = Session.SignedOut)

        viewModel.onIntent(AuthIntent.SignUp("a@b.c", "password123"))
        awaitState { viewModel.stateFlow.value is AuthUiState.AwaitingEmailConfirmation }

        assertIs<AuthUiState.AwaitingEmailConfirmation>(viewModel.stateFlow.value)
    }

    @Test
    fun `a sign-up awaiting confirmation emits no navigation`() = runTest {
        // The user landed inside the app with no session: a task list that cannot be
        // saved, and nothing on screen that says why.
        val viewModel = viewModel(sessionAfterSuccess = Session.SignedOut)
        val events = mutableListOf<AuthUiEvent>()
        // A foreground `launch` on the test scope, not `backgroundScope`: the
        // collector has to be subscribed before `onIntent`, and a background coroutine
        // is not guaranteed to have been scheduled by the time the intent runs.
        // A foreground `launch` on the test scope, not `backgroundScope`: the
        // collector has to be subscribed before `onIntent`, and a background coroutine
        // is not guaranteed to have been scheduled by the time the intent runs.
        //
        // The `finally` is not decoration. `runTest` waits for every foreground
        // coroutine, so a collector left running by a failed assertion turns a red
        // test into a hung build — and a hang hides the failure it was reporting.
        val collector = launch { viewModel.events.collect { events += it } }
        try {
            runCurrent()

            viewModel.onIntent(AuthIntent.SignUp("a@b.c", "password123"))
            awaitState { viewModel.stateFlow.value is AuthUiState.AwaitingEmailConfirmation }
            runCurrent()

            assertTrue(
                events.filterIsInstance<AuthUiEvent.Message>().isNotEmpty(),
                "the collector saw nothing at all, so 'no navigation' would pass for the wrong reason: $events",
            )
            assertTrue(
                events.none { it is AuthUiEvent.NavigateToHome },
                "navigating on a session-less success put the user in with no session: $events",
            )
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `a sign-up awaiting confirmation does not seed`() = runTest {
        // Seeding runs on behalf of an account. With no session there is no account to
        // seed for, and the rows it would upload have no owner to be attributed to.
        val viewModel = viewModel(sessionAfterSuccess = Session.SignedOut)

        viewModel.onIntent(AuthIntent.SignUp("a@b.c", "password123"))
        awaitState { viewModel.stateFlow.value is AuthUiState.AwaitingEmailConfirmation }

        assertEquals(0, seedCalls)
    }

    @Test
    fun `a sign-up awaiting confirmation says what to do, and not as an error`() = runTest {
        val viewModel = viewModel(sessionAfterSuccess = Session.SignedOut)
        val events = mutableListOf<AuthUiEvent>()
        val collector = launch { viewModel.events.collect { events += it } }
        try {
            runCurrent()

            viewModel.onIntent(AuthIntent.SignUp("a@b.c", "password123"))
            awaitState { viewModel.stateFlow.value is AuthUiState.AwaitingEmailConfirmation }
            runCurrent()

            val message = events.filterIsInstance<AuthUiEvent.Message>().singleOrNull()
            assertNotNull(message, "the user has to check their mail; nothing says so: $events")
            assertTrue(
                message.message.contains("email", ignoreCase = true),
                "the message has to name the step that unblocks them: ${message.message}",
            )
            assertTrue(
                events.none { it is AuthUiEvent.Error },
                "the account was created; reporting it in the error colour reads as a failed sign-up: $events",
            )
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `a completed sign-up still enters the application`() = runTest {
        // The guard on the guard: the session-less branch must not swallow the ordinary
        // case, or nobody could ever sign up.
        val viewModel = viewModel()
        val events = mutableListOf<AuthUiEvent>()
        val collector = launch { viewModel.events.collect { events += it } }
        try {
            runCurrent()

            viewModel.onIntent(AuthIntent.SignUp("a@b.c", "password123"))
            awaitState { viewModel.stateFlow.value is AuthUiState.Success }
            runCurrent()

            assertEquals(1, events.filterIsInstance<AuthUiEvent.NavigateToHome>().size)
            assertEquals(1, seedCalls)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `a failed sign-up is reported as a failure, not as "check your mail"`() = runTest {
        val viewModel = viewModel(signUp = Result.failure(AppError.Unauthorized("nope")))

        viewModel.onIntent(AuthIntent.SignUp("a@b.c", "password123"))
        awaitState { viewModel.stateFlow.value is AuthUiState.Idle }

        assertIs<AuthUiState.Idle>(viewModel.stateFlow.value)
    }

    // ── Signing out ──────────────────────────────────────────────────────

    /**
     * A sign-out that did not happen is reported.
     *
     * This is the negative control for the dropped failure. Before the fix the view
     * model launched the call, read nothing back, and returned — the user walked away
     * from the auth screen believing the account was closed while the session it was
     * meant to end was still on the device, and the next thing they learned was that
     * their data was still there too.
     *
     * The session surviving is asserted as well as the event, because the event alone
     * would pass against a fix that merely announced a failure the repository had not
     * actually suffered.
     */
    @Test
    fun `a sign-out that fails is reported rather than swallowed`() = runTest {
        sessions.value = Session.SignedIn(
            userId = UserId.generate(),
            email = "a@b.c",
            accessToken = "access",
            refreshToken = "refresh",
        )
        val viewModel = viewModel(signOut = Result.failure(AppError.Network("offline")))
        val events = mutableListOf<AuthUiEvent>()
        val collector = launch { viewModel.events.collect { events += it } }
        try {
            runCurrent()

            viewModel.onIntent(AuthIntent.SignOut)
            runCurrent()

            val error = events.filterIsInstance<AuthUiEvent.Error>().singleOrNull()
            assertNotNull(
                error,
                "the sign-out did not happen and nothing told the user so: $events",
            )
            assertTrue(
                error.message.contains("sign out", ignoreCase = true),
                "the message has to name what failed: ${error.message}",
            )
            assertIs<Session.SignedIn>(
                sessions.value,
                "the session survives a sign-out that failed — which is the whole " +
                    "reason the user has to be told",
            )
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `a successful sign-out reports no error`() = runTest {
        sessions.value = Session.SignedIn(
            userId = UserId.generate(),
            email = "a@b.c",
            accessToken = "access",
            refreshToken = "refresh",
        )
        val viewModel = viewModel()
        val events = mutableListOf<AuthUiEvent>()
        val collector = launch { viewModel.events.collect { events += it } }
        try {
            runCurrent()

            viewModel.onIntent(AuthIntent.SignOut)
            runCurrent()

            assertEquals(
                emptyList(),
                events.filterIsInstance<AuthUiEvent.Error>(),
                "the sign-out worked; an error here would teach users to ignore it",
            )
            assertEquals(Session.SignedOut, sessions.value)
        } finally {
            collector.cancel()
        }
    }

    // ── Infrastructure ──────────────────────────────────────────────────────

    private class MapStorage : SecureStorage {
        val values = mutableMapOf<String, String>()
        override suspend fun read(key: String): String? = values[key]
        override suspend fun write(key: String, value: String) {
            values[key] = value
        }
        override suspend fun delete(key: String) {
            values.remove(key)
        }
    }

    private class FixedIds : IdGenerator {
        override fun next(): String = UserId.generate().value
    }
}

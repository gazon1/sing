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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    ): AuthViewModel {
        val provider = SupabaseClientProvider(
            resolver = SupabaseConfigResolver(idGenerator = FixedIds()),
            store = storage,
        )
        val repository = object : AuthRepository {
            override val currentSession = sessions.asStateFlow()
            override val isLoading = MutableStateFlow(false)
            override suspend fun signIn(email: String, password: String) = signIn
            override suspend fun signUp(email: String, password: String) = signUp
            override suspend fun signInAnonymously() = Result.success(Unit)
            override suspend fun signOut(): Result<Unit> {
                sessions.value = Session.SignedOut
                return Result.success(Unit)
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

        awaitState { viewModel.state.value is AuthUiState.SignedOutWithNoServer }
        assertIs<AuthUiState.SignedOutWithNoServer>(viewModel.state.value)
    }

    @Test
    fun `a stored configuration puts the credential form first`() = runTest {
        storage.values[SupabaseConfigResolver.KEY_URL] = "https://project.supabase.co"
        storage.values[SupabaseConfigResolver.KEY_ANON_KEY] = "anon-key"

        val viewModel = viewModel()

        awaitState { viewModel.state.value is AuthUiState.Idle }
        assertIs<AuthUiState.Idle>(viewModel.state.value)
    }

    @Test
    fun `saving a configuration stores it and reveals the credential form`() = runTest {
        val viewModel = viewModel()
        awaitState { viewModel.state.value is AuthUiState.SignedOutWithNoServer }

        viewModel.onIntent(AuthIntent.SaveServerConfig("https://p.supabase.co", "anon-key"))
        awaitState { storage.values.containsKey(SupabaseConfigResolver.KEY_ANON_KEY) }

        assertEquals("https://p.supabase.co", storage.values[SupabaseConfigResolver.KEY_URL])
        assertEquals("anon-key", storage.values[SupabaseConfigResolver.KEY_ANON_KEY])
        awaitState { viewModel.state.value is AuthUiState.Idle }
    }

    @Test
    fun `a half-typed configuration is refused and names what is missing`() = runTest {
        val viewModel = viewModel()
        awaitState { viewModel.state.value is AuthUiState.SignedOutWithNoServer }

        viewModel.onIntent(AuthIntent.SaveServerConfig("https://p.supabase.co", "  "))

        // A stored URL with no key is indistinguishable from "not configured" until
        // the first request fails, and that failure arrives as a network error that
        // points at the wrong thing entirely.
        assertTrue(storage.values.isEmpty(), "a half-configuration must not be stored")
        assertEquals(AuthUiState.SignedOutWithNoServer, viewModel.state.value)
    }

    // ── Seeding on first entry ──────────────────────────────────────────────

    @Test
    fun `a successful sign-in runs the seed`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(AuthIntent.SignIn("a@b.c", "password123"))
        awaitState { viewModel.state.value is AuthUiState.Success }

        assertEquals(1, seedCalls)
    }

    @Test
    fun `a successful sign-up runs the seed`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(AuthIntent.SignUp("a@b.c", "password123"))
        awaitState { viewModel.state.value is AuthUiState.Success }

        assertEquals(1, seedCalls)
    }

    @Test
    fun `a failed sign-in does not seed`() = runTest {
        val viewModel = viewModel(signIn = Result.failure(AppError.Unauthorized("nope")))

        viewModel.onIntent(AuthIntent.SignIn("a@b.c", "password123"))
        awaitState { viewModel.state.value is AuthUiState.Idle }

        assertEquals(0, seedCalls, "uploading data on behalf of a sign-in that did not happen")
    }

    @Test
    fun `the pending state is cleared after a failure`() = runTest {
        val viewModel = viewModel(signIn = Result.failure(AppError.Unauthorized("nope")))

        viewModel.onIntent(AuthIntent.SignIn("a@b.c", "password123"))

        awaitState { viewModel.state.value !is AuthUiState.Loading }
        assertTrue(viewModel.state.value is AuthUiState.Idle)
    }

    @Test
    fun `a seed that fails does not prevent the sign-in`() = runTest {
        val provider = SupabaseClientProvider(SupabaseConfigResolver(FixedIds()), storage)
        val repository = object : AuthRepository {
            override val currentSession = sessions.asStateFlow()
            override val isLoading = MutableStateFlow(false)
            override suspend fun signIn(email: String, password: String) = Result.success(Unit)
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
        awaitState { viewModel.state.value is AuthUiState.Success }
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

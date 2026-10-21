package com.singularity.todo.test.stubs

import android.content.Context
import com.singularity.todo.core.auth.AuthGateway
import com.singularity.todo.core.auth.AuthRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for test builds that bypass Supabase authentication.
 *
 * This module is loaded **instead of** the real auth bindings from [com.singularity.todo.core.di.coreModule].
 * Because it is loaded via [com.singularity.todo.SingularityApp.extraKoinModules] with
 * `allowOverride = true`, these bindings override the production ones.
 *
 * Use case: E2E smoke tests that need a signed-in session without any network calls.
 * When this module is active:
 *   - [AuthGateway] → [NoOpAuthGateway] (all methods are no-ops, no network calls)
 *   - [AuthRepository] → [StubAuthRepository] (restores the session from the app's
 *     own encrypted store, and installs one when the store has none)
 *
 * The app starts straight into the agenda screen, bypassing the auth screen entirely.
 *
 * The stub reads and writes the same [com.singularity.todo.core.auth.SecureSessionStore]
 * the real login writes to, so a flow that signs the user out, damages the store or
 * restarts the app exercises the app's own session path rather than a memory that
 * dies with the process.
 *
 * Note: [com.singularity.todo.core.auth.SupabaseClientProvider] is NOT overridden —
 * the real client is built from the build-time Supabase config in local.properties
 * (a dummy URL + anon key). No network calls are made until the app actually needs
 * to talk to Supabase (e.g. sync), which the smoke flows don't exercise.
 */
fun testAuthModule(): Module = module {
    // Override: all methods are no-ops, no network calls.
    single<AuthGateway> { NoOpAuthGateway() }

    // Override: restores the session from the app's encrypted store, and installs
    // a test session when the store holds none.
    single<AuthRepository> {
        StubAuthRepository(
            sessionStore = get(),
            context = get<Context>(),
            scope = get(),
        )
    }
}

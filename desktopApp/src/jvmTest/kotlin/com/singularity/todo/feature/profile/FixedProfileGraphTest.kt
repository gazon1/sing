package com.singularity.todo.feature.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import com.singularity.todo.test.helpers.testPlatformModule
import org.junit.jupiter.api.Tag
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A process told which profile to serve never opens the store that holds the active one.
 *
 * ## What this protects
 *
 * The application's active profile lives in a preferences file of its own, and that file
 * takes a lock. A second process — the tool server an external agent is handed — must
 * not open it: the application is holding that lock, and a second process that reaches
 * for it either blocks or fights for it.
 *
 * The contract says the mechanism "is asserted by a test rather than by argument", and
 * until now no test did. This one does, and it does it by making the store *unbuildable*:
 * a fixed-profile graph that asked for the store would fail here rather than quietly
 * prove nothing.
 *
 * ## The control
 *
 * The second test is the other half. The same graph, built without a fixed profile,
 * asks for the store and is refused — so the first test cannot pass by accident, by a
 * graph that stopped asking for the store in both modes.
 */
@Tag("fast")
class FixedProfileGraphTest {

    /**
     * The store, and the refusal to build it.
     *
     * It replaces the test platform module's own binding: Koin 4 allows a definition to
     * be overridden, and the later definition is the one that is used.
     */
    private val forbiddenStore = module {
        single<DataStore<Preferences>>(qualifier = named("profile_state")) {
            error("The profile-state store was built — a process told which profile to serve must never open it.")
        }
    }

    @Test
    fun `a process told which profile to serve never opens the profile state`() {
        val app = koinApplication {
            // KOIN-W003 here is an accepted, documented tradeoff — the same one
            // `DomainGraphResolutionTest` takes: the module set is computed at
            // runtime, so the compile-time graph check cannot see it. This test
            // is the runtime validation that replaces it.
            @Suppress("KoinCompilationForInternal")
            modules(
                listOf(coreLoggingModule()) +
                    listOf(testPlatformModule(), forbiddenStore) +
                    domainModule(ProfileId.fromString("profile-1")),
            )
        }
        try {
            // Resolving the repository is what builds it, and building it is what would
            // have asked for the store. The graph answers, so the store was never asked for.
            val repository = app.koin.get<ProfileRepository>()

            assertNotNull(repository)
        } finally {
            app.close()
        }
    }

    @Test
    fun `a process without a fixed profile does ask for the profile state`() {
        val app = koinApplication {
            @Suppress("KoinCompilationForInternal")
            modules(
                listOf(coreLoggingModule()) +
                    listOf(testPlatformModule(), forbiddenStore) +
                    domainModule(),
            )
        }
        try {
            // The control. Without a fixed profile the repository takes the store, the
            // store refuses to be built, and the refusal is what the test expects. A
            // fixed-profile test that passed on its own would pass here too if the
            // fixed branch had quietly stopped being taken.
            val failure = assertFails {
                app.koin.get<ProfileRepository>()
            }

            assertTrue(
                generateSequence(failure) { it.cause }
                    .any { it.message?.contains("must never open it") == true },
                "The refusal came from the store itself, not from something else: $failure",
            )
        } finally {
            app.close()
        }
    }
}

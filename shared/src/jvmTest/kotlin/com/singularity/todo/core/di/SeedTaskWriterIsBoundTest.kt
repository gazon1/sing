package com.singularity.todo.core.di

import com.singularity.todo.core.database.UnitOfWork
import com.singularity.todo.core.seed.SeedTaskWriter
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.data.SeedTaskDaoWriter
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeUnitOfWork
import org.junit.jupiter.api.Tag
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * The seed's batch port is wired to the writer, not merely declared.
 *
 * ## Why a test for one binding
 *
 * `SeedWriter` takes `SeedTaskWriter` as a constructor parameter, so the debug seed link
 * has two ways to break that no test would see: the binding is missing, or it is bound
 * to the wrong type. The first is a `NoDefinitionFoundException` at the moment a flow
 * seeds, in a *debug* activity — the one thing a pull request never runs — and the
 * second is worse, because a container that resolved the port to something else would
 * keep compiling and keep "working" until a flow asserted on rows that were never
 * written.
 *
 * Koin's compile-time check catches a missing definition at build time already; this
 * test is the runtime half, and it costs one container. It builds `tasksModule()` alone
 * rather than the whole application graph, so it asserts the binding without dragging in
 * the database, the network and the platform modules that the application graph needs.
 */
@Tag("fast")
class SeedTaskWriterIsBoundTest {
    @Test
    fun `the seed port resolves to the fixture writer`() {
        // `tasksModule()` names the DAO but does not own it — the platform module binds
        // it beside the database. A local fake supplies that one edge so the module can
        // be built without a database, which is what keeps this a fast test.
        val db = FakeAppDatabase()
        val app =
            koinApplication {
                modules(
                    module {
                        single { db.taskDao() }
                        single<ProfileAwareCurrentUser> { FakeProfileAwareCurrentUser() }
                        single<UnitOfWork> { FakeUnitOfWork() }
                    },
                    tasksModule(),
                )
            }
        try {
            val resolved = app.koin.get<SeedTaskWriter>()

            assertIs<SeedTaskDaoWriter>(
                resolved,
                "the seed port must resolve to the writer that skips the sync enqueue — " +
                    "a different implementation here would seed rows through a path nobody checked",
            )
        } finally {
            app.close()
        }
    }
}

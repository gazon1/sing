package com.singularity.todo.feature.profile

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Persistence owner for profile creation (#3122).
 *
 * [ProfileFlowTest] in desktopApp covers the UI journey; this test proves the
 * created profile is durably persisted in Room and survives reopening the
 * database.
 */
@Tag("slow")
@Tag("slow:data")
class ProfileRepositoryPersistenceTest {
    private lateinit var fixture: DatabaseTestFixture

    @BeforeTest
    fun setUp() {
        fixture = DatabaseTestFixture.onDisk()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `created profile survives database reopen`(@TempDir dir: File) = runTest {
        val clock = MutableClock()
        val profileStateFile = File(dir, "profile_state.preferences_pb")

        val createdId = ProfileId.generate()
        val sessionA = fixture.database()
        val scopeA = CoroutineScope(SupervisorJob() + testScheduler)
        try {
            val repoA = ProfileRepositoryImpl(
                profileDao = sessionA.profileDao(),
                profileStateStore = PreferenceDataStoreFactory.create(scope = scopeA) {
                    profileStateFile
                },
                clock = clock,
                scope = backgroundScope,
            )
            repoA.create(
                Profile(
                    id = createdId,
                    name = "Work",
                    emoji = "💼",
                    colorIdx = 1,
                    isDefault = false,
                    createdAt = clock.now(),
                    updatedAt = clock.now(),
                ),
            ).getOrThrow()
        } finally {
            sessionA.close()
            scopeA.cancel()
            scopeA.coroutineContext.job.join()
        }

        val sessionB = fixture.reopen()
        val scopeB = CoroutineScope(SupervisorJob() + testScheduler)
        try {
            val repoB = ProfileRepositoryImpl(
                profileDao = sessionB.profileDao(),
                profileStateStore = PreferenceDataStoreFactory.create(scope = scopeB) {
                    profileStateFile
                },
                clock = clock,
                scope = backgroundScope,
            )
            val restored = repoB.get(createdId)
            assertNotNull(restored, "created profile must survive database reopen")
            assertEquals("Work", restored.name)
            assertEquals("💼", restored.emoji)
            assertEquals(1, restored.colorIdx)
            assertEquals(false, restored.isDefault)

            val all = repoB.observeAll().first()
            assertNotNull(all.firstOrNull { it.id == createdId }, "reopened observeAll must include created profile")
        } finally {
            sessionB.close()
            scopeB.cancel()
            scopeB.coroutineContext.job.join()
        }
    }
}

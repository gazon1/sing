package com.singularity.todo.feature.notes

import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("fast")
class AutoDiaryRepositoryTest {

    @Test
    fun `getOrCreate is idempotent for same project and date`() = runTest {
        val repo = FakeNotesRepository(clock = FakeClock())
        val pid = ProjectId("p1")
        val first = repo.getOrCreateDailyNote("2026-09-27", pid)
        val second = repo.getOrCreateDailyNote("2026-09-27", pid)
        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        assertEquals(first.getOrThrow(), second.getOrThrow())
    }

    @Test
    fun `getOrCreate is idempotent for global (null project) daily note`() = runTest {
        val repo = FakeNotesRepository(clock = FakeClock())
        val first = repo.getOrCreateDailyNote("2026-09-27", null)
        val second = repo.getOrCreateDailyNote("2026-09-27", null)
        assertEquals(first.getOrThrow(), second.getOrThrow())
    }

    @Test
    fun `getDailyNote returns null when absent`() = runTest {
        val repo = FakeNotesRepository(clock = FakeClock())
        assertNull(repo.getDailyNote("2026-09-27", ProjectId("p1")))
    }

    @Test
    fun `getDailyNote returns note when present`() = runTest {
        val repo = FakeNotesRepository(clock = FakeClock())
        val pid = ProjectId("p1")
        repo.getOrCreateDailyNote("2026-09-27", pid)
        val note = repo.getDailyNote("2026-09-27", pid)
        assertTrue(note != null)
        assertEquals(pid, note!!.projectId)
    }

    @Test
    fun `different projects get distinct daily notes`() = runTest {
        val repo = FakeNotesRepository(clock = FakeClock())
        val a = repo.getOrCreateDailyNote("2026-09-27", ProjectId("p1"))
        val b = repo.getOrCreateDailyNote("2026-09-27", ProjectId("p2"))
        assertTrue(a.getOrThrow() != b.getOrThrow())
    }
}
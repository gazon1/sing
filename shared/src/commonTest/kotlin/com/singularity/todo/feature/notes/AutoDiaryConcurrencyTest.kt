package com.singularity.todo.feature.notes

import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("fast")
class AutoDiaryConcurrencyTest {

    @Test
    fun `concurrent creates return the same note id`() = runTest {
        val repo = FakeNotesRepository(clock = FakeClock())
        val pid = ProjectId("p1")
        val results = (1..10).map {
            async { repo.getOrCreateDailyNote("2026-09-27", pid) }
        }.awaitAll()
        val ids = results.map { it.getOrThrow() }.toSet()
        assertEquals(1, ids.size, "all concurrent creates must collapse to one note")
    }
}

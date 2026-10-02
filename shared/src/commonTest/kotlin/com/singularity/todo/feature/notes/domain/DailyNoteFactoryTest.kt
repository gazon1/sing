package com.singularity.todo.feature.notes.domain

import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteKind
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class DailyNoteFactoryTest {

    private val repo = FakeNotesRepository(FakeProfileAwareCurrentUser())
    private val factory = DailyNoteFactory(repo, FixedClock)

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2024-03-15T10:00:00Z")
    }

    @Test
    fun `dailyKey formats date as ISO string`() {
        val date = LocalDate(2024, 3, 15)
        assertEquals("2024-03-15", factory.dailyKey(date))
    }

    @Test
    fun `todayKey returns today's ISO date`() {
        assertEquals("2024-03-15", factory.todayKey())
    }

    @Test
    fun `getDailyNote returns null when no daily note exists`() = runTest {
        assertNull(factory.getDailyNote("2024-03-15"))
    }

    @Test
    fun `getDailyNote returns existing daily note by dateKey`() = runTest {
        val noteId = repo.getOrCreateDailyNote("2024-03-15", null).getOrThrow()
        val found = factory.getDailyNote("2024-03-15")
        assertNotNull(found)
        assertEquals(noteId, found.id)
        assertEquals("2024-03-15", found.title)
        assertEquals(NoteKind.Daily, found.kind)
    }

    @Test
    fun `getOrCreate returns existing daily note without creating new one`() = runTest {
        val first = factory.getOrCreate("2024-03-15", null)
        val second = factory.getOrCreate("2024-03-15", null)
        assertEquals(first.getOrThrow(), second.getOrThrow())
        // Only one daily note for that date
        val allDaily = repo.watchDailyNotesInRange("2024-03-01", "2024-03-31").first()
        assertEquals(1, allDaily.size)
    }

    @Test
    fun `getOrCreate creates from template when no daily note exists`() = runTest {
        val templateId = repo.createWithContent(
            NoteId("tpl-daily"),
            title = "Morning routine",
            bodyMarkdown = "1. Meditate\n2. Exercise\n3. Journal",
            bodyHtml = "<p>1. Meditate</p>",
        ).getOrThrow()
        val noteId = factory.getOrCreate("2024-03-15", templateId)
        assertTrue(noteId.isSuccess)
        val daily = factory.getDailyNote("2024-03-15")
        assertNotNull(daily)
        assertEquals("2024-03-15", daily.title)
        assertEquals(NoteKind.Daily, daily.kind)
        assertTrue(daily.bodyMarkdown?.contains("Meditate") == true)
    }

    @Test
    fun `prevDay returns previous date key`() {
        assertEquals("2024-03-14", factory.prevDay("2024-03-15"))
    }

    @Test
    fun `prevDay returns null when previous would be before minDate`() {
        assertNull(factory.prevDay("2024-01-01", minDate = LocalDate(2024, 1, 1)))
        assertEquals("2024-01-01", factory.prevDay("2024-01-02", minDate = LocalDate(2024, 1, 1)))
    }

    @Test
    fun `nextDay returns next date key`() {
        assertEquals("2024-03-16", factory.nextDay("2024-03-15"))
    }

    @Test
    fun `nextDay returns null when next would be after maxDate`() {
        assertNull(factory.nextDay("2100-12-31", maxDate = LocalDate(2100, 12, 31)))
        assertEquals("2100-12-31", factory.nextDay("2100-12-30", maxDate = LocalDate(2100, 12, 31)))
    }
}

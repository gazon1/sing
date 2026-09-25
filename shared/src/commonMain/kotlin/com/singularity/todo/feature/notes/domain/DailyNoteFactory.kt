package com.singularity.todo.feature.notes.domain

import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Pure logic for daily note creation and lookup.
 *
 * A daily note is identified by a `dateKey` — an ISO date string (YYYY-MM-DD)
 * used as both the note's title and its identity anchor.
 */
class DailyNoteFactory(private val notesRepository: NotesRepository) {
    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Returns the canonical daily-key string for a given date.
     * Format: `YYYY-MM-DD`.
     */
    fun dailyKey(date: LocalDate): String = date.format(formatter)

    /**
     * Returns today's daily key.
     */
    fun todayKey(): String = dailyKey(LocalDate.now())

    /**
     * Looks up an existing daily note for [dateKey], or null if none exists.
     */
    suspend fun getDailyNote(dateKey: String): Note? = notesRepository.getDailyNote(dateKey)

    /**
     * Looks up the daily note for [date], or null if none exists.
     */
    suspend fun getDailyNote(date: LocalDate): Note? = getDailyNote(dailyKey(date))

    /**
     * Gets an existing daily note or creates one from [templateId] if provided.
     * Returns the note id.
     */
    suspend fun getOrCreate(dateKey: String, templateId: NoteId?): Result<NoteId> =
        notesRepository.getOrCreateDailyNote(dateKey, templateId)

    /**
     * Returns the date key for the previous day, or null if that would go before [minDate].
     */
    fun prevDay(dateKey: String, minDate: LocalDate = LocalDate.of(2000, 1, 1)): String? {
        val date = LocalDate.parse(dateKey, formatter)
        val prev = date.minusDays(1)
        return if (!prev.isBefore(minDate)) dailyKey(prev) else null
    }

    /**
     * Returns the date key for the next day, or null if that would go after [maxDate].
     */
    fun nextDay(dateKey: String, maxDate: LocalDate = LocalDate.of(2100, 12, 31)): String? {
        val date = LocalDate.parse(dateKey, formatter)
        val next = date.plusDays(1)
        return if (!next.isAfter(maxDate)) dailyKey(next) else null
    }
}

package com.singularity.todo.core.reminders

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ReminderOffset.fromMinutes] and [ReminderOffsetState].
 *
 * The point of both is the same: an offset this build does not know is still a
 * reminder. The old lookup answered `null` for it, and `null` was what the UI
 * read as "no reminder" — so a reminder written by an older build, by a newer
 * one, or by another client was shown to the user as nothing at all.
 */
@Tag("fast")
class ReminderOffsetStateTest {
    @Test
    fun `no reminder is no state at all`() {
        assertEquals(ReminderOffsetState.None, ReminderOffsetState.of(null))
    }

    @Test
    fun `every known offset is found`() {
        ReminderOffset.entries.forEach { offset ->
            assertEquals(
                ReminderOffsetState.Known(offset),
                ReminderOffsetState.of(offset.minutes),
                "the offset ${offset.minutes} minutes is one this build knows",
            )
        }
    }

    @Test
    fun `a stored offset counts backwards and is still found`() {
        // The row stores `-15` for "15 minutes before".
        assertEquals(
            ReminderOffsetState.Known(ReminderOffset.FIFTEEN_MIN),
            ReminderOffsetState.of(-15),
        )
    }

    @Test
    fun `an unknown offset is a reminder, not the absence of one`() {
        val state = ReminderOffsetState.of(37)

        assertTrue(
            state is ReminderOffsetState.Unknown,
            "an offset this build does not know is still a reminder",
        )
        assertEquals(37, (state as ReminderOffsetState.Unknown).offsetMinutes)
    }

    @Test
    fun `a negative unknown offset keeps its sign`() {
        val state = ReminderOffsetState.of(-37)

        assertEquals(-37, (state as ReminderOffsetState.Unknown).offsetMinutes)
    }

    @Test
    fun `the smallest int is an unknown offset, not a crash`() {
        // `abs(Int.MIN_VALUE)` is `Int.MIN_VALUE`: the one value whose magnitude
        // does not fit in an `Int`. It used to stay negative and match no offset,
        // which is fine — but the lookup must not overflow while trying.
        assertNull(ReminderOffset.fromMinutes(Int.MIN_VALUE))
        assertEquals(
            ReminderOffsetState.Unknown(Int.MIN_VALUE),
            ReminderOffsetState.of(Int.MIN_VALUE),
        )
    }

    @Test
    fun `the largest int is an unknown offset`() {
        assertNull(ReminderOffset.fromMinutes(Int.MAX_VALUE))
        assertEquals(
            ReminderOffsetState.Unknown(Int.MAX_VALUE),
            ReminderOffsetState.of(Int.MAX_VALUE),
        )
    }
}

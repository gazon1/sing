package com.singularity.todo.feature.calendar_sync.error

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("fast")
class CalendarSyncExceptionsTest {

    @Test
    fun permissionRevoked_is_sealed() {
        val ex = CalendarSyncException.PermissionRevokedException()
        assertEquals("Calendar permission revoked", ex.message)
        assertEquals("Calendar permission revoked", ex.message)
    }

    @Test
    fun calendarNotFound_contains_calendarId() {
        val ex = CalendarSyncException.CalendarNotFoundException("cal_abc123")
        assertEquals("Calendar cal_abc123 not found", ex.message)
        assertEquals("cal_abc123", ex.calendarId)
    }

    @Test
    fun calendarAppMissing_contains_packageName() {
        val ex = CalendarSyncException.CalendarAppMissingException("com.google.calendar")
        assertEquals("Calendar app com.google.calendar is not installed", ex.message)
        assertEquals("com.google.calendar", ex.packageName)
    }

    @Test
    fun transientSyncException_carries_cause() {
        val cause = IllegalStateException("Cursor is closed")
        val ex = CalendarSyncException.TransientSyncException("Sync failed", cause)
        assertEquals("Sync failed", ex.message)
        assertEquals(cause, ex.cause)
    }

    @Test
    fun networkSyncException_has_default_message() {
        val ex = CalendarSyncException.NetworkSyncException()
        assertEquals("Network error during calendar sync", ex.message)
    }
}

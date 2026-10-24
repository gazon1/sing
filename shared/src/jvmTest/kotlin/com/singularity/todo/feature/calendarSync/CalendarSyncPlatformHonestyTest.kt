package com.singularity.todo.feature.calendarSync

import com.singularity.todo.feature.calendarSync.data.JvmCalendarAppQueries
import com.singularity.todo.feature.calendarSync.data.NoopCalendarProvider
import com.singularity.todo.feature.calendarSync.data.NoopCalendarSyncRepositoryImpl
import com.singularity.todo.feature.calendarSync.work.NoopCalendarSyncWorkScheduler
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The desktop's calendar ports say what they are.
 *
 * ## What is at stake
 *
 * A no-op implementation that does not say it is a no-op is a feature that looks
 * finished: the screen offers a switch, the switch is stored, and nothing happens.
 * The ports answer for themselves — every one of them says `isSupported = false` —
 * and the screen says "needs Android" instead of offering a control that does
 * nothing.
 *
 * ## Why the flag is on the port
 *
 * The same reason
 * [com.singularity.todo.feature.pomodoro.PomodoroTaskListProvider.isSupported]
 * exists: the implementation is the authority on what it can provide. A caller that
 * has to guess — or a screen that reads the flag off some other port and hopes the
 * two agree — will eventually show a feature that cannot work.
 */
@Tag("fast")
class CalendarSyncPlatformHonestyTest {

    @Test
    fun `the desktop's calendar ports say they cannot work`() {
        assertFalse(NoopCalendarProvider().isSupported, "the calendar provider must say it is a no-op")
        assertFalse(
            NoopCalendarSyncWorkScheduler().isSupported,
            "the work scheduler must say it is a no-op",
        )
        assertFalse(JvmCalendarAppQueries().isSupported, "the app queries must say they are a no-op")
        assertFalse(
            NoopCalendarSyncRepositoryImpl().isSupported,
            "the settings repository must say it is a no-op",
        )
    }
}

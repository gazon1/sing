package com.singularity.todo.feature.nav

import com.singularity.todo.feature.calendar.presentation.nav.CalendarRoute
import com.singularity.todo.feature.notes.presentation.nav.NotesRoute
import com.singularity.todo.feature.projects.presentation.nav.ProjectsRoute
import com.singularity.todo.feature.tasks.presentation.nav.TasksRoute
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guard for the Android cold-start crash in [navSavedStateConfig].
 *
 * `subclass(serializer)` is `inline reified` and keys the polymorphic registration on
 * `T::class` resolved at the call site. The previous implementation took
 * `vararg KSerializer<out NavKey>` and cast each element to `KSerializer<NavKey>`, so `T`
 * inferred as `NavKey` and *every* entry registered under `NavKey::class` — the second
 * registration threw `SerializerAlreadyRegisteredException` and the process died before the
 * first frame.
 *
 * These tests only run on JVM; the crash itself was Android-only because only Android
 * evaluates the `SavedStateConfiguration`. Building the same configuration here reproduces it.
 */
class NavSavedStateConfigTest {

    @Test
    fun `top-level destination hierarchy builds`() {
        navSavedStateConfig(AppDestination.serializer())
    }

    @Test
    fun `agenda start route hierarchy builds`() {
        navSavedStateConfig(AgendaStartRoute.serializer())
    }

    @Test
    fun `tasks route hierarchy builds`() {
        navSavedStateConfig(TasksRoute.serializer())
    }

    @Test
    fun `projects route hierarchy builds`() {
        navSavedStateConfig(ProjectsRoute.serializer())
    }

    @Test
    fun `notes route hierarchy builds`() {
        navSavedStateConfig(NotesRoute.serializer())
    }

    @Test
    fun `calendar route hierarchy builds`() {
        navSavedStateConfig(CalendarRoute.serializer())
    }

    @Test
    fun `every destination subtype is reachable without a hand-maintained registry`() {
        // The point of annotating `AppDestination` as `@Serializable sealed`: adding a new
        // destination must not require editing a serializer list somewhere else.
        val json = Json
        val samples: List<AppDestination> = listOf<AppDestination>(
            AppDestination.Inbox,
            AppDestination.Today,
            AppDestination.Upcoming,
            AppDestination.Plans,
            AppDestination.Pomodoro,
            AppDestination.Statistics,
            AppDestination.Calendar,
            AppDestination.Notes,
            AppDestination.AiChat,
            AppDestination.Search,
            AppDestination.Archive,
            AppDestination.Settings,
            AppDestination.AiUsage,
            AppDestination.ProfileSwitcher,
            AppDestination.AgendaGraph(AgendaStartRoute.Today),
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create),
            AppDestination.TasksByProject("p1"),
            AppDestination.ProjectsGraph(),
            AppDestination.NotesGraph(),
            AppDestination.CalendarGraph(),
            AppDestination.TaskDetail("t1"),
            AppDestination.TaskDetailCreate(),
            AppDestination.ProjectEditor("pr1"),
            AppDestination.ProjectDetail("pr1"),
        )

        samples.forEach { destination ->
            val encoded = json.encodeToString(
                AppDestination.serializer(),
                destination,
            )
            val decoded = json.decodeFromString(AppDestination.serializer(), encoded)
            assertEquals(destination, decoded, "round-trip failed for $destination")
        }
    }
}

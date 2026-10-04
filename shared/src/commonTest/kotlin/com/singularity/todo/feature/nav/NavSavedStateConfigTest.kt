package com.singularity.todo.feature.nav

import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for two crashes that each killed the process on Android before
 * its first frame could settle.
 *
 * 1. **Duplicate registration.** `subclass` is `inline reified` and keys the
 *    polymorphic registration on the `T` resolved at the call site. The original
 *    implementation took `vararg KSerializer<out NavKey>` and erased each element
 *    to `KSerializer<NavKey>`, so `T` inferred as `NavKey` and *every* entry
 *    registered under `NavKey::class` — the second threw
 *    `SerializerAlreadyRegisteredException`.
 * 2. **Lone `data object` routes.** `Settings` and `Search` were declared as
 *    `data object Settings : NavKey`, outside any sealed hierarchy, so
 *    `subclassesOfSealed` threw `IllegalArgumentException: subclassesOfSealed only
 *    supports automatic adding of subclasses of sealed types with standard
 *    serializers` the moment either screen opened. No test covered either route,
 *    which is why it shipped. Found by
 *    the settings-cycle smoke flow (now `Maestro/flows/smoke/02-menu-settings.yaml`).
 *
 * Both are now structurally impossible: every route is a leaf of the single sealed
 * [AppNavKey] root, and one shared [navSavedStateConfig()] registers all of them.
 * These tests hold that property in place.
 *
 * They run on JVM; the crashes were Android-only because only Android evaluates the
 * `SavedStateConfiguration`. Building the same configuration here reproduces both.
 */
@Tag("fast")
class NavSavedStateConfigTest {

    @Test
    fun `shared configuration builds`() {
        // The single call every NavGraph now uses. Building it is what used to throw.
        navSavedStateConfig()
    }

    @Test
    fun `settings and search — the routes that crashed — are addressable`() {
        // Both are plain objects on purpose: a `data object` is a valid leaf of a
        // sealed interface, and these are the two that used to be unreachable.
        val nested: List<AppNavKey> = listOf(Settings, Search)
        val json = Json
        nested.forEach { route ->
            val encoded = json.encodeToString(AppNavKey.serializer(), route)
            val decoded = json.decodeFromString(AppNavKey.serializer(), encoded)
            assertEquals(route, decoded, "round-trip failed for $route")
        }
    }

    @Test
    fun `every nested graph route is a leaf of the sealed root`() {
        // If a route is declared as `: NavKey` instead of `: AppNavKey`, it is
        // invisible to `subclassesOfSealed` and its screen crashes on open — so
        // assert the relationship, don't trust a comment.
        val nested: List<AppNavKey> = listOf(
            Settings,
            Search,
            TasksRoute.Detail(TaskId("t1")),
            ProjectsRoute.List,
            NotesRoute.List,
            CalendarRoute.Month("2026-09"),
            AgendaStartRoute.Today,
            AppDestination.ProjectsStartRoute.List,
            AppDestination.NotesStartRoute.List,
            AppDestination.CalendarStartRoute.Month("2026-09"),
        )
        assertTrue(nested.isNotEmpty())
    }

    @Test
    fun `every destination subtype round-trips without a hand-maintained registry`() {
        // The point of the sealed root: adding a destination must not require
        // editing a serializer list anywhere.
        val json = Json
        val samples: List<AppDestination> = listOf(
            AppDestination.AgendaGraph(AgendaStartRoute.Inbox),
            AppDestination.AgendaGraph(AgendaStartRoute.Today),
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
            AppDestination.AgendaGraph(AgendaStartRoute.Upcoming),
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create()),
            // B2 round-trip inventory: the five routes now carry typed entity ids —
            // value-class properties must survive the polymorphic encode/decode too.
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(TaskId("t1"))),
            AppDestination.TasksByProject(ProjectId("p1")),
            AppDestination.NotesGraph(AppDestination.NotesStartRoute.Preview(NoteId("n1"))),
            AppDestination.NotesGraph(AppDestination.NotesStartRoute.EditorForTask(TaskId("t1"))),
            AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.Editor(ProjectId("p1"))),
            AppDestination.ProjectsGraph(),
            AppDestination.NotesGraph(),
            AppDestination.CalendarGraph(),
            AppDestination.ProjectEditor("pr1"),
            AppDestination.ProjectDetail("pr1"),
        )

        samples.forEach { destination ->
            val encoded = json.encodeToString(AppDestination.serializer(), destination)
            val decoded = json.decodeFromString(AppDestination.serializer(), encoded)
            assertEquals(destination, decoded, "round-trip failed for $destination")
        }
    }
}

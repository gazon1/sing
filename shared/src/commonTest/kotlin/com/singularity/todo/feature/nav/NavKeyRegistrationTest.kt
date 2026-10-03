package com.singularity.todo.feature.nav

import androidx.navigation3.runtime.NavKey
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclassesOfSealed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every route must survive the polymorphic `NavKey` module the app actually
 * builds — not just its own hierarchy's serializer.
 *
 * Round-tripping through `AppDestination.serializer()` proves nothing here: that
 * path never consults the module, so a route declared outside [AppNavKey] would
 * still pass while its screen throws `Serializer for subclass 'X' is not found`
 * the first time it is restored. That is the crash this guards.
 *
 * The module below mirrors [navSavedStateConfig] exactly. Keep the two in step:
 * if the app's registration changes, change this with it.
 */
class NavKeyRegistrationTest {

    private val json = Json {
        serializersModule = SerializersModule {
            polymorphic(NavKey::class) { subclassesOfSealed(AppNavKey.serializer()) }
        }
    }

    private fun assertRoundTrips(route: NavKey) {
        val poly = PolymorphicSerializer(NavKey::class)
        val encoded = json.encodeToString(poly, route)
        assertEquals(route, json.decodeFromString(poly, encoded), "no NavKey registration for $route")
    }

    @Test
    fun `top-level destinations are registered`() {
        assertRoundTrips(AppDestination.AgendaGraph(AgendaStartRoute.Inbox))
        assertRoundTrips(AppDestination.AgendaGraph(AgendaStartRoute.Today))
        assertRoundTrips(AppDestination.Statistics)
    }

    @Test
    fun `nested graph destinations are registered`() {
        // These are the leaves of a *nested* sealed hierarchy. They are the ones
        // that broke when the app switched to registering AppNavKey alone.
        assertRoundTrips(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Inbox))
        assertRoundTrips(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail("t1")))
        assertRoundTrips(AppDestination.ProjectsGraph())
        assertRoundTrips(AppDestination.NotesGraph())
        assertRoundTrips(AppDestination.CalendarGraph())
        assertRoundTrips(AppDestination.AgendaGraph(AgendaStartRoute.Today))
        assertRoundTrips(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail("t1")))
    }

    @Test
    fun `routes that are not inside AppDestination are registered`() {
        // The lone `data object` routes that `subclassesOfSealed` rejects when
        // handed a non-sealed root — the original crash.
        assertRoundTrips(Settings)
        assertRoundTrips(Search)
    }

    @Test
    fun `nested graph route leaves are registered`() {
        assertRoundTrips(TasksRoute.Detail(TaskId("t1")))
        assertRoundTrips(NotesRoute.List)
        assertRoundTrips(ProjectsRoute.List)
        assertRoundTrips(CalendarRoute.Month("2026-09"))
        assertRoundTrips(AgendaStartRoute.Today)
    }
}

package com.singularity.todo.feature.nav.deeplink

import androidx.navigation3.runtime.deeplink.DeepLinkRequest
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.presentation.nav.AgendaDeepLink
import com.singularity.todo.feature.auth.AuthDeepLink
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.nav.TaskDeepLink
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The link table: which URI opens which screen.
 *
 * The registry is the only place that decides what a link means, so this table
 * is the contract between the app and everything outside it — a calendar event,
 * a notification, a magic-link email, the desktop CLI.
 */
@Tag("fast")
class DeepLinkRegistryTest {

    private val registry = DeepLinkRegistry(
        listOf(TaskDeepLink(), AgendaDeepLink(), AuthDeepLink()),
    )

    private fun resolve(uri: String): DeepLinkTarget? = registry.resolve(DeepLinkRequest(uri))

    private val today: AppDestination = AppDestination.AgendaGraph(AgendaStartRoute.Today)

    // ── The known links ────────────────────────────────────────────────────

    @Test
    fun `a task link opens the task on the Today tab`() {
        assertEquals(
            DeepLinkTarget(
                tab = today,
                screen = AppDestination.TasksGraph(
                    AppDestination.TasksStartRoute.Detail(TaskId("t1")),
                ),
            ),
            resolve("singularity://task/t1"),
        )
    }

    @Test
    fun `a saved-view link opens the view editor on the Today tab`() {
        assertEquals(
            DeepLinkTarget(
                tab = today,
                screen = AppDestination.AgendaGraph(
                    AgendaStartRoute.SavedAgendaEdit(SavedAgendaViewId.fromString("v1")),
                ),
            ),
            resolve("singularity://view/v1"),
        )
    }

    @Test
    fun `a magic link opens the confirmation screen on the Today tab`() {
        assertEquals(
            DeepLinkTarget(
                tab = today,
                screen = AppDestination.MagicLinkConfirm(email = "a@b.c", token = "tok"),
            ),
            resolve("singularity://verify?email=a@b.c&token=tok"),
        )
    }

    @Test
    fun `a magic link keeps an email that needed encoding`() {
        val target = resolve("singularity://verify?email=a%40b.c&token=tok")

        assertEquals("a@b.c", (target?.screen as? AppDestination.MagicLinkConfirm)?.email)
    }

    // ── What is not a link ─────────────────────────────────────────────────

    @Test
    fun `an unknown host resolves to nothing`() {
        assertNull(resolve("singularity://toolbox"))
    }

    @Test
    fun `another scheme resolves to nothing`() {
        assertNull(resolve("https://example.com/task/t1"))
    }

    @Test
    fun `a link without its argument resolves to nothing`() {
        assertNull(resolve("singularity://task"))
    }

    @Test
    fun `a magic link without a token resolves to nothing`() {
        assertNull(resolve("singularity://verify?email=a@b.c"))
    }

    @Test
    fun `a link whose path is longer than the template resolves to nothing`() {
        assertNull(resolve("singularity://task/t1/extra"))
    }

    // ── The order of the checks ───────────────────────────────────────────

    @Test
    fun `the first matching link in the list wins`() {
        // Two links that both match the same request: the registry must take the
        // first one, because "the best match" is not defined when two templates
        // accept the same URI.
        val first = AlwaysMatchingLink("first", DeepLinkTarget(today, AppDestination.Plans))
        val second = AlwaysMatchingLink("second", DeepLinkTarget(today, AppDestination.Settings))

        val ordered = DeepLinkRegistry(listOf(first, second))
        val reversed = DeepLinkRegistry(listOf(second, first))

        assertEquals(
            DeepLinkTarget(today, AppDestination.Plans),
            ordered.resolve(DeepLinkRequest("singularity://task/t1")),
        )
        assertEquals(
            DeepLinkTarget(today, AppDestination.Settings),
            reversed.resolve(DeepLinkRequest("singularity://task/t1")),
        )
    }

    @Test
    fun `an empty registry resolves to nothing`() {
        assertNull(DeepLinkRegistry(emptyList()).resolve(DeepLinkRequest("singularity://task/t1")))
    }

    /** A link that accepts every request — used to pin the order of the checks. */
    private class AlwaysMatchingLink(override val id: String, private val target: DeepLinkTarget) :
        AppDeepLink {
        override fun match(request: DeepLinkRequest): DeepLinkTarget = target
    }
}

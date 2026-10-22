package com.singularity.todo.feature.nav

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every task section of the agenda shows the context menu — on both platforms.
 *
 * ## What this protects
 *
 * Android used to leave the three "problem" sections — blocked, stagnant,
 * overdue — without the menu, while Desktop showed it everywhere. The two lists
 * were written out separately, and nothing said they were supposed to be the
 * same. The divergence was accidental: the Android list simply stopped three
 * entries short.
 *
 * Now the entries live in one common file, so the platforms cannot drift — the
 * test holds the list itself, which is the part a future edit could still get
 * wrong.
 */
@Tag("fast")
class AgendaContextMenuParityTest {

    private val commonMainRoot: String = System.getProperty("commonMain.root")
        ?: error("commonMain.root system property is not set — see shared/build.gradle.kts")

    private val source: String = File(
        "$commonMainRoot/com/singularity/todo/feature/agenda/presentation/nav/AgendaNavGraph.kt",
    ).readText()

    /** The `entry<AgendaStartRoute.X> { ... }` blocks, by route name. */
    private val entries: Map<String, String> = source
        .split("entry<AgendaStartRoute.")
        .drop(1)
        .associate { part -> part.substringBefore(">") to part }

    @Test
    fun `every task section of the agenda gets the context menu host`() {
        for (section in SECTIONS_WITH_MENU) {
            val block = entries[section]
            assertTrue(block != null, "The agenda has no entry for $section.")
            assertTrue(
                block.contains("contextMenuHost = contextMenuHost"),
                "The agenda's $section section does not show the context menu.",
            )
        }
    }

    @Test
    fun `the saved-view sections show no context menu`() {
        for (section in SECTIONS_WITHOUT_MENU) {
            val block = entries[section]
            assertTrue(block != null, "The agenda has no entry for $section.")
            assertTrue(
                !block.contains("contextMenuHost"),
                "The agenda's $section section shows the task context menu, which it has no tasks for.",
            )
        }
    }

    @Test
    fun `the agenda declares every route it can be opened with`() {
        assertEqualsRoutes()
    }

    private fun assertEqualsRoutes() {
        val declared = entries.keys
        assertTrue(
            declared.containsAll(SECTIONS_WITH_MENU + SECTIONS_WITHOUT_MENU),
            "The agenda entry provider is missing routes: " +
                (SECTIONS_WITH_MENU + SECTIONS_WITHOUT_MENU - declared),
        )
    }

    private companion object {
        /** The task sections — every one of them shows the menu. */
        val SECTIONS_WITH_MENU = listOf(
            "Inbox",
            "Today",
            "Upcoming",
            "Project",
            "Tag",
            "Blocked",
            "Stagnant",
            "Overdue",
        )

        /** The saved-view sections — they list views, not tasks, so no task menu. */
        val SECTIONS_WITHOUT_MENU = listOf(
            "SavedAgendaList",
            "SavedAgendaResults",
            "SavedAgendaEdit",
            "SavedAgendaCreate",
        )
    }
}

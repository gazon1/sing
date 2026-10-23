package com.singularity.todo.feature.nav

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's entry providers cover exactly the destinations they should.
 *
 * ## What this protects
 *
 * The entries used to be written out twice — once per platform — and a new
 * destination had to be added to both files. The entry *bodies* now live in the
 * contributors under `feature/nav/entries/`, one per feature, and the platform
 * files are assemblers. A destination that is added to a contributor but not to
 * the platform's assembler would compile and simply never open — the failure
 * mode this test exists to catch.
 *
 * ## Why it reads the sources
 *
 * The set of destinations is a property of the *code*, not of a runtime: an
 * entry provider is a map from key to screen, and asking "which keys does this
 * map have?" is answered by reading the `entry<...>` declarations. The
 * alternative — resolving every key at runtime — needs the whole Koin graph and
 * would only prove that the map is non-empty, not that it is the right map.
 *
 * The two platform files are read too, because the tasks graph entry is
 * deliberately platform-specific (see `JvmNavEntries`).
 */
@Tag("fast")
class AppEntryParityTest {

    private val commonMainRoot: String = System.getProperty("commonMain.root")
        ?: error("commonMain.root system property is not set — see shared/build.gradle.kts")

    /** `shared/src` — the root both platform source sets hang off. */
    private val sharedSrcRoot: String = File(commonMainRoot).parentFile.parentFile.path

    private fun readSource(file: File): String {
        assertTrue(file.exists(), "Expected source file to exist: ${file.path}")
        return file.readText()
    }

    private fun sourceOf(vararg paths: String): List<String> =
        paths.map { readSource(File(it)) }

    /** The `entry<AppDestination.X>` declarations in a source text. */
    private fun destinationsIn(source: String): List<String> =
        ENTRY_REGEX.findAll(source).map { it.groupValues[1] }.toList()

    @Test
    fun `the entry providers cover exactly the expected destinations`() {
        val declared = sourceOf(
            "$sharedSrcRoot/commonMain/kotlin/com/singularity/todo/feature/nav/entries/AppEntries.kt",
            "$sharedSrcRoot/commonMain/kotlin/com/singularity/todo/feature/nav/entries/AgendaEntries.kt",
            "$sharedSrcRoot/commonMain/kotlin/com/singularity/todo/feature/nav/entries/CalendarEntries.kt",
            "$sharedSrcRoot/commonMain/kotlin/com/singularity/todo/feature/nav/entries/NotesEntries.kt",
            "$sharedSrcRoot/commonMain/kotlin/com/singularity/todo/feature/nav/entries/ProjectsEntries.kt",
            "$sharedSrcRoot/commonMain/kotlin/com/singularity/todo/feature/nav/entries/SearchEntries.kt",
            "$sharedSrcRoot/commonMain/kotlin/com/singularity/todo/feature/nav/entries/SettingsEntries.kt",
            "$sharedSrcRoot/androidMain/kotlin/com/singularity/todo/feature/nav/AndroidNavEntries.kt",
            "$sharedSrcRoot/jvmMain/kotlin/com/singularity/todo/feature/nav/JvmNavEntries.kt",
        ).flatMap { destinationsIn(it) }.toSet()

        assertEquals(
            EXPECTED_DESTINATIONS,
            declared,
            "The entry providers do not cover exactly the expected destinations.",
        )
    }

    @Test
    fun `every destination but the platform-specific ones is declared in common code`() {
        val inCommon = sourceOf(
            "$sharedSrcRoot/commonMain/kotlin/com/singularity/todo/feature/nav/entries/AppEntries.kt",
            "$sharedSrcRoot/commonMain/kotlin/com/singularity/todo/feature/nav/entries/AgendaEntries.kt",
            "$sharedSrcRoot/commonMain/kotlin/com/singularity/todo/feature/nav/entries/CalendarEntries.kt",
            "$sharedSrcRoot/commonMain/kotlin/com/singularity/todo/feature/nav/entries/NotesEntries.kt",
            "$sharedSrcRoot/commonMain/kotlin/com/singularity/todo/feature/nav/entries/ProjectsEntries.kt",
            "$sharedSrcRoot/commonMain/kotlin/com/singularity/todo/feature/nav/entries/SearchEntries.kt",
            "$sharedSrcRoot/commonMain/kotlin/com/singularity/todo/feature/nav/entries/SettingsEntries.kt",
        ).flatMap { destinationsIn(it) }.toSet()

        assertEquals(
            EXPECTED_DESTINATIONS - PLATFORM_SPECIFIC_DESTINATIONS,
            inCommon,
            "The common contributors must cover every destination but the platform-specific ones.",
        )
    }

    @Test
    fun `the tasks graph entry is declared on both platforms`() {
        val android = destinationsIn(
            readSource(File("$sharedSrcRoot/androidMain/kotlin/com/singularity/todo/feature/nav/AndroidNavEntries.kt")),
        )
        val desktop = destinationsIn(
            readSource(File("$sharedSrcRoot/jvmMain/kotlin/com/singularity/todo/feature/nav/JvmNavEntries.kt")),
        )

        assertTrue("TasksGraph" in android, "Android must declare the tasks graph entry.")
        assertTrue("TasksGraph" in desktop, "Desktop must declare the tasks graph entry.")
    }

    private companion object {
        /** `entry<AppDestination.X>` — the destination's own name. */
        val ENTRY_REGEX = Regex("""entry<AppDestination\.(\w+)>""")

        /**
         * The destinations the app opens. `TasksByProject` is deliberately absent:
         * it has no entry and no production opener — the documented unwired
         * surface in `docs/navigation.md`.
         */
        val EXPECTED_DESTINATIONS = setOf(
            "Plans",
            "Pomodoro",
            "Statistics",
            "Calendar",
            "Notes",
            "AiChat",
            "Proposals",
            "Search",
            "Archive",
            "Settings",
            "AiUsage",
            "ProfileSwitcher",
            "AttachAccount",
            "ChangePassword",
            "MagicLinkConfirm",
            "ProjectEditor",
            "ProjectDetail",
            "AttachmentViewer",
            "ProjectsGraph",
            "TasksGraph",
            "NotesGraph",
            "CalendarGraph",
            "AgendaGraph",
            "ExternalAgent",
        )

        /**
         * The destinations whose entry stays platform-specific: the pushed tasks
         * graph seeds its own per-entry stack, and Desktop adds the detail route
         * to it so that the right frame renders immediately.
         *
         * The external-agent screen is the second one. Its screen hosts a child
         * process, and a phone cannot spawn one — so the entry is declared on
         * Desktop only, and a route pushed on Android would have nothing behind it.
         */
        val PLATFORM_SPECIFIC_DESTINATIONS = setOf("TasksGraph", "ExternalAgent")
    }
}

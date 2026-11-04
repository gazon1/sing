package com.singularity.todo.arch

import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.shell.AppMenu
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Plans and Pomodoro flows reach their destination through the app menu,
 * and this is the test that keeps them doing it.
 *
 * ## Why an assertion and not a comment
 *
 * Plans and Pomodoro are menu destinations, not bottom-bar tabs. Eighteen flows
 * used to tap `nav_tab_plans` / `nav_tab_pomodoro` — ids the bar stopped
 * rendering — so every one of them failed on an element that cannot exist, and
 * the failure named a tab instead of the behaviour the flow was written for.
 * The repair is eighteen edited files with nothing holding them together: the
 * next person to touch the navigation can put the tab id back and only a device
 * run on the nightly will say so. This test says so on the pull request.
 *
 * ## What it reads
 *
 * The flow files, as text. There is deliberately no YAML parser here: the
 * questions are "does this `tapOn` address a retired id", "does this `runFlow`
 * name a file that exists" and "is the menu drawn in the order the flows scroll
 * for", and the first two are answerable a line at a time. A parser in the test
 * would be a second grammar to keep in step with Maestro's. The line-at-a-time
 * reading has blind spots, and they are named where they bite rather than
 * papered over: a `runFlow:` whose target sits on the following line is skipped
 * instead of guessed at.
 */
class MaestroFlowNavigationTest {
    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { dir -> dir.parentFile }
            .firstOrNull { dir -> File(dir, "Maestro/flows").isDirectory }
            ?: error("No Maestro/flows above '${System.getProperty("user.dir")}'; cannot find the repository root.")

    private val flows: List<File> = filesUnder(File(repoRoot, "Maestro/flows"))
    private val helpers: List<File> = filesUnder(File(repoRoot, "Maestro/helpers"))

    @Test
    fun `no flow taps a bottom-bar tab the bar does not render`() {
        assertNoRetiredTap(flows)
    }

    @Test
    fun `no helper taps a bottom-bar tab the bar does not render`() {
        assertNoRetiredTap(helpers)
    }

    @Test
    fun `every file a flow runs exists`() {
        val missing =
            (flows + helpers).flatMap { file ->
                runFlowTargets(file).filterNot { target -> target.isFile }.map { target ->
                    "${path(file)} runs ${path(target)}, which does not exist"
                }
            }
        assertTrue(
            missing.isEmpty(),
            "A runFlow that names a missing file fails as 'file not found' rather than as the " +
                "step the reader has to look at:\n" + missing.joinToString("\n"),
        )
    }

    @Test
    fun `the sheet draws Plans above Pomodoro`() {
        val order = AppMenu.sheetDestinations.map { entry -> entry.destination }
        val plans = order.indexOf(AppDestination.Plans)
        val pomodoro = order.indexOf(AppDestination.Pomodoro)
        assertTrue(plans >= 0 && pomodoro >= 0, "Both entries belong to the sheet; the drawn order is $order")
        assertTrue(
            plans < pomodoro,
            "open-plans.yaml and open-pomodoro.yaml find both entries with `direction: DOWN` from a " +
                "freshly opened sheet, so Plans has to be drawn above Pomodoro. The drawn order is $order.",
        )
    }

    private fun assertNoRetiredTap(files: List<File>) {
        val offenders =
            files.flatMap { file ->
                tappedIds(file).filter { id -> id in retiredTabIds }.map { id -> "${path(file)} taps $id" }
            }
        assertTrue(
            offenders.isEmpty(),
            "Plans and Pomodoro live behind the bottom-bar Menu button now. Open the menu " +
                "(Maestro/helpers/open-plans.yaml, Maestro/helpers/open-pomodoro.yaml) instead of " +
                "tapping an id the bar does not draw:\n" + offenders.joinToString("\n"),
        )
    }

    /** The ids of every `tapOn:` step in [file], read a line at a time. */
    private fun tappedIds(file: File): List<String> {
        val ids = mutableListOf<String>()
        var inTapOn = false
        file.forEachLine { raw ->
            val line = raw.trim()
            when {
                line.startsWith("- ") -> inTapOn = line.removePrefix("- ").trim() == "tapOn:"
                inTapOn && line.startsWith("id:") -> ids += line.removePrefix("id:").trim()
            }
        }
        return ids.filter { id -> id.isNotEmpty() }
    }

    /**
     * Every file [file] runs, resolved the way Maestro resolves it — against the
     * directory the flow lives in, not the repository root. A `runFlow:` whose
     * target is not on the same line is skipped: it carries its target in a
     * nested `file:` key, and reading that properly means parsing YAML.
     */
    private fun runFlowTargets(file: File): List<File> =
        file.readLines()
            .map { line -> line.trim() }
            .filter { line -> line.startsWith("- runFlow:") }
            .map { line -> line.removePrefix("- runFlow:").trim() }
            .filter { target -> target.endsWith(".yaml") }
            .map { target -> File(file.parentFile, target) }

    private fun filesUnder(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .sortedBy { child -> child.name }
            .flatMap { child -> if (child.isDirectory) filesUnder(child) else listOf(child) }
            .filter { child -> child.isFile && child.name.endsWith(".yaml") }

    private fun path(file: File): String = file.relativeTo(repoRoot).path.replace(File.separatorChar, '/')

    private companion object {
        /**
         * The two ids the bottom bar stopped rendering. They stay declared in
         * `TestTags` — a tag can be declared and unwired, which is a different
         * finding — so nothing but a search of the flows keeps a flow off them.
         */
        val retiredTabIds = setOf("nav_tab_plans", "nav_tab_pomodoro")
    }
}

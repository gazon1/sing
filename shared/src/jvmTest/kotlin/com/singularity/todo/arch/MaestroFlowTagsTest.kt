package com.singularity.todo.arch

import com.singularity.todo.core.ui.TestTagsCatalog
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.assertTrue

/**
 * Every `id:` selector in a Maestro flow must resolve to an entry in
 * [TestTagsCatalog]. This is the blocking JVM equivalent of the shell-script
 * check in `Maestro/scripts/check-tags.sh`.
 *
 * The two assertions:
 * 1. Every `id:` in a flow is derivable from `TestTags.kt` (static const or
 *    dynamic function prefix).
 * 2. At least 40 ids are scanned — guards against a broken collector giving
 *    a vacuous green result.
 *
 * [calendarDay] ids (`calendar_day_YYYY_MM_DD`) and [profileItem] ids
 * (`profile_item_<slug>`) are accepted as known exceptions: neither function
 * routes through `slug()` as the terminal interpolation in its template literal,
 * so both are intentionally excluded from [TestTagsCatalog.dynamicFunctions].
 *
 * Run from repo root:
 * ```
 * ./gradlew :shared:jvmTest --tests "com.singularity.todo.arch.MaestroFlowTagsTest"
 * ```
 */
@Tag("fast")
class MaestroFlowTagsTest {

    /** Resolves to the worktree root (4 levels up from commonMain), where Maestro/ lives. */
    private val workspaceRoot: Path = run {
        val commonMainRoot = System.getProperty("commonMain.root")
            ?: error("commonMain.root system property is not set — see shared/build.gradle.kts")
        // commonMain.root = worktree/shared/src/commonMain/kotlin
        //   parent1 = worktree/shared/src/commonMain
        //   parent2 = worktree/shared/src
        //   parent3 = worktree/shared
        //   parent4 = worktree/  ← Maestro/ lives here
        Paths.get(commonMainRoot).parent.parent.parent.parent
    }

    private val idPattern = Regex("""^\s+id:\s*"?([^"#\s]+)"?\s*$""", RegexOption.MULTILINE)

    /** Matches calendar_day_YYYY_MM_DD — calendarDay() expanded form, not in dynamicFunctions(). */
    private val calendarDayPattern = Regex("""^calendar_day_\d{4}(_\d{2})?(_\d{2})?$""")

    /**
     * `profileItem(name)` expands to `"profile_item_${slug(name)}"`.
     * The function does NOT end with `${slug(...)` in the template literal
     * (it reads `${PROFILE_ITEM_PREFIX}${slug(name)}`), so it is intentionally
     * excluded from [TestTagsCatalog.dynamicFunctions].  Accept the expanded
     * form directly.
     */
    private val profileItemPattern = Regex("""^profile_item_[a-z][a-z0-9_]*$""")

    /**
     * Walks [dir] (flows or helpers) and collects every `id:` value → flow path.
     * Template expressions (`${...}`) are skipped.
     */
    private fun collectIds(dir: Path): Map<String, String> {
        if (!dir.exists()) return emptyMap()
        val out = mutableMapOf<String, String>()
        Files.walk(dir).use { stream ->
            stream.filter { it.isRegularFile() && it.extension in listOf("yaml", "yml") }
                .forEach { file ->
                    val text = file.readText()
                    idPattern.findAll(text).forEach { match ->
                        val id = match.groupValues[1]
                        if (id.isNotBlank() && !id.startsWith("\${")) {
                            out.putIfAbsent(id, file.toString())
                        }
                    }
                }
        }
        return out
    }

    @Test
    fun `every id in a Maestro flow resolves to TestTagsCatalog`() {
        val flows = workspaceRoot.resolve("Maestro/flows")
        val helpers = workspaceRoot.resolve("Maestro/helpers")
        val ids = collectIds(flows) + collectIds(helpers)

        assertTrue(ids.size > 40, "scanned ${ids.size} ids — collector likely broken (expected > 40)")

        val staticValues = TestTagsCatalog.staticTags().map { it.second }.toSet()
        val dynamicPrefixes = TestTagsCatalog.dynamicFunctions()
            .mapNotNull { it.second.substringBefore("\${").takeIf(String::isNotBlank) }
            .toSet()

        val unknown = ids.keys.filter { id ->
            id !in staticValues &&
                dynamicPrefixes.none { prefix -> id.startsWith(prefix) } &&
                !id.matches(calendarDayPattern) &&
                !id.matches(profileItemPattern)
        }

        val failureMessage = if (unknown.isEmpty()) {
            ""
        } else {
            "Unknown Maestro flow ids (${unknown.size}/${ids.size}): " +
                unknown.take(5).joinToString(", ") { "$it (e.g. ${ids[it]})" } +
                if (unknown.size > 5) {
                    " ... and ${unknown.size - 5} more"
                } else {
                    ""
                } +
                ". Fix: add a const val to TestTags.kt, or remove the flow that needs it."
        }
        assertTrue(unknown.isEmpty(), failureMessage)
    }

    /**
     * Every relative `runFlow:` path must resolve to a file that exists.
     *
     * Maestro reports a wrong relative path as `Invalid File Path at …` and then
     * fails the flow for a reason that has nothing to do with the app — five
     * flows in `flows/tasks/` and `flows/agenda/` said `../helpers/…` where the
     * helpers directory is two levels up, not one. The id check above could not
     * see it: those flows parsed fine, they just pointed at nothing, and the
     * only symptom was a red run on a device.
     *
     * Paths are resolved against the *including* flow's own directory, which is
     * what Maestro does, so a flow nested one level deeper needs `../../`.
     */
    @Test
    fun `every relative runFlow path resolves to an existing file`() {
        val flowsRoot = workspaceRoot.resolve("Maestro/flows")
        val broken = mutableListOf<String>()
        var checked = 0

        Files.walk(flowsRoot).use { stream ->
            stream.filter { it.isRegularFile() && it.extension in listOf("yaml", "yml") }
                .forEach { file ->
                    val dir = file.parent
                    RUN_FLOW.findAll(file.readText()).forEach { match ->
                        val raw = match.groupValues[1].trim()
                        // Absolute paths and expressions cannot be resolved statically.
                        if (raw.startsWith("/") || raw.contains("\${")) return@forEach
                        checked++
                        val target = dir.resolve(raw).normalize()
                        if (!target.exists()) {
                            broken += "${file.fileName}: $raw"
                        }
                    }
                }
        }

        assertTrue(
            checked >= 20,
            "scanned only $checked runFlow references — collector likely broken (expected >= 20)",
        )
        assertTrue(
            broken.isEmpty(),
            "runFlow paths that resolve to nothing (${broken.size}/$checked):\n" +
                broken.joinToString("\n") { "  - $it" } +
                "\nPaths are relative to the flow's own directory. Helpers live in " +
                "Maestro/helpers/, which is ../../ from a flow in Maestro/flows/<group>/.",
        )
    }

    private companion object {
        val RUN_FLOW = Regex("""^\s*-?\s*runFlow:\s*(\S+)\s*$""", RegexOption.MULTILINE)
    }
}

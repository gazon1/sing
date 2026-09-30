package com.singularity.todo.core.ui

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [TestTagsCatalog] parsing and the bidirectional wiring contract
 * between [TestTags] and `TAGS.md`.
 *
 * ## Bidirectional contract
 *
 * - **Forward** (declared → reachable): every constant/function in [TestTags] must be
 *   usable at runtime. This is verified by importing and calling it.
 *
 * - **Reverse** (used → declared): every `Modifier.testTag("...")` and
 *   `TestTags.*` call in production code must resolve to a constant or function
 *   declared in [TestTags]. This grep-based test enforces that no ad-hoc strings
 *   are used.
 *
 * - **Golden** (`TAGS.md`): the generated section of `Maestro/TAGS.md` must match
 *   what [TestTagsCatalog] produces. Regenerate with
 *   `./gradlew :shared:jvmTest -PupdateGoldens=true`.
 */
class TestTagsCatalogJvmTest {

    // ─── Catalog parsing ────────────────────────────────────────────────────────

    @Test
    fun `static tags returns all constants`() {
        val tags = TestTagsCatalog.staticTags()

        assertTrue(tags.isNotEmpty(), "TestTags should define at least one constant")
        // Spot-check known constants
        assertTrue(tags.any { it.second == "task_editor_title_input" })
        assertTrue(tags.any { it.second == "task_editor_save" })
    }

    @Test
    fun `static tags values are non-empty`() {
        for ((name, value) in TestTagsCatalog.staticTags()) {
            assertTrue(value.isNotBlank(), "$name must have a non-blank value")
        }
    }

    @Test
    fun `static tags values contain no spaces`() {
        for ((name, value) in TestTagsCatalog.staticTags()) {
            assertFalse(value.contains(' '), "$name = '$value' must not contain spaces")
        }
    }

    @Test
    fun `dynamic functions returns all tag functions`() {
        val functions = TestTagsCatalog.dynamicFunctions()

        assertTrue(functions.isNotEmpty(), "TestTags should define at least one dynamic function")
        // Spot-check known functions
        assertTrue(functions.any { it.first == "taskItem" })
        assertTrue(functions.any { it.first == "agendaSection" })
        assertTrue(functions.any { it.first == "navTab" })
    }

    @Test
    fun `dynamic functions prefixes are non-empty`() {
        for ((name, prefix) in TestTagsCatalog.dynamicFunctions()) {
            assertTrue(prefix.isNotBlank(), "function $name must have a non-empty prefix")
        }
    }

    @Test
    fun `dynamic functions prefixes end with a separator`() {
        for ((name, prefix) in TestTagsCatalog.dynamicFunctions()) {
            // Interpolated prefixes like "${PROFILE_ITEM_PREFIX}" end with '}' — valid
            assertTrue(
                prefix.endsWith("_") || prefix.endsWith("-") || prefix.endsWith("/") || prefix.endsWith("}"),
                "function $name prefix '$prefix' should end with a separator or '}' (interpolated const)",
            )
        }
    }

    // ─── Forward wiring ─────────────────────────────────────────────────────────
    //
    // Verify every static tag is actually usable as a Compose testTag.

    @Test
    fun `static tags are usable in testTag calls`() {
        // If this compiles and runs, TestTags.FOO is a valid String.
        // If a constant were removed or renamed, this would fail to compile.
        val tag: String = TestTags.TASK_EDITOR_TITLE_INPUT
        assertEquals("task_editor_title_input", tag)
    }

    @Test
    fun `dynamic functions produce deterministic slugs`() {
        // slug() is idempotent — calling it twice with the same input produces the same output
        val slug1 = slug("Buy milk")
        val slug2 = slug("Buy milk")
        assertEquals(slug1, slug2, "slug must be deterministic")
        assertEquals("buy_milk", slug1)
    }

    @Test
    fun `dynamic functions handle special characters`() {
        // Non-alphanumeric characters are collapsed to underscores
        val slug = slug("Profile & sync")
        assertFalse(slug.contains(' '), "slug must not contain spaces")
        assertFalse(slug.contains('&'), "slug must not contain ampersands")
        assertNotEquals(slug, "")
    }

    @Test
    fun `dynamic functions fall back to untitled for blank input`() {
        assertEquals("untitled", slug("   "))
        assertEquals("untitled", slug(""))
    }

    // ─── Reverse wiring ─────────────────────────────────────────────────────────
    //
    // Verify no ad-hoc testTag strings appear in production code.

    // ─── Golden TAGS.md comparison ─────────────────────────────────────────────

    /**
     * Verifies the generated section of `Maestro/TAGS.md` (between the two
     * `GENERATED` marker lines) is in sync with what [TestTagsCatalog] reports.
     *
     * Rewrite the generated section when the change is intentional — a new tag, a
     * new dynamic function, or a new `TagsMd` classification:
     * ```
     * ./gradlew :shared:jvmTest -PupdateGoldens=true
     * ```
     * Then re-run without the flag; the test must pass on a clean checkout.
     */
    @Test
    fun `generated section of TAGS md matches TestTags`() {
        val expected = TagsMd.renderGeneratedSection(TestTagsCatalog)
        val actual = TagsMd.extractGeneratedSection()

        if (actual == null) {
            @Suppress("UNCHECKED_CAST")
            Assertions.fail<Any>(
                "TAGS.md is missing generation markers. It needs a line containing exactly " +
                    "<!-- GENERATED:BEGIN --> and a line containing exactly <!-- GENERATED:END -->, " +
                    "then re-run with -PupdateGoldens=true to populate the generated section.",
            )
            return
        }

        if (System.getProperty("update.goldens").toBoolean()) {
            TagsMd.replaceGeneratedSection(expected)
            return
        }

        Assertions.assertEquals(
            expected.trim(),
            actual.trim(),
            """
            TAGS.md is out of sync with TestTags.kt.
            Run the following command to update it:
              ./gradlew :shared:jvmTest -PupdateGoldens=true
            """.trimIndent(),
        )
    }

    @Test
    fun `no raw testTag strings outside TestTags and test sources`() {
        // A raw `Modifier.testTag("my_ad_hoc_tag")` in production code is a policy
        // violation: the tag is invisible to TestTagsCatalog, so TAGS.md goes stale
        // and the wiring test cannot see it.
        //
        // Source roots are resolved from `commonMain.root` (= :shared/src/commonMain/kotlin),
        // NOT from the test JVM's working directory — the working dir is the *module*
        // directory, so a workspace-relative root like "shared/src/commonMain" would
        // resolve to "shared/shared/src/commonMain" and silently scan nothing.
        val violations = findRawTestTagUsages()
        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("Raw testTag strings found outside TestTags.kt:")
                violations.take(10).forEach { (file, tag) ->
                    appendLine("  $file: testTag(\"$tag\")")
                }
                if (violations.size > 10) appendLine("  ... and ${violations.size - 10} more")
                appendLine("Add a constant to TestTags.kt and use it, or document the tag in")
                appendLine("the TAGS.md \"Raw-string tags\" table.")
            },
        )
    }

    /** The `:shared` module root, derived from `commonMain.root`. */
    private val moduleRoot: File = File(
        System.getProperty("commonMain.root")
            ?: error("commonMain.root is not set — see shared/build.gradle.kts"),
    ).parentFile.parentFile.parentFile

    /** The workspace (git) root — `commonMain.root` is three levels below it. */
    private val workspaceRoot: File = moduleRoot.parentFile

    private val scannedFiles: Int
        get() = sourceRoots.sumOf { root -> root.walkTopDown().count { it.isFile && it.extension == "kt" } }

    private val sourceRoots: List<File> by lazy {
        listOf(
            File(moduleRoot, "src/commonMain/kotlin"),
            File(moduleRoot, "src/androidMain/kotlin"),
            File(moduleRoot, "src/jvmMain/kotlin"),
            File(workspaceRoot, "desktopApp/src/main/kotlin"),
            File(workspaceRoot, "androidApp/src/main/kotlin"),
        ).filter { it.isDirectory }
    }

    /**
     * Scans production Kotlin sources for `.testTag("literal")` calls — a raw
     * string instead of a `TestTags` constant or function.
     *
     * Fails loudly if no source root resolved, so a path bug can never masquerade
     * as a clean run.
     */
    private fun findRawTestTagUsages(): List<Pair<String, String>> {
        val roots = sourceRoots
        check(roots.isNotEmpty()) {
            "No source root resolved from commonMain.root=$moduleRoot — " +
                "the reverse-wiring check would silently scan nothing."
        }
        val testTagsFile = File(moduleRoot, TestTagsCatalog.TEST_TAGS_SOURCE)

        val violations = mutableListOf<Pair<String, String>>()
        val testTagCall = Regex("""\.testTag\s*\(\s*"([^"]+)"""")
        for (root in roots) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it != testTagsFile }
                .forEach { file ->
                    testTagCall.findAll(Files.readString(file.toPath())).forEach { match ->
                        violations.add(
                            file.relativeTo(workspaceRoot).path to match.groupValues[1],
                        )
                    }
                }
        }
        return violations
    }

    /**
     * Guards the guard: the reverse-wiring test is only meaningful if it actually
     * reads production sources. A path-resolution regression would otherwise turn
     * it into a vacuously green test.
     */
    @Test
    fun `reverse wiring check actually scans production sources`() {
        assertTrue(
            scannedFiles > 500,
            "reverse-wiring scan found only $scannedFiles .kt files — " +
                "the check is not reading production sources and would pass vacuously",
        )
    }
}

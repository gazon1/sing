package com.singularity.todo.arch

import com.singularity.todo.core.ui.TestTagsCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every `TestTags` constant must be applied to a composable.
 *
 * ## The defect this catches
 *
 * `TestTags` is the single registry a test author reads to find a selector. A
 * constant declared there but never passed to `Modifier.testTag(...)` is worse
 * than a missing one: the author writes `onNodeWithTag(TestTags.TASK_EDITOR_DUE_ROW)`
 * and gets a bare "could not find any node", with nothing pointing at the real
 * problem — the tag does not exist because nothing ever applies it.
 *
 * This is not hypothetical. `TASK_EDITOR_DUE_ROW`, `TASK_EDITOR_PRIORITY_ROW`,
 * `PRIORITY_OPTION_*` and `TASKS_LIST` were all declared, referenced only by
 * `SlugTest` (which asserts the slug string, not any composable), and unusable.
 * Three planned desktop flows were written against them before the gap was found.
 *
 * `scripts/find-unwired-surfaces.py` covers the inverse — code that is implemented
 * but never called. This covers the tag registry's half of the same class of
 * problem: a *name* that is published but never produced.
 *
 * ## Source of truth
 *
 * After ADR `2026-09-30-test-infra-known-gaps.md` §4 item #8, this test reads
 * from [TestTagsCatalog.staticTags] — the same single-pass scanner that resolves
 * nested `object` names to qualified paths (`EditorOverflow.ARCHIVE`). The
 * `knownUnapplied` keys below use those qualified names, so a stale entry
 * (constant was applied but the entry was not removed) is detected correctly.
 *
 * ## Allowlist
 *
 * Every entry is debt, not a shortcut, and each needs a line saying why. Add an
 * entry only together with the work that removes it; see ADR
 * `2026-09-30-testtag-registry-honesty`.
 */
class TestTagsWiringTest {

    /**
     * The `shared/` module directory. `commonMain.root` points at
     * `shared/src/commonMain/kotlin`, so three levels up is `shared/`.
     */
    private val moduleRoot: File = File(
        System.getProperty("commonMain.root")
            ?: error("commonMain.root is not set — see shared/build.gradle.kts"),
    ).parentFile.parentFile.parentFile

    private val testTagsFile = File(moduleRoot, "src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt")

    /**
     * Declared-but-unapplied constants, with the reason each is still here.
     * Keys use owner-qualified names as resolved by [TestTagsCatalog.staticTags].
     * Each must be *applied* in production code before it can be removed from
     * the registry — deleting the constant without wiring the composable would
     * leave the same trap, just undocumented.
     */
    private val knownUnapplied = mapOf(
        "TASKS_LIST" to
            "the Android bottom-bar list tag; the desktop shell has no bottom bar, " +
            "so no JVM counterpart applies it",
        "EditorOverflow.DELETE" to
            "the overflow menu renders rows through TestTags.taskAction(action), " +
            "so this constant has no call site",
        "EditorOverflow.ARCHIVE" to "same as DELETE",
        "EditorOverflow.RESTORE" to "same as DELETE",
        "EditorOverflow.PIN" to "same as DELETE",
        "EditorOverflow.UNPIN" to "same as DELETE",
        "SNACKBAR_SAVED" to
            "referenced by Maestro/flows/agenda/03-saved-views-crud.yaml, which " +
            "waits on a snackbar the screen never shows — see " +
            "deferred-backlog.md#saved-views-crud-flow-selects-a-snackbar-that-does-not-exist",
    )

    @Test
    fun every_declared_test_tag_is_applied_to_a_composable() {
        // TestTagsCatalog.staticTags() returns owner-qualified names so that
        // EditorOverflow.ARCHIVE and a hypothetical top-level ARCHIVE never collide.
        val declared = declaredConstants()
        assertTrue(declared.isNotEmpty(), "parsed no constants out of ${testTagsFile.path}")

        // Every production source set, not just commonMain: the shell chrome that
        // carries NAV_MENU_BUTTON and TASKS_FAB lives in androidMain, and a tag is
        // a tag wherever its composable does. Scanning commonMain alone reported
        // both as unapplied.
        val sourceRoots = listOf("commonMain", "androidMain", "jvmMain")
            .map { File(moduleRoot, "src/$it/kotlin") }
            .filter { it.isDirectory }

        val productionSources = sourceRoots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .filter { it != testTagsFile }
        val sourceText = productionSources.map { it.readText() }

        val unapplied = declared
            // A *_PREFIX constant is a building block for a generated tag
            // (PROFILE_ITEM_PREFIX feeds profileItem()), not a tag in itself.
            .filterNot { it.first.endsWith("_PREFIX") }
            .filter { (qualified, _) -> sourceText.none { qualified.isAppliedIn(it) } }

        val undocumented = unapplied.map { it.first }.filterNot { it in knownUnapplied }
        if (undocumented.isNotEmpty()) {
            fail(
                "TestTags constants declared but never applied to a composable: " +
                    undocumented.joinToString().let { "$it.\n" } +
                    "A test author will trust these and get 'could not find any node'. " +
                    "Either apply them with Modifier.testTag(...), or add them to " +
                    "knownUnapplied with a reason.",
            )
        }

        val stale = knownUnapplied.keys.filterNot { it in unapplied.map { (k, _) -> k } }
        if (stale.isNotEmpty()) {
            fail(
                "knownUnapplied entries that are now applied — remove them: ${stale.joinToString()}. " +
                    "Keeping a stale entry hides a real regression.",
            )
        }
    }

    /**
     * Returns owner-qualified constant names from [TestTagsCatalog.staticTags].
     * Qualified names (e.g. `EditorOverflow.ARCHIVE`) prevent same-named constants
     * in different nested objects from colliding.
     */
    @Suppress("FunctionSignature") // ktlint: = on same → WrappingRule, new line → FunctionSignature
    private fun declaredConstants(): List<Pair<String, String>> =
        TestTagsCatalog.staticTags()

    /**
     * Whether [source] references this constant as a whole identifier.
     *
     * The boundary matters: a plain `text.contains(".ARCHIVE")` also matches
     * `.ARCHIVE_NOTIFICATION_HOST`, which made `EditorOverflow.ARCHIVE` look
     * applied and hid it in `knownUnapplied`. Requiring a non-identifier
     * character after the name keeps the two apart.
     */
    private fun String.isAppliedIn(source: String): Boolean =
        Regex("""\b${Regex.escape(this)}\b""").containsMatchIn(source)
}

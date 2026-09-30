package com.singularity.todo.arch

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

    private val commonMainRoot: File = File(moduleRoot, "src/commonMain/kotlin")

    private val testTagsFile = File(commonMainRoot, "com/singularity/todo/core/ui/TestTags.kt")

    /**
     * Declared-but-unapplied constants, with the reason each is still here.
     * Each must be *applied* in production code before it can be removed from
     * the registry — deleting the constant without wiring the composable would
     * leave the same trap, just undocumented.
     */
    private val knownUnapplied = mapOf(
        "TASK_EDITOR_DUE_ROW" to
            "the due-date row in TaskEditorContent has no testTag; no flow selects it by id",
        "TASK_EDITOR_PRIORITY_ROW" to
            "the priority row in TaskEditorContent has no testTag; flows select it by label text",
        "PRIORITY_OPTION_HIGH" to
            "TaskEditorPrioritySheet rows have no per-row testTag; flows select by label",
        "PRIORITY_OPTION_MEDIUM" to "same as PRIORITY_OPTION_HIGH",
        "PRIORITY_OPTION_LOW" to "same as PRIORITY_OPTION_HIGH",
        "PRIORITY_OPTION_NONE" to "same as PRIORITY_OPTION_HIGH",
        "TASKS_LIST" to
            "the Android bottom-bar list tag has no JVM counterpart; desktop flows assert on rows",
        "ARCHIVE" to
            "EditorOverflow.ARCHIVE — the overflow menu renders rows with " +
            "TestTags.taskAction(label) instead, so this constant has no call site",
        "RESTORE" to "EditorOverflow.RESTORE — the overflow menu uses taskAction(label). " +
            "This entry was invisible before the identifier-boundary fix: " +
            "`SyncEventType.RESTORED` contains \".RESTORE\" as a substring, so the old " +
            "`contains` check reported the constant as applied.",
        "PIN" to "EditorOverflow.PIN — same: the menu uses taskAction(label), Russian labels",
        "UNPIN" to "EditorOverflow.UNPIN — same",
        "SNACKBAR_SAVED" to
            "no screen applies it; the saved-agenda editor's \"Saved\" snackbar is " +
            "asserted by text in the Maestro flow instead",
    )

    @Test
    fun every_declared_test_tag_is_applied_to_a_composable() {
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
            .filterNot { it.endsWith("_PREFIX") }
            .filter { constant -> sourceText.none { constant.isAppliedIn(it) } }

        val undocumented = unapplied.filterNot { it in knownUnapplied }
        if (undocumented.isNotEmpty()) {
            fail(
                "TestTags constants declared but never applied to a composable: " +
                    undocumented.joinToString().let { "$it.\n" } +
                    "A test author will trust these and get 'could not find any node'. " +
                    "Either apply them with Modifier.testTag(...), or add them to " +
                    "knownUnapplied with a reason.",
            )
        }

        val stale = knownUnapplied.keys.filterNot { it in unapplied }
        if (stale.isNotEmpty()) {
            fail(
                "knownUnapplied entries that are now applied — remove them: ${stale.joinToString()}. " +
                    "Keeping a stale entry hides a real regression.",
            )
        }
    }

    /** `const val NAME = ...` declarations, including those nested in the groups. */
    private fun declaredConstants(): List<String> =
        CONST_DECL.findAll(testTagsFile.readText()).map { it.groupValues[1] }.toList()

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

    private companion object {
        val CONST_DECL = Regex("""const val ([A-Z][A-Z0-9_]*)""")
    }
}

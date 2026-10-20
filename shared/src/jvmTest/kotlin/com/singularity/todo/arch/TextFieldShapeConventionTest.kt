package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every text field in the app is classified, and the classification is the truth.
 *
 * ## The decision a field makes
 *
 * A state-based field has to answer one question: **who owns the text?** The app has
 * three answers, and each has its own helper:
 *
 * | Shape | Who owns the text | Helper |
 * |---|---|---|
 * | **local** | the field itself | `rememberTextFieldState` |
 * | **bridged** | the field *and* a ViewModel-owned string, in sync | `rememberBridgedTextFieldState` |
 * | **anchored** | an outside value; the field only shows it | `rememberAnchoredTextFieldState` |
 *
 * Picking the wrong one is the defect the `singularity-todo-text-field-states` skill
 * exists to prevent — a bridged field written as a local one loses the user's last edit,
 * a local field written as a bridged one pushes text nobody wanted.
 *
 * ## What this test holds
 *
 * The classification is published in **one** place: the inventory table in
 * `.agents/skills/singularity-todo-text-field-states/SKILL.md` — one row per file, with
 * the shapes that file's fields have. This test *is* that table's reader; it holds no
 * second copy of it. A registry written twice is a registry that can disagree with itself,
 * and the copy nobody reads is the copy that rots.
 *
 * Two things are checked, and the first is the one that matters:
 *
 * 1. **Every field site is classified.** A file that uses one of the three helpers and
 *    is not in the table fails the build. A new field is a new classification, and the
 *    classification is a decision somebody has to make on purpose.
 * 2. **The table matches the code.** The shapes a file declares are the shapes its
 *    imports prove. A file that claims *local* and imports the *bridged* helper fails —
 *    the claim is what the next reader trusts. A row whose file no longer owns a field
 *    fails too: a stale row is a classification of nothing.
 *
 * ## Where the shapes come from
 *
 * The shapes are **derived from the code**, not read out of its text. A file that uses
 * the bridged helper has to import it — the helper lives in
 * `com.singularity.todo.core.ui.textfields`, and no other package can reach it
 * without an import — so the import list is the compiler's own record of which shape
 * the file uses.
 *
 * This used to be a text scan: the file's source was searched for the helper's name.
 * That is the trick the rewrite contract got rid of, and it was wrong in the same way
 * — a KDoc line naming the helper was enough to classify a file that owns no field,
 * and a helper named in a comment could hide a field that does.
 *
 * ## Why the table is not derived from the code
 *
 * The table could be computed by scanning the sources — that is what check 2 does.
 * It is written out anyway because the *act of writing it down* is the point: a
 * classification nobody wrote down is a classification nobody made. A derived table
 * would make every field in the app "classified" the moment it was written, which is
 * the state the skill's own warning is about ("a field that is not in this table has
 * not been classified yet").
 *
 * ## What it cannot check
 *
 * The derivation is per file, not per field: a file with three fields of two shapes is
 * classified as *both*, and the per-field answer lives in the skill's table (the
 * "Field" column). A field whose owner is wrong in a way the helper cannot express —
 * a bridged field whose upstream is not the ViewModel — is not visible here.
 */
@Tag("fast")
class TextFieldShapeConventionTest {

    @Test
    fun `every file with a text field is classified`() {
        val classified = registry.keys.toSet()
        val unclassified = fieldSites()
            .keys
            .filterNot { it in classified }
            .sorted()

        assertTrue(
            unclassified.isEmpty(),
            "These files own a text field and are not classified in the inventory table " +
                "of .agents/skills/singularity-todo-text-field-states/SKILL.md:\n" +
                unclassified.joinToString("\n") { "  - $it" } +
                "\n\nAdd the file to the table with the shape its fields have. The table " +
                "has one row per file: | File | Shape | Field |.",
        )
    }

    @Test
    fun `the classified shapes are the shapes the code uses`() {
        val sites = fieldSites()
        val mismatches = registry.mapNotNull { (path, declared) ->
            val used = sites[path]
            if (used == null) {
                "  - $path: classified as $declared but the file has no text field " +
                    "any more (stale row)"
            } else if (used != declared) {
                "  - $path: classified as $declared but the file uses $used"
            } else {
                null
            }
        }

        assertTrue(
            mismatches.isEmpty(),
            "The classification does not match the code:\n" +
                mismatches.joinToString("\n") + "\n",
        )
    }

    // ── the registry: the skill's inventory table ───────────────────────────────

    /**
     * The classification of every file that owns a text field, as the skill publishes it.
     *
     * The key is the path relative to `commonMain/kotlin/com/singularity/todo/`; the
     * value is the shapes the file's fields have. A file with fields of two shapes lists
     * both — `ChatScreen` has a bridged chat input and two local ones.
     */
    private val registry: Map<String, Set<String>> by lazy { skillInventory() }

    // ── the scan ───────────────────────────────────────────────────────────────

    /**
     * The files that own a text field, and the shapes they own.
     *
     * The shapes come from the files' imports — see [TextFieldShapeScan] for why an
     * import and not a mention.
     */
    private fun fieldSites(): Map<String, Set<String>> =
        TextFieldShapeScan.shapes()
            .mapValues { (_, shapes) -> shapes.map { shape -> shape.label }.toSet() }

    // ── the skill's inventory ───────────────────────────────────────────────────

    /**
     * The inventory table of the text-field skill, as (file, shapes) pairs.
     *
     * The table is a Markdown table of `| File | Shape | Field |` rows; the file cell
     * holds a backticked path and the shape cell holds the shape names, comma
     * separated. The table is found by its header, so a row of another table is never
     * read as a row of this one.
     */
    private fun skillInventory(): Map<String, Set<String>> =
        SkillTables.table("File", "Shape", "Field")
            .mapNotNull { cells ->
                val file = cells[0].removeSurrounding("`").trim()
                if (!file.endsWith(".kt")) return@mapNotNull null
                val shapes = cells[1]
                    .split(",")
                    .map { it.trim().removeSurrounding("`") }
                    .filter { it in setOf("local", "bridged", "anchored") }
                    .toSet()
                if (shapes.isEmpty()) null else file to shapes
            }
            .toMap()
}

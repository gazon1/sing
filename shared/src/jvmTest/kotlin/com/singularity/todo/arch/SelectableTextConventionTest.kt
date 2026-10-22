package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every text surface in the app is classified, and the classification is the truth.
 *
 * ## What a text surface decides
 *
 * A piece of text a person reads is one of three things:
 *
 * | Treatment | What the person gets |
 * |---|---|
 * | **selectable** | the text can be selected and copied |
 * | **linkified** | the text's links can be tapped, and it can be copied |
 * | **excluded** | neither — and the reason is written down |
 *
 * The exclusion is not a shrug. A row whose long press already opens a menu cannot
 * also start a text selection: the gesture is spoken for. That is a decision, and
 * this test is what stops it from being an accident.
 *
 * ## What this test holds
 *
 * The classification is published in **one** place: the inventory table in
 * `.agents/skills/singularity-todo-selectable-text/SKILL.md` — one row per file, with
 * the treatment the file's texts have and the reason when the answer is "excluded".
 * This test *is* that table's reader; it holds no second copy of it.
 *
 * Three things are checked:
 *
 * 1. **Every text surface is classified.** A file that uses one of the widgets and is
 *    not in the table fails the build. A new surface is a new classification.
 * 2. **The table matches the code.** The treatment a file declares is the treatment
 *    its imports prove. A file that claims *selectable* and imports nothing fails;
 *    a file that claims *excluded* and imports a widget fails harder.
 * 3. **Every row points at a file that exists.** A row for a file that is gone is a
 *    classification of nothing.
 *
 * ## Where the treatment comes from
 *
 * It is derived from the code — from the files' imports, the same argument the
 * text-field convention makes. A file that calls `SelectableText` has to import it;
 * a mention in a comment imports nothing.
 */
@Tag("fast")
class SelectableTextConventionTest {

    @Test
    fun `every file that uses a text widget is classified`() {
        val classified = registry.keys.toSet()
        val unclassified = SelectableTextScan.treatments()
            .keys
            .filterNot { it in classified }
            .sorted()

        assertTrue(
            unclassified.isEmpty(),
            "These files use a text widget and are not classified in the inventory table " +
                "of .agents/skills/singularity-todo-selectable-text/SKILL.md:\n" +
                unclassified.joinToString("\n") { "  - $it" } +
                "\n\nAdd the file to the table with the treatment its texts have. The table " +
                "has one row per file: | File | Treatment | Reason |.",
        )
    }

    @Test
    fun `the classified treatment is the treatment the code uses`() {
        val sites = SelectableTextScan.treatments()
        val mismatches = registry.mapNotNull { (path, declared) ->
            if (path !in SelectableTextScan.files()) {
                "  - $path: classified as $declared but there is no such file in commonMain " +
                    "(stale row)"
                return@mapNotNull null
            }
            val used = sites[path].orEmpty()
            if (declared == TextTreatment.EXCLUDED) {
                if (used.isEmpty()) {
                    null
                } else {
                    "  - $path: classified as excluded but the file uses $used"
                }
            } else if (used.isEmpty()) {
                "  - $path: classified as $declared but the file uses no text widget " +
                    "any more (stale row)"
            } else if (declared !in used) {
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
     * The classification of every text surface, as the skill publishes it.
     *
     * The key is the path relative to `commonMain/kotlin/com/singularity/todo/`.
     */
    private val registry: Map<String, TextTreatment> by lazy { skillInventory() }

    // ── the skill's inventory ───────────────────────────────────────────────────

    /**
     * The inventory table of the selectable-text skill, as (file, treatment) pairs.
     *
     * The table is a Markdown table of `| File | Treatment | Reason |` rows; the file
     * cell holds a backticked path and the treatment cell holds one of the three
     * treatments. The table is found by its header, so a row of another table is
     * never read as a row of this one.
     */
    private fun skillInventory(): Map<String, TextTreatment> =
        SkillTables.tableIn("singularity-todo-selectable-text/SKILL.md", "File", "Treatment", "Reason")
            .mapNotNull { cells ->
                val file = cells[0].removeSurrounding("`").trim()
                if (!file.endsWith(".kt")) return@mapNotNull null
                val treatment = cells[1]
                    .removeSurrounding("`")
                    .trim()
                    .lowercase()
                    .let { label -> TextTreatment.entries.firstOrNull { it.label == label } }
                    ?: return@mapNotNull null
                file to treatment
            }
            .toMap()
}

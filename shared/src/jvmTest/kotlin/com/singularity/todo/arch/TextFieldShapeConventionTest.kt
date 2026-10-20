package com.singularity.todo.arch

import java.io.File
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
 * The classification is published in two places, and this test is what keeps them
 * honest:
 *
 * - the **registry** below — one row per file, with the shapes that file's fields have;
 * - the **inventory table** in the skill, which is what a developer reads before adding
 *   a field.
 *
 * Three things are checked, and the first is the one that matters:
 *
 * 1. **Every field site is classified.** A file that uses one of the three helpers and
 *    is not in the registry fails the build. A new field is a new classification, and
 *    the classification is a decision somebody has to make on purpose.
 * 2. **The registry matches the code.** The shapes a file declares are the shapes its
 *    imports prove. A file that claims *local* and imports the *bridged* helper fails —
 *    the claim is what the next reader trusts.
 * 3. **The skill matches the registry.** The inventory table lists every classified
 *    file, with the same shapes.
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
 * ## Why the registry is not derived from the code
 *
 * The registry could be computed by scanning the sources — that is what check 2 does.
 * It is written out anyway because the *act of writing it down* is the point: a
 * classification nobody wrote down is a classification nobody made. A derived registry
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
        val classified = registry.keys.map { it.substringAfterLast('/') }.toSet()
        val unclassified = fieldSites()
            .keys
            .filterNot { it.substringAfterLast('/') in classified }
            .sorted()

        assertTrue(
            unclassified.isEmpty(),
            "These files own a text field and are not classified in the registry of " +
                "TextFieldShapeConventionTest:\n" +
                unclassified.joinToString("\n") { "  - $it" } +
                "\n\nAdd the file to the registry with the shape its fields have, and to " +
                "the inventory table in .agents/skills/singularity-todo-text-field-states/.",
        )
    }

    @Test
    fun `the classified shapes are the shapes the code uses`() {
        val sites = fieldSites()
        val mismatches = registry.mapNotNull { (path, declared) ->
            val used = sites[path]
            if (used == null) {
                "  - $path: classified as ${declared.shapes} but the file has no text field " +
                    "any more (stale entry)"
            } else if (used != declared.shapes) {
                "  - $path: classified as ${declared.shapes} but the file uses $used"
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

    @Test
    fun `the skill's inventory lists every classified file`() {
        val inventory = skillInventory()
        val missing = registry.keys.filter { path ->
            val name = path.substringAfterLast('/')
            inventory.none { it.first == name && it.second == registry.getValue(path).shapes }
        }.sorted()
        assertTrue(
            missing.isEmpty(),
            "The inventory in .agents/skills/singularity-todo-text-field-states/SKILL.md " +
                "does not list these files with their shapes:\n" +
                missing.joinToString("\n") { "  - $it" } +
                "\n\nThe table has one row per file: | File | Shape | Field |.",
        )
    }

    // ── the registry ────────────────────────────────────────────────────────────

    /**
     * The classification of every file that owns a text field.
     *
     * The path is relative to `commonMain/kotlin/com/singularity/todo/`, and the shapes
     * are the helpers the file's fields use. A file with fields of two shapes lists
     * both — `ChatScreen` has a bridged chat input and two local ones.
     */
    private val registry: Map<String, Classification> = mapOf(
        "core/ui/components/ChatInputBar.kt" to Classification(local = true),
        "feature/agenda/presentation/screen/SavedAgendaContent.kt" to Classification(bridged = true),
        "feature/agenda/presentation/screen/SavedAgendaScreen.kt" to Classification(local = true),
        "feature/ai/chat/ChatScreen.kt" to Classification(local = true, bridged = true),
        "feature/attachments/annotation/AttachmentAnnotationPanel.kt" to Classification(bridged = true),
        "feature/attachments/components/AttachmentsSheet.kt" to Classification(local = true),
        "feature/auth/presentation/AttachAccountScreen.kt" to Classification(local = true),
        "feature/auth/presentation/MagicLinkConfirmScreen.kt" to Classification(local = true),
        "feature/auth/AuthFormFields.kt" to Classification(local = true),
        "feature/auth/LoginScreen.kt" to Classification(local = true),
        "feature/checklist/components/ChecklistEditorSheet.kt" to Classification(local = true),
        "feature/genui/render/material3/input/TextFieldRenderer.kt" to Classification(bridged = true),
        "feature/notes/components/InternalLinkPickerSheet.kt" to Classification(local = true),
        "feature/notes/presentation/screen/LinkUrlDialog.kt" to Classification(local = true),
        "feature/notes/presentation/screen/NoteEditorScreen.kt" to Classification(bridged = true),
        "feature/notes/presentation/screen/NotesListScreen.kt" to Classification(local = true, bridged = true),
        "feature/profile/presentation/ChangePasswordScreen.kt" to Classification(local = true),
        "feature/profile/ProfileSwitcherScreen.kt" to Classification(local = true),
        "feature/projects/presentation/screen/ProjectDetailBody.kt" to Classification(local = true),
        "feature/projects/presentation/screen/ProjectDetailHero.kt" to Classification(bridged = true),
        "feature/projects/presentation/screen/ProjectEditorScreen.kt" to Classification(bridged = true),
        "feature/projects/presentation/screen/ProjectsScreen.kt" to Classification(bridged = true),
        "feature/search/presentation/SaveSearchDialog.kt" to Classification(local = true),
        "feature/search/presentation/SavedSearchesRow.kt" to Classification(anchored = true),
        "feature/search/SearchScreen.kt" to Classification(bridged = true),
        "feature/settings/screens/AiProviderSettingsScreen.kt" to Classification(
            local = true,
            bridged = true,
            anchored = true,
        ),
        "feature/settings/screens/WorkScheduleSettingsScreen.kt" to Classification(anchored = true),
        "feature/tags/components/AddTagDialog.kt" to Classification(local = true),
        "feature/tasks/presentation/components/detail/RecurrencePickerSheet.kt" to Classification(anchored = true),
        "feature/tasks/presentation/components/detail/SubtasksSection.kt" to Classification(local = true),
        "feature/tasks/presentation/components/detail/TaskDescriptionField.kt" to Classification(bridged = true),
        "feature/tasks/presentation/components/detail/TaskDetailProposalSection.kt" to Classification(local = true),
        "feature/tasks/presentation/components/detail/TaskEditorEstimateSheet.kt" to Classification(local = true),
        "feature/tasks/presentation/components/detail/TaskTitleRow.kt" to Classification(bridged = true),
        "feature/timetracking/presentation/components/TimeEntryEditorSheet.kt" to Classification(local = true),
    )

    /**
     * The shapes a file's fields have.
     *
     * @property local the file owns its text outright.
     * @property bridged the file keeps its text in sync with a string elsewhere.
     * @property anchored the file only shows a value it does not own.
     */
    private data class Classification(
        val local: Boolean = false,
        val bridged: Boolean = false,
        val anchored: Boolean = false,
    ) {
        /** The shapes as a set, for comparison with what the code uses. */
        val shapes: Set<String> = buildSet {
            if (local) add(FieldShape.LOCAL.label)
            if (bridged) add(FieldShape.BRIDGED.label)
            if (anchored) add(FieldShape.ANCHORED.label)
        }

        override fun toString(): String = shapes.sorted().joinToString("+")
    }

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
     * separated. A row that does not parse is ignored — the table is prose with a
     * shape, and the test reads the rows it understands.
     */
    private fun skillInventory(): List<Pair<String, Set<String>>> {
        val skill = File(
            System.getProperty("skills.root")
                ?: error("skills.root system property is not set — see shared/build.gradle.kts"),
            "singularity-todo-text-field-states/SKILL.md",
        )
        assertTrue(skill.isFile, "the text-field skill is missing: ${skill.absolutePath}")

        return skill.readLines()
            .filter { it.trimStart().startsWith("|") }
            .map { it.trim().trim('|') }
            .map { it.split("|").map { cell -> cell.trim() } }
            .filter { cells -> cells.size >= 2 }
            .mapNotNull { cells ->
                val file = cells[0].removeSurrounding("`").trim()
                if (!file.endsWith(".kt")) return@mapNotNull null
                val shapes = cells[1]
                    .split(",")
                    .map { it.trim().removeSurrounding("`") }
                    .filter { it in setOf("local", "bridged", "anchored") }
                    .toSet()
                if (shapes.isEmpty()) null else file.substringAfterLast('/') to shapes
            }
            .toList()
    }
}

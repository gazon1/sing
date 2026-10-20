package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Who may rewrite a text field, and how the field learns about it.
 *
 * ## The question the shape does not answer
 *
 * [TextFieldShapeConventionTest] says who *owns* a field's text: the field itself
 * (*local*), the field and a ViewModel-owned string (*bridged*), or an outside
 * value the field only shows (*anchored*). The shape says nothing about what
 * happens when somebody *else* writes the value — a proposal applied to the note,
 * a sync from another device, a server writing a GenUI surface.
 *
 * That is where the loss happens. The editor owns the content it shows, so a
 * rewrite it did not make reaches the field only through the upstream's value.
 * The bridge's echo guard carries a rewrite the field never produced — but a
 * rewrite that lands on a value the field *did* produce earlier looks exactly
 * like a lagging upstream, and the guard drops it. The revision token is the way
 * out: the upstream that knows it is rewriting says so, and the guard is lifted
 * for that value.
 *
 * ## What this test holds
 *
 * The contract is published in two places, and this test keeps them honest:
 *
 * - the **registry** below — one row per bridged field site, with the upstream and
 *   the mechanism that carries the rewrite;
 * - the **rewrite table** in the skill, which is what a developer reads before
 *   adding a field.
 *
 * Three things are checked:
 *
 * 1. **Every bridged field site has a row.** A file that calls the bridged helper
 *    and is not in the registry fails the build. A new bridged field is a new
 *    answer to "who may rewrite this?", and the answer is a decision somebody has
 *    to make on purpose.
 * 2. **The registry is true of the code.** A row that claims the field carries a
 *    revision must find one in the file. The claim is what the next reader trusts.
 * 3. **The skill matches the registry.**
 *
 * ## What it cannot check
 *
 * The scan is lexical, and it sees files, not fields: a file with two bridged
 * fields of different upstreams is one row, and the per-field answer lives in
 * the skill's table (the "Field" column). Whether a rewrite can land on a value
 * the field produced is a property of the *upstream*, not of the file, and the
 * table states it rather than deriving it.
 */
@Tag("fast")
class TextFieldRewriteContractTest {

    @Test
    fun `every bridged field site has a rewrite contract`() {
        val bridged = bridgedSites()
        val unclassified = bridged.filterNot { it in registry }.sorted()

        assertTrue(
            unclassified.isEmpty(),
            "These files own a bridged text field and have no rewrite contract in " +
                "the registry of TextFieldRewriteContractTest:\n" +
                unclassified.joinToString("\n") { "  - $it" } +
                "\n\nAdd the file to the registry with the upstream that may rewrite it " +
                "and the mechanism that carries the rewrite, and to the rewrite table " +
                "in .agents/skills/singularity-todo-text-field-states/.",
        )
    }

    @Test
    fun `a field that claims a revision carries one`() {
        val claims = registry.filterValues { it.answer == Answer.VALUE_AND_TOKEN }
        val missing = claims.filter { (path, contract) ->
            val source = sourceOf(path)
            !source.contains("revision =")
        }.keys.sorted()

        assertTrue(
            missing.isEmpty(),
            "These fields are registered as carrying a rewrite onto a value the field " +
                "produced, but the file passes no revision to the bridge:\n" +
                missing.joinToString("\n") { "  - $it" } +
                "\n\nA bridged field whose upstream rewrites it must pass a revision: " +
                "without it the bridge's echo guard treats the rewrite as a lagging " +
                "upstream and drops it.",
        )
    }

    @Test
    fun `the skill's rewrite table lists every registered field site`() {
        val table = skillRewriteTable()
        val missing = registry.keys.filter { path ->
            val name = path.substringAfterLast('/')
            table.none { it.first == name && it.second == registry.getValue(path).answer }
        }.sorted()

        assertTrue(
            missing.isEmpty(),
            "The rewrite table in .agents/skills/singularity-todo-text-field-states/" +
                "SKILL.md does not list these files with their contract:\n" +
                missing.joinToString("\n") { "  - $it" } +
                "\n\nThe table has one row per file: | File | Field | Upstream | Answer |.",
        )
    }

    // ── the registry ───────────────────────────────────────────────────────────

    /**
     * What a rewrite costs a field, when it happens.
     *
     * - [Answer.NONE] — nothing outside the field writes its value. The field
     *   owns the text; the upstream only holds what the field produced.
     * - [Answer.VALUE] — the upstream rewrites the value, and the rewrite is
     *   carried by the value itself: the bridge's echo guard applies a value the
     *   field never produced. A rewrite that lands on a value the field *did*
     *   produce is not carried — see the note on the table.
     * - [Answer.VALUE_AND_TOKEN] — the upstream rewrites the value, and the
     *   rewrite can land on a value the field produced, so the field is told it
     *   is a rewrite: it passes a revision, and the guard is lifted for it.
     */
    private enum class Answer {
        NONE,
        VALUE,
        VALUE_AND_TOKEN,
        ;

        override fun toString(): String = when (this) {
            NONE -> "none"
            VALUE -> "the value itself"
            VALUE_AND_TOKEN -> "the value and a revision token"
        }
    }

    /**
     * The rewrite contract of every file that owns a bridged text field.
     *
     * The path is relative to `commonMain/kotlin/com/singularity/todo/`.
     */
    private val registry: Map<String, Contract> = mapOf(
        // The saved agenda's name. Nothing renames a saved agenda from outside
        // the screen: the ViewModel holds what the field produced.
        "feature/agenda/presentation/screen/SavedAgendaContent.kt" to Contract(
            fields = "the saved agenda's name",
            upstream = "the agenda ViewModel",
            answer = Answer.NONE,
        ),
        // The chat input. The ViewModel clears it when a message is sent — a
        // value the field never produced.
        "feature/ai/chat/ChatScreen.kt" to Contract(
            fields = "the chat input",
            upstream = "the chat ViewModel, which clears it after sending",
            answer = Answer.VALUE,
        ),
        // The annotation draft. It is seeded when the editor opens and edited by
        // the user afterwards; nothing replaces it while the editor is open.
        "feature/attachments/annotation/AttachmentAnnotationPanel.kt" to Contract(
            fields = "the annotation draft (start, end, quote, note)",
            upstream = "the annotation ViewModel, which seeds it on open",
            answer = Answer.NONE,
        ),
        // The surface's field. The server writes it, and it can write a value
        // the field produced — so the renderer passes the server's revision.
        "feature/genui/render/material3/input/TextFieldRenderer.kt" to Contract(
            fields = "the surface's text field",
            upstream = "the surface's server, through the data context",
            answer = Answer.VALUE_AND_TOKEN,
        ),
        // The note's title and body. A proposal applied to the note, a sync from
        // another device — either rewrites the note under the editor, and the
        // rewrite can land on a value the field produced. The title passes the
        // draft's revision; the body is re-seeded from it.
        "feature/notes/presentation/screen/NoteEditorScreen.kt" to Contract(
            fields = "the note's title, the note's body",
            upstream = "the note itself (a proposal applied to it, a sync)",
            answer = Answer.VALUE_AND_TOKEN,
        ),
        // The notes query. The ViewModel clears it.
        "feature/notes/presentation/screen/NotesListScreen.kt" to Contract(
            fields = "the notes search query",
            upstream = "the notes ViewModel, which clears it",
            answer = Answer.VALUE,
        ),
        // The project's name and description. The ViewModel observes the project,
        // so a synced value reaches the field as a value it never produced.
        "feature/projects/presentation/screen/ProjectDetailHero.kt" to Contract(
            fields = "the project's name, the project's description",
            upstream = "the project ViewModel, which observes the project",
            answer = Answer.VALUE,
        ),
        // The project editor's drafts. The editor holds what the user typed.
        "feature/projects/presentation/screen/ProjectEditorScreen.kt" to Contract(
            fields = "the project's name, the project's description",
            upstream = "the project editor ViewModel",
            answer = Answer.NONE,
        ),
        // The projects query. The ViewModel clears it.
        "feature/projects/presentation/screen/ProjectsScreen.kt" to Contract(
            fields = "the projects search query",
            upstream = "the projects ViewModel, which clears it",
            answer = Answer.VALUE,
        ),
        // The search query. The ViewModel clears it.
        "feature/search/SearchScreen.kt" to Contract(
            fields = "the search query",
            upstream = "the search ViewModel, which clears it",
            answer = Answer.VALUE,
        ),
        // The AI provider's settings. They are loaded from disk and nothing
        // rewrites them while the screen is open.
        "feature/settings/screens/AiProviderSettingsScreen.kt" to Contract(
            fields = "the base URL, the model, the system prompt",
            upstream = "the settings ViewModel, which loads them from disk",
            answer = Answer.NONE,
        ),
        // The task's description. The coordinator observes the task, so a synced
        // value reaches the field as a value it never produced.
        "feature/tasks/presentation/components/detail/TaskDescriptionField.kt" to Contract(
            fields = "the task's description",
            upstream = "the task coordinator, which observes the task",
            answer = Answer.VALUE,
        ),
        // The task's title. The coordinator observes the task.
        "feature/tasks/presentation/components/detail/TaskTitleRow.kt" to Contract(
            fields = "the task's title",
            upstream = "the task coordinator, which observes the task",
            answer = Answer.VALUE,
        ),
    )

    /**
     * The rewrite contract of one file.
     *
     * @property fields the fields the file owns that the contract is about.
     * @property upstream who may write the value.
     * @property answer what carries the rewrite.
     */
    private data class Contract(val fields: String, val upstream: String, val answer: Answer)

    // ── the scan ───────────────────────────────────────────────────────────────

    /**
     * The files that call the bridged helper.
     *
     * The helper's own definition is excluded: `TextFieldStateBridge.kt` *is* the
     * helper, not a field site.
     */
    private fun bridgedSites(): Set<String> {
        val root = SourceScan.commonMainRoot()
        val helperFile = "core/ui/components/TextFieldStateBridge.kt"
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .mapNotNull { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (relative == helperFile) return@mapNotNull null
                if (!file.readText().contains(BRIDGED_CALL)) null else relative
            }
            .toSet()
    }

    /** The source of a file the registry names. */
    private fun sourceOf(path: String): String {
        val root = SourceScan.commonMainRoot()
        val file = File(root, path)
        assertTrue(file.isFile, "the registry names a file that does not exist: $path")
        return file.readText()
    }

    // ── the skill's rewrite table ──────────────────────────────────────────────

    /**
     * The rewrite table of the text-field skill, as (file, answer) pairs.
     *
     * The table is a Markdown table of `| File | Field | Upstream | Answer |` rows;
     * the file cell holds a backticked path and the answer cell holds the answer's
     * name. A row that does not parse is ignored — the table is prose with a shape,
     * and the test reads the rows it understands.
     */
    private fun skillRewriteTable(): List<Pair<String, Answer>> {
        val skill = File(
            System.getProperty("skills.root")
                ?: error("skills.root system property is not set — see shared/build.gradle.kts"),
            "singularity-todo-text-field-states/SKILL.md",
        )
        assertTrue(skill.isFile, "the text-field skill is missing: ${skill.absolutePath}")

        val answers = Answer.entries.associateBy { it.toString() }
        return skill.readLines()
            .filter { it.trimStart().startsWith("|") }
            .map { it.trim().trim('|') }
            .map { it.split("|").map { cell -> cell.trim() } }
            .filter { cells -> cells.size >= 4 }
            .mapNotNull { cells ->
                val file = cells[0].removeSurrounding("`").trim()
                if (!file.endsWith(".kt")) return@mapNotNull null
                val answer = answers[cells[3].removeSurrounding("`").trim()] ?: return@mapNotNull null
                file.substringAfterLast('/') to answer
            }
            .toList()
    }

    private companion object {
        const val BRIDGED_CALL = "rememberBridgedTextFieldState("
    }
}

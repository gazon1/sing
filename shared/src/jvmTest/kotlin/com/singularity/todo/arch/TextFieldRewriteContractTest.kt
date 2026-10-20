package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Who may rewrite a text field, and how the code says so.
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
 * like a lagging upstream, and the guard drops it.
 *
 * ## The answer is a type now
 *
 * The answer used to live in a registry: a table of every bridged field site,
 * the upstream for each, and a test that read the *source text* of every call
 * site to check the table was true. Three failures were possible and all three
 * were silent — a field whose upstream owned the value passed no counter, a
 * field passed a counter that never moved (`revision = 0`), and a field forgot
 * the question altogether because the parameter carrying the answer had a
 * default.
 *
 * Now the answer is in the type of the source the bridge is handed:
 *
 * - a **static** source (`StaticText`) says the upstream replaces the value only
 *   with one the field never produced, so the echo guard is enough;
 * - an **owned** source (`OwnedText`) says the upstream may replace the value
 *   with anything, and it carries the count of the replacements.
 *
 * The counter is not a parameter of the bridge any more, so a call site cannot
 * freeze it and cannot forget the question: the source is required, and its type
 * says what it means.
 *
 * ## What this test holds
 *
 * The contract is published in two places, and this test keeps them honest:
 *
 * - the **registry** below — one row per bridged field site, with the upstream,
 *   the kind of source the site is handed, and the owner that builds it;
 * - the **rewrite table** in the skill, which is what a developer reads before
 *   adding a field.
 *
 * Five things are checked:
 *
 * 1. **Every bridged field site has a row.** A file that calls the bridged helper
 *    and is not in the registry fails the build. A new bridged field is a new
 *    answer to "who may rewrite this?", and the answer is a decision somebody has
 *    to make on purpose.
 * 2. **The owner's source is of the kind the row claims.** The row names the file
 *    that builds the source; that file must declare a source of the claimed kind.
 * 3. **An owned row names a counter that exists and moves.** The file that declares
 *    the counter must declare it, and the file that advances it must advance it —
 *    a counter that never moves says "this is a rewrite" about nothing.
 * 4. **A field site does not build its own source.** The answer is the owner's to
 *    give; a site that constructs the source itself is answering a question it
 *    cannot know the answer to. The GenUI renderer is the one documented
 *    exception: the owner there is a server, and the renderer is the boundary
 *    where the server's value and the server's counter are read.
 * 5. **The bridge takes no revision argument.** The counter is the source's
 *    property, not something a call site can write down.
 *
 * ## Where the evidence comes from
 *
 * The registry makes claims about the code, and every claim is checked against the
 * code's *structure*, not against its text. A file that uses the bridged helper has
 * to import it — the helper lives in `com.singularity.todo.core.ui.textfields`, and
 * no other package can reach it without an import — so the import list is the
 * compiler's own record of which files are bridged sites. The owner's source is a
 * property with a type, the counter is a property with a name, the bridge's
 * parameters are a list of names.
 *
 * The two checks that cannot be structural — a counter that *moves*, and a site that
 * *constructs* a source — are read from the declarations of the file, with the
 * comments stripped. Konsist 0.17.3 has no API for call sites, so the text of a
 * declaration is the finest grain available; what it cannot be fooled by is a
 * comment outside the declaration, and a comment inside one.
 *
 * ## What it cannot check
 *
 * The scan is per file, not per field: a file with two bridged fields of different
 * upstreams is one row, and the per-field answer lives in the skill's table (the
 * "Field" column). Whether a rewrite can land on a value the field produced is a
 * property of the *upstream*, not of the file, and the table states it rather than
 * deriving it.
 *
 * The scan can also not tell whether the counter actually moves at *runtime* — only
 * that the field is handed an owned source rather than a static one, and that the
 * file that owns the counter contains the increment. That the counter advances is
 * the draft state's own contract, proved in `TaskDetailDraftStateTest`,
 * `ProjectDetailDraftStateTest` and `NoteRewriteWatcherTest`.
 */
@Tag("fast")
class TextFieldRewriteContractTest {

    @Test
    fun `every bridged field site has a rewrite contract`() {
        val bridged = bridgedSites()
        val unclassified = bridged.filterNot { site -> registry.any { it.site == site } }.sorted()

        assertTrue(
            unclassified.isEmpty(),
            "These files own a bridged text field and have no rewrite contract in " +
                "the registry of TextFieldRewriteContractTest:\n" +
                unclassified.joinToString("\n") { "  - $it" } +
                "\n\nAdd the file to the registry with the upstream that may rewrite it " +
                "and the kind of source it is handed, and to the rewrite table " +
                "in .agents/skills/singularity-todo-text-field-states/.",
        )
    }

    @Test
    fun `the owner a row names declares a source of that kind`() {
        val wrong = registry.mapNotNull { row ->
            val kind = row.kind.sourceType
            // A row that builds its own source — the GenUI renderer, whose owner is a
            // server — has no property to declare: the source is constructed where the
            // server's value and the server's count are read. It still has to name the
            // type, or the construction would not compile.
            val declared = if (row.buildsSource) {
                TextFieldShapeScan.imports(row.owner).contains(SOURCE_IMPORT + kind)
            } else {
                TextFieldShapeScan.properties(row.owner).any { property ->
                    property.declaresSourceOf(kind)
                }
            }
            if (declared) return@mapNotNull null
            "$row.site -> ${row.owner} declares no $kind source"
        }.sorted()

        assertTrue(
            wrong.isEmpty(),
            "These rows claim a source of one kind, but the file that builds the " +
                "source declares another:\n" +
                wrong.joinToString("\n") { "  - $it" } +
                "\n\nThe owner builds the source, and its type is the answer to " +
                "\"who may rewrite this field?\".",
        )
    }

    @Test
    fun `an owned row names a counter that is declared`() {
        val missing = registry.filter { it.kind == Kind.OWNED }.mapNotNull { row ->
            val counter = row.counter
            if (counter.isEmpty()) return@mapNotNull "$row.site -> names no counter file"
            val declared = TextFieldShapeScan.properties(counter).any { property ->
                property.name.contains("revision", ignoreCase = true)
            }
            if (declared) return@mapNotNull null
            "$row.site -> $counter declares no revision"
        }.sorted()

        assertTrue(
            missing.isEmpty(),
            "These fields are handed an owned source, but the file that owns the " +
                "counter does not declare one:\n" +
                missing.joinToString("\n") { "  - $it" } +
                "\n\nName the file that declares the counter in the registry's Counter " +
                "column, and in the rewrite table in " +
                ".agents/skills/singularity-todo-text-field-states/SKILL.md.",
        )
    }

    @Test
    fun `an owned row names a counter that advances`() {
        val frozen = registry.filter { it.kind == Kind.OWNED }.mapNotNull { row ->
            val advancer = row.advances
            if (advancer.isEmpty()) return@mapNotNull "$row.site -> names no file that advances the counter"
            val advances = TextFieldShapeScan.declarationTexts(advancer).any { text ->
                ADVANCE.containsMatchIn(KotlinText.stripComments(text))
            }
            if (advances) return@mapNotNull null
            "$row.site -> $advancer never advances the counter"
        }.sorted()

        assertTrue(
            frozen.isEmpty(),
            "These fields are handed an owned source, but nothing advances the counter " +
                "it carries — a counter that never moves says \"this is a rewrite\" " +
                "about nothing:\n" +
                frozen.joinToString("\n") { "  - $it" } +
                "\n\nName the file that advances the counter in the registry's Advances " +
                "column.",
        )
    }

    @Test
    fun `a field site does not build its own source`() {
        val built = registry.filterNot { it.buildsSource }.mapNotNull { row ->
            val constructed = TextFieldShapeScan.functions(row.site)
                .filterNot { function -> function.isPreview() }
                .mapNotNull { function ->
                    val code = KotlinText.stripComments(function.text)
                    CONSTRUCTION.find(code)?.value
                }
            if (constructed.isEmpty()) return@mapNotNull null
            "$row.site -> ${constructed.joinToString(", ")}"
        }.sorted()

        assertTrue(
            built.isEmpty(),
            "These field sites build the source they hand the bridge:\n" +
                built.joinToString("\n") { "  - $it" } +
                "\n\nThe source is the answer to \"who may rewrite this field?\", and only " +
                "the owner knows the answer. The owner builds the source; the field " +
                "passes it through.",
        )
    }

    @Test
    fun `the bridge takes no revision argument`() {
        val offending = TextFieldShapeScan.functions(BRIDGE_FILE)
            .filter { it.name == BRIDGED_HELPER }
            // The bridge's own implementation reads the count from the source; only a
            // *public* declaration would let a call site write one down.
            .filterNot { it.hasPrivateModifier }
            .mapNotNull { function ->
                function.parameters.firstOrNull { it.name == "revision" }?.name
            }
            .toList()

        assertTrue(
            offending.isEmpty(),
            "The bridge's public API takes a revision argument:\n" +
                offending.joinToString("\n") { "  - $it" } +
                "\n\nThe counter is a property of the source, not an argument of the " +
                "call: an argument can be frozen at the call site, a property cannot.",
        )
    }

    @Test
    fun `the skill's rewrite table lists every registered field site`() {
        val table = skillRewriteTable()
        val missing = registry.mapNotNull { row ->
            val name = row.site.substringAfterLast('/')
            val found = table.any {
                it.site == name && it.source == row.kind.sourceType &&
                    it.counter == row.counter && it.advances == row.advances
            }
            if (found) null else "$row.site -> ${row.kind.sourceType} -> ${row.counter} -> ${row.advances}"
        }.sorted()

        assertTrue(
            missing.isEmpty(),
            "The rewrite table in .agents/skills/singularity-todo-text-field-states/" +
                "SKILL.md does not list these files with their contract:\n" +
                missing.joinToString("\n") { "  - $it" } +
                "\n\nThe table has one row per file: " +
                "| File | Field | Upstream | Source | Counter | Advances |.",
        )
    }

    // ── the registry ───────────────────────────────────────────────────────────

    /**
     * The kind of source a field site is handed.
     *
     * - [Kind.STATIC] — the upstream replaces the value only with one the field
     *   never produced. The bridge's echo guard applies it as it is.
     * - [Kind.OWNED] — the upstream owns the value and may replace it with
     *   anything, including a value the field itself produced. The source
     *   carries the count of the replacements, and the bridge lifts its echo
     *   guard for the value that arrives with a new count.
     */
    private enum class Kind(val sourceType: String) {
        STATIC("StaticText"),
        OWNED("OwnedText"),
    }

    /**
     * The rewrite contract of one bridged field site.
     *
     * @property site the file that owns the bridged field, relative to
     *   `commonMain/kotlin/com/singularity/todo/`.
     * @property fields the fields the file owns that the contract is about.
     * @property upstream who may write the value.
     * @property kind the kind of source the site is handed.
     * @property owner the file that builds the source.
     * @property counter the file that declares the revision counter, relative to
     *   `commonMain/kotlin/com/singularity/todo/`. Empty for a static row.
     * @property advances the file that advances the counter. Empty for a static row.
     * @property buildsSource whether the site builds the source itself — the
     *   GenUI renderer, whose owner is a server the renderer does not own.
     */
    private data class Row(
        val site: String,
        val fields: String,
        val upstream: String,
        val kind: Kind,
        val owner: String,
        val counter: String = "",
        val advances: String = "",
        val buildsSource: Boolean = false,
    )

    /**
     * The rewrite contract of every file that owns a bridged text field.
     *
     * A file may have more than one row: the task title and the task description
     * are served by two upstreams — the create draft and the detail draft — and
     * the two are different kinds of upstream.
     */
    private val registry: List<Row> = listOf(
        // The saved agenda's name. Nothing renames a saved agenda from outside
        // the screen: the ViewModel holds what the field produced.
        Row(
            site = "feature/agenda/presentation/screen/SavedAgendaContent.kt",
            fields = "the saved agenda's name",
            upstream = "the agenda ViewModel",
            kind = Kind.STATIC,
            owner = "feature/agenda/presentation/viewmodel/SavedAgendaViewModel.kt",
        ),
        // The chat input. The ViewModel clears it when a message is sent — a
        // value the field never produced.
        Row(
            site = "feature/ai/chat/ChatScreen.kt",
            fields = "the chat input",
            upstream = "the chat ViewModel, which clears it after sending",
            kind = Kind.STATIC,
            owner = "feature/ai/chat/ChatViewModel.kt",
        ),
        // The annotation draft. It is seeded when the editor opens and edited by
        // the user afterwards; nothing replaces it while the editor is open.
        Row(
            site = "feature/attachments/annotation/AttachmentAnnotationPanel.kt",
            fields = "the annotation draft (start, end, quote, note)",
            upstream = "the annotation ViewModel, which seeds it on open",
            kind = Kind.STATIC,
            owner = "feature/attachments/annotation/AttachmentAnnotationViewModel.kt",
        ),
        // The surface's field. The server writes it, and it can write a value
        // the field produced — so the renderer hands the bridge an owned source
        // carrying the server's count. The renderer builds it: the owner is a
        // server, and the renderer is the boundary that reads its value and its
        // counter.
        Row(
            site = "feature/genui/render/material3/input/TextFieldRenderer.kt",
            fields = "the surface's text field",
            upstream = "the surface's server, through the data context",
            kind = Kind.OWNED,
            owner = "feature/genui/render/material3/input/TextFieldRenderer.kt",
            counter = "feature/genui/surface/SurfaceController.kt",
            advances = "feature/genui/surface/SurfaceController.kt",
            buildsSource = true,
        ),
        // The note's title. A proposal applied to the note, a sync from another
        // device — either rewrites the note under the editor, and the rewrite
        // can land on a value the field produced. The editor counts the
        // replacements, and the title is handed an owned source.
        Row(
            site = "feature/notes/presentation/screen/NoteEditorScreen.kt",
            fields = "the note's title",
            upstream = "the note itself (a proposal applied to it, a sync)",
            kind = Kind.OWNED,
            owner = "feature/notes/Ids.kt",
            counter = "feature/notes/Ids.kt",
            advances = "feature/notes/presentation/viewmodel/NoteEditor.kt",
        ),
        // The notes query. The ViewModel clears it.
        Row(
            site = "feature/notes/presentation/screen/NotesListScreen.kt",
            fields = "the notes search query",
            upstream = "the notes ViewModel, which clears it",
            kind = Kind.STATIC,
            owner = "feature/notes/Ids.kt",
        ),
        // The project's name and description. The ViewModel observes the project,
        // so a synced value reaches the field as a value it never produced — and a
        // rewrite can land on a name the field did produce, so the draft's counter
        // travels with the source.
        Row(
            site = "feature/projects/presentation/screen/ProjectDetailHero.kt",
            fields = "the project's name, the project's description",
            upstream = "the project ViewModel, which observes the project",
            kind = Kind.OWNED,
            owner = "feature/projects/presentation/viewmodel/ProjectDetailDraftState.kt",
            counter = "feature/projects/presentation/viewmodel/ProjectDetailDraftState.kt",
            advances = "feature/projects/presentation/viewmodel/ProjectDetailDraftState.kt",
        ),
        // The project editor's drafts. The editor holds what the user typed.
        Row(
            site = "feature/projects/presentation/screen/ProjectEditorScreen.kt",
            fields = "the project's name, the project's description",
            upstream = "the project editor ViewModel",
            kind = Kind.STATIC,
            owner = "feature/projects/presentation/state/ProjectEditorUiState.kt",
        ),
        // The projects query. The ViewModel clears it.
        Row(
            site = "feature/projects/presentation/screen/ProjectsScreen.kt",
            fields = "the projects search query",
            upstream = "the projects ViewModel, which clears it",
            kind = Kind.STATIC,
            owner = "feature/projects/presentation/state/ProjectsUiState.kt",
        ),
        // The search query. The ViewModel clears it.
        Row(
            site = "feature/search/SearchScreen.kt",
            fields = "the search query",
            upstream = "the search ViewModel, which clears it",
            kind = Kind.STATIC,
            owner = "feature/search/SearchViewModel.kt",
        ),
        // The AI provider's settings. They are loaded from disk and nothing
        // rewrites them while the screen is open.
        Row(
            site = "feature/settings/screens/AiProviderSettingsScreen.kt",
            fields = "the base URL, the model, the system prompt",
            upstream = "the settings ViewModel, which loads them from disk",
            kind = Kind.STATIC,
            owner = "feature/settings/SettingsUiState.kt",
        ),
        // The task's description, in the detail screen. The coordinator observes
        // the task, so a synced value reaches the field as a value it never
        // produced — and a rewrite can land on a description the field did
        // produce, so the draft's counter travels with the source.
        Row(
            site = "feature/tasks/presentation/components/detail/TaskDescriptionField.kt",
            fields = "the task's description (detail)",
            upstream = "the task coordinator, which observes the task",
            kind = Kind.OWNED,
            owner = "feature/tasks/presentation/state/TaskDetailUiState.kt",
            counter = "feature/tasks/presentation/viewmodel/TaskDetailDraftState.kt",
            advances = "feature/tasks/presentation/viewmodel/TaskDetailDraftState.kt",
        ),
        // The task's description, in the create screen. The draft is seeded once
        // and afterwards holds what the user typed.
        Row(
            site = "feature/tasks/presentation/components/detail/TaskDescriptionField.kt",
            fields = "the task's description (create)",
            upstream = "the create draft, which seeds it once",
            kind = Kind.STATIC,
            owner = "feature/tasks/presentation/state/TaskCreateUiState.kt",
        ),
        // The task's title, in the detail screen. The coordinator observes the task.
        Row(
            site = "feature/tasks/presentation/components/detail/TaskTitleRow.kt",
            fields = "the task's title (detail)",
            upstream = "the task coordinator, which observes the task",
            kind = Kind.OWNED,
            owner = "feature/tasks/presentation/state/TaskDetailUiState.kt",
            counter = "feature/tasks/presentation/viewmodel/TaskDetailDraftState.kt",
            advances = "feature/tasks/presentation/viewmodel/TaskDetailDraftState.kt",
        ),
        // The task's title, in the create screen. The draft is seeded once and
        // afterwards holds what the user typed.
        Row(
            site = "feature/tasks/presentation/components/detail/TaskTitleRow.kt",
            fields = "the task's title (create)",
            upstream = "the create draft, which seeds it once",
            kind = Kind.STATIC,
            owner = "feature/tasks/presentation/state/TaskCreateUiState.kt",
        ),
    )

    // ── the scan ───────────────────────────────────────────────────────────────

    /**
     * The files that own a bridged text field.
     *
     * Derived from the files' imports: a file that uses the bridged helper has to
     * import it, because the helper lives in its own package. The helper's own
     * definition is in that package too, so it is not a field site.
     */
    private fun bridgedSites(): Set<String> =
        TextFieldShapeScan.shapes()
            .filterValues { shapes -> FieldShape.BRIDGED in shapes }
            .keys

    // ── the skill's rewrite table ──────────────────────────────────────────────

    /**
     * One row of the skill's rewrite table.
     *
     * @property site the file, without its path.
     * @property source the kind of source the code uses.
     * @property counter the file that declares the revision, empty for a static row.
     * @property advances the file that advances the revision, empty for a static row.
     */
    private data class Published(val site: String, val source: String, val counter: String, val advances: String)

    /**
     * The rewrite table of the text-field skill.
     *
     * The table is a Markdown table of
     * `| File | Field | Upstream | Source | Counter | Advances |` rows; the file cell
     * holds a backticked path, the source cell holds the kind of source the code uses,
     * and the counter and advances cells hold the files that declare and advance the
     * revision (or `-`). A row that does not parse is ignored — the table is prose with
     * a shape, and the test reads the rows it understands.
     */
    private fun skillRewriteTable(): List<Published> {
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
            .filter { cells -> cells.size >= 6 }
            .mapNotNull { cells ->
                val file = cells[0].removeSurrounding("`").trim()
                if (!file.endsWith(".kt")) return@mapNotNull null
                val source = cells[3].removeSurrounding("`").trim()
                if (source !in Kind.entries.map { kind -> kind.sourceType }) return@mapNotNull null
                Published(
                    site = file.substringAfterLast('/'),
                    source = source,
                    counter = cells[4].removeSurrounding("`").trim().pathOrEmpty(),
                    advances = cells[5].removeSurrounding("`").trim().pathOrEmpty(),
                )
            }
            .toList()
    }

    /** A cell that holds a path, or nothing. */
    private fun String.pathOrEmpty(): String =
        if (this == "-" || isEmpty()) "" else this

    private companion object {
        /** The bridge, the one file that holds the helper itself. */
        const val BRIDGE_FILE = "core/ui/textfields/TextFieldStateBridge.kt"

        /** The name of the bridged helper — the function the bridge exposes. */
        const val BRIDGED_HELPER = "rememberBridgedTextFieldState"

        /** Where the two kinds of source live. */
        const val SOURCE_IMPORT = "com.singularity.todo.core.ui.textfields."

        /** A source that is constructed. */
        val CONSTRUCTION = Regex("""(?:StaticText|OwnedText)\(""")

        /** A counter that advances. */
        val ADVANCE = Regex("""revision[^\n]*\+\s*1""", RegexOption.IGNORE_CASE)
    }
}

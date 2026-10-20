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
 * ## What it cannot check
 *
 * The scan is lexical, and it sees files, not fields: a file with two bridged
 * fields of different upstreams is one row, and the per-field answer lives in the
 * skill's table (the "Field" column). Whether a rewrite can land on a value the
 * field produced is a property of the *upstream*, not of the file, and the table
 * states it rather than deriving it.
 *
 * The lexical scan can also not tell whether the counter actually moves at
 * runtime — only that the field is handed an owned source rather than a static
 * one. That the counter advances is the draft state's own contract, proved in
 * `TaskDetailDraftStateTest`, `ProjectDetailDraftStateTest` and
 * `NoteRewriteWatcherTest`.
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
            val owner = sourceOf(row.owner)
            val kind = row.kind.sourceType
            // A row that builds its own source — the GenUI renderer, whose owner is a
            // server — has no property to declare: the source is constructed where the
            // server's value and the server's count are read.
            val declared = if (row.buildsSource) {
                owner.contains(kind)
            } else {
                owner.sourceDeclarations().any { it == kind }
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
            if (sourceOf(counter).contains("revision", ignoreCase = true)) return@mapNotNull null
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
            if (sourceOf(advancer).contains(ADVANCE)) return@mapNotNull null
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
            val arguments = productionSourceOf(row.site).sourceArguments()
            val constructed = arguments.filter { argument ->
                argument.contains("StaticText(") || argument.contains("OwnedText(")
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
        val bridge = sourceOf(BRIDGE_FILE)
        val offending = BRIDGE_FUNCTION.findAll(bridge).mapNotNull { match ->
            val parameters = match.groupValues[1]
            if (!parameters.contains("revision")) return@mapNotNull null
            // The bridge's own implementation reads the count from the source; only a
            // *public* declaration would let a call site write one down.
            val declaration = bridge.substring(0, match.range.first).substringAfterLast('\n').trim()
            if (declaration == "private") return@mapNotNull null
            parameters
        }.toList()

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
     * The files that call the bridged helper.
     *
     * The helper's own definition is excluded: `TextFieldStateBridge.kt` *is* the
     * helper, not a field site.
     */
    private fun bridgedSites(): Set<String> {
        val root = SourceScan.commonMainRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .mapNotNull { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (relative == BRIDGE_FILE) return@mapNotNull null
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

    /**
     * The production part of a file — everything before its first preview.
     *
     * A preview is a fixture: it may build a source, because it is standing in
     * for an owner rather than being one. The marker is the *annotation* on its
     * own line, not the word: a file whose header comment mentions `@Preview`
     * must not be cut there, or the check would never see the rest of it.
     */
    private fun productionSourceOf(path: String): String {
        val source = sourceOf(path)
        val preview = PREVIEW_MARKER.find(source) ?: return source
        return source.substring(0, preview.range.first)
    }

    // ── the skill's rewrite table ──────────────────────────────────────────────

    /**
     * The kinds of source a file declares.
     *
     * A file may declare both — the notes' state declares the editor's owned title
     * and the list's static query — so this is the *set*, not the single kind.
     */
    private fun String.sourceDeclarations(): Set<String> =
        SOURCE_DECLARATION.findAll(this).map { it.groupValues[1] }.toSet()

    /**
     * The `source = …` arguments of the bridge calls in a file.
     */
    private fun String.sourceArguments(): List<String> =
        SOURCE_ARGUMENT.findAll(this).map { it.groupValues[1].trim() }.toList()

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
        const val BRIDGED_CALL = "rememberBridgedTextFieldState("
        const val BRIDGE_FILE = "core/ui/components/TextFieldStateBridge.kt"

        /** The bridge's own declarations, with their parameter lists. */
        val BRIDGE_FUNCTION = Regex(
            """fun rememberBridgedTextFieldState\((.*?)\)\s*:\s*TextFieldState""",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** The `source = …` argument of a bridge call. */
        val SOURCE_ARGUMENT = Regex("""source\s*=\s*([^\n]+)""")

        /**
         * The annotation that opens a preview.
         *
         * It has to be the annotation on its own line: the word `@Preview` also
         * appears in prose, and cutting a file at a mention of it would hide
         * everything after the mention from the checks.
         */
        val PREVIEW_MARKER = Regex(
            """^\s*@(?:Preview|androidx\.compose\.ui\.tooling\.preview\.Preview)\s*$""",
            RegexOption.MULTILINE,
        )

        /** A declaration of a source property, with the type it declares. */
        val SOURCE_DECLARATION = Regex("""val\s+[\w.]+\s*:\s*(OwnedText|StaticText)\b""")

        /** A counter that advances. */
        val ADVANCE = Regex("""revision[^\n]*\+\s*1""", RegexOption.IGNORE_CASE)
    }
}

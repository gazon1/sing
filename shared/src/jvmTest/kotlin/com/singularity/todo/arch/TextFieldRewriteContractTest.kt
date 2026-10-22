package com.singularity.todo.arch

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
 * The contract is published in **one** place: the rewrite table in
 * `.agents/skills/singularity-todo-text-field-states/SKILL.md` — one row per bridged
 * field site, with the upstream, the kind of source the site is handed, and the
 * owner that builds it. This test *is* that table's reader; it holds no second
 * copy of it. A contract written twice is a contract that can disagree with
 * itself, and the copy nobody reads is the copy that rots.
 *
 * Five things are checked:
 *
 * 1. **Every bridged field site has a row.** A file that calls the bridged helper
 *    and is not in the table fails the build. A new bridged field is a new
 *    answer to "who may rewrite this?", and the answer is a decision somebody has
 *    to make on purpose. A row whose file no longer owns a bridged field fails
 *    too: a stale row is a contract with nobody.
 * 2. **The owner's source is of the kind the row claims.** The row names the file
 *    that builds the source; that file must declare a source of the claimed kind.
 * 3. **An owned row names a counter that exists and moves.** The file that declares
 *    the counter must declare it, and the file that advances it must advance it —
 *    a counter that never moves says "this is a rewrite" about nothing.
 * 4. **A field site does not build its own source.** The answer is the owner's to
 *    give; a site that constructs the source itself is answering a question it
 *    cannot know the answer to. There is no exception: the GenUI renderer's owner
 *    is the data context, which builds the source and hands it out whole.
 * 5. **The bridge takes no revision argument.** The counter is the source's
 *    property, not something a call site can write down.
 *
 * ## Where the evidence comes from
 *
 * The table makes claims about the code, and every claim is checked against the
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
            "These files own a bridged text field and have no rewrite contract in the " +
                "rewrite table of .agents/skills/singularity-todo-text-field-states/:\n" +
                unclassified.joinToString("\n") { "  - $it" } +
                "\n\nAdd the file to the table with the upstream that may rewrite it, the " +
                "kind of source it is handed, and the owner that builds it.",
        )
    }

    @Test
    fun `every rewrite contract names a file that still owns a bridged field`() {
        val bridged = bridgedSites()
        val stale = registry.mapNotNull { row ->
            if (row.site in bridged) null else "  - ${row.site} -> ${row.kind.sourceType}"
        }.sorted()

        assertTrue(
            stale.isEmpty(),
            "These rows name a file that no longer owns a bridged text field:\n" +
                stale.joinToString("\n") + "\n" +
                "\n\nA row that no longer describes anything is a contract with nobody. " +
                "Remove it from the rewrite table in " +
                ".agents/skills/singularity-todo-text-field-states/SKILL.md.",
        )
    }

    /**
     * The field a row names is a field the file has.
     *
     * The scan is per file, so it proves that a file declares the shapes it declares —
     * it cannot prove that the *field* the table names is the field the file has. That
     * mapping was prose, maintained by hand, and it drifted: a row could name a field
     * the file has not had for months, and nothing said so.
     *
     * So the mapping is checked the only way it can be: by the words the row names, read
     * against the words the file is made of. An identifier is split into its camel-case
     * parts — `nameSource` says *name* and *source*, `baseUrl` says *base* and *url* —
     * and the path counts too, because a file named `NoteEditorScreen.kt` says *note*,
     * *editor* and *screen*. Every content word of the Field cell has to be one of them.
     *
     * The check is a word check, not a proof: "end" is a word of "start, end, quote,
     * note", and a file with an `endDate` would satisfy it. That is the price of a
     * textual check, and it is the same price the counter checks pay. It catches the
     * failure this column actually had — a row that names a field the file does not have
     * — and it says so in the failure message.
     */
    @Test
    fun `the field a row names is a field the file has`() {
        val wrong = registry.mapNotNull { row ->
            val missing = row.fields.fieldWords().filterNot { word -> word in siteWords(row.site) }
            if (missing.isEmpty()) return@mapNotNull null
            "${row.site} -> has no ${missing.joinToString(", ")}"
        }.sorted()

        assertTrue(
            wrong.isEmpty(),
            "These rows name a field the file does not have:\n" +
                wrong.joinToString("\n") { "  - $it" } +
                "\n\nThe Field column of the rewrite table in " +
                ".agents/skills/singularity-todo-text-field-states/SKILL.md names the " +
                "field of that file the contract is about. Name the field the file has.",
        )
    }

    @Test
    fun `a static row names an owner whose value names its writes`() {
        val wrong = registry.filter { it.kind == Kind.STATIC }.mapNotNull { row ->
            // The static kind is a promise about the upstream's writes: it replaces the
            // value only with one the field never produced. An owner that holds a bare
            // string can write to it from anywhere, and the write says nothing. An owner
            // that holds a `FieldOwnedText` can only write it in the three named ways —
            // the field's own text, a seed, a clear — so the promise is in the type.
            val declared = TextFieldShapeScan.properties(row.owner).any { property ->
                property.type?.bareSourceType == FIELD_OWNED
            }
            if (declared) return@mapNotNull null
            "$row.site -> ${row.owner} holds no $FIELD_OWNED"
        }.sorted()

        assertTrue(
            wrong.isEmpty(),
            "These fields are handed a static source, but the file that owns the value " +
                "holds no $FIELD_OWNED:\n" +
                wrong.joinToString("\n") { "  - $it" } +
                "\n\nA static source is a promise about the upstream's writes, and a " +
                "promise that lives in a type is one nobody can forget.",
        )
    }

    @Test
    fun `the owner a row names declares a source of that kind`() {
        val wrong = registry.mapNotNull { row ->
            val kind = row.kind.sourceType
            // The owner builds the source: it keeps it in a property, or it hands it
            // out from a function. The GenUI data context does the latter — it builds
            // the source of a bound field and gives it to the renderer whole.
            val declared = TextFieldShapeScan.properties(row.owner).any { property ->
                property.declaresSourceOf(kind)
            } || TextFieldShapeScan.functions(row.owner).any { function ->
                function.returnsSourceOf(kind)
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
            // The count either travels with the value — a `Revisioned`, which carries the
            // value and the count of its replacements in one object — or it is a plain
            // count of the upstream's writes, the way the GenUI surface counts the
            // server's writes. Both are counters; only the first is a value's own.
            val declared = TextFieldShapeScan.properties(counter).any { property ->
                property.type?.bareSourceType == REVISIONED ||
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
                "\n\nName the file that declares the counter in the Counter column of " +
                "the rewrite table in " +
                ".agents/skills/singularity-todo-text-field-states/SKILL.md.",
        )
    }

    @Test
    fun `an owned row names a counter that advances`() {
        val frozen = registry.filter { it.kind == Kind.OWNED }.mapNotNull { row ->
            val advancer = row.advances
            if (advancer.isEmpty()) return@mapNotNull "$row.site -> names no file that advances the counter"
            // A `Revisioned`'s count moves only through `replacedBy` — that is the whole
            // point of the type — so the file that advances it says so by calling it. A
            // plain count is incremented instead: that is the GenUI surface's count of
            // the server's writes.
            //
            // `replacedIfChangedBy` is the same move, conditional on the value having
            // changed: a replacement that lands on the value the owner already holds is
            // the echo of the owner's own save, and it does not move the count.
            val advances = TextFieldShapeScan.declarationTexts(advancer).any { text ->
                val code = KotlinText.stripComments(text)
                code.contains(REPLACED_BY) ||
                    code.contains(REPLACED_IF_CHANGED_BY) ||
                    ADVANCE.containsMatchIn(code)
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
                "\n\nName the file that advances the counter in the Advances column of " +
                "the rewrite table.",
        )
    }

    @Test
    fun `a field site does not build its own source`() {
        val built = registry.mapNotNull { row ->
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

    /**
     * The sealed parent of the two kinds of source is nobody's parameter.
     *
     * The bridge's own check covers one file. The rule is about every component: a
     * parameter of the sealed parent is a promise the compiler cannot keep — anyone
     * holding one may pass it on, and the receiver has to decide what to do with it.
     * The decision is a `when`, and the `when` is where the kind stops being known.
     */
    @Test
    fun `the sealed parent of the two kinds is never a parameter`() {
        val offending = TextFieldShapeScan.functionsThatCanMention(TEXT_SOURCE_QUALIFIED)
            .filter { function ->
                function.parameters.any { it.type?.bareSourceType == TEXT_SOURCE }
            }
            .map { function -> function.name }
            .sorted()

        assertTrue(
            offending.isEmpty(),
            "These functions take the sealed parent of the two kinds of source:\n" +
                offending.joinToString("\n") { "  - $it" } +
                "\n\nA component that serves two kinds of upstream cannot say which kind " +
                "of upstream it holds, so it is split: each half knows its kind, and the " +
                "kind is a compile-time property of the parameter it is given.",
        )
    }

    @Test
    fun `the bridge does not decide the kind of the source at runtime`() {
        val offending = TextFieldShapeScan.functions(BRIDGE_FILE)
            // A function that takes the sealed parent decides the kind with a `when`
            // when it is called: the kind is a runtime question, and the answer is
            // wherever the `when` happens to live.
            .filter { function ->
                function.parameters.any { it.type?.bareSourceType == TEXT_SOURCE }
            }
            .map { it.name }

        assertTrue(
            offending.isEmpty(),
            "The bridge takes a source whose kind it decides at runtime:\n" +
                offending.joinToString("\n") { "  - $it" } +
                "\n\nA component that serves two upstreams cannot say which kind of " +
                "upstream it holds, so it is split: each half knows its kind, and the " +
                "kind is a compile-time property of the parameter it is given.",
        )
    }

    // ── the registry: the skill's rewrite table ─────────────────────────────────

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
     * @property kind the kind of source the site is handed.
     * @property owner the file that builds the source.
     * @property counter the file that declares the revision counter, relative to
     *   `commonMain/kotlin/com/singularity/todo/`. Empty for a static row.
     * @property advances the file that advances the counter. Empty for a static row.
     */
    private data class Row(
        val site: String,
        val fields: String,
        val kind: Kind,
        val owner: String,
        val counter: String = "",
        val advances: String = "",
    )

    /**
     * The rewrite contract of every file that owns a bridged text field, as the skill
     * publishes it.
     *
     * A file may have more than one row: the task title and the task description
     * are served by two upstreams — the create draft and the detail draft — and
     * the two are different kinds of upstream.
     */
    private val registry: List<Row> by lazy { skillRewriteTable() }

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
     * The rewrite table of the text-field skill, read row by row.
     *
     * The table is a Markdown table of
     * `| File | Field | Upstream | Source | Owner | Counter | Advances |` rows; the
     * file cell holds a backticked path, the source cell holds the kind of source the
     * code uses, the owner cell holds the file that builds the source, and the counter
     * and advances cells hold the files that declare and advance the revision (or `-`).
     * The table is found by its header, so a row of another table is never read as a
     * row of this one.
     */
    private fun skillRewriteTable(): List<Row> =
        SkillTables.table("File", "Field", "Source", "Owner", "Counter", "Advances")
            .mapNotNull { cells ->
                val site = cells[0].removeSurrounding("`").trim()
                if (!site.endsWith(".kt")) return@mapNotNull null
                val source = cells[2].removeSurrounding("`").trim()
                val kind = Kind.entries.firstOrNull { it.sourceType == source } ?: return@mapNotNull null
                Row(
                    site = site,
                    fields = cells[1],
                    kind = kind,
                    owner = cells[3].removeSurrounding("`").trim().pathOrEmpty(),
                    counter = cells[4].removeSurrounding("`").trim().pathOrEmpty(),
                    advances = cells[5].removeSurrounding("`").trim().pathOrEmpty(),
                )
            }

    /** A cell that holds a path, or nothing. */
    private fun String.pathOrEmpty(): String =
        if (this == "-" || isEmpty()) "" else this

    /**
     * The words the file is made of.
     *
     * Every identifier of every declaration, split into its camel-case parts, plus the
     * path's own words. The declarations come from Konsist, so this is the file's own
     * vocabulary and not a text search over its source.
     */
    private fun siteWords(site: String): Set<String> {
        val words = mutableSetOf<String>()
        for (text in TextFieldShapeScan.declarationTexts(site)) {
            // The comments go first: a word in a KDoc is prose about the file, not a
            // word the file is made of. Without this, a row could be satisfied by the
            // name of a thing the file only talks about.
            val code = KotlinText.stripComments(text)
            for (match in IDENTIFIER.findAll(code)) {
                words += match.value.splitCamelCase()
            }
        }
        words += site.splitCamelCase()
        return words
    }

    /**
     * The content words of a Field cell.
     *
     * "the annotation draft (start, end, quote, note)" says *annotation*, *draft*,
     * *start*, *end*, *quote* and *note*. The words that carry no field — the articles,
     * the possessives — are dropped.
     */
    private fun String.fieldWords(): List<String> =
        lowercase()
            .replace(NON_WORD, " ")
            .split(" ")
            .filter { word -> word.isNotEmpty() && word !in STOP_WORDS }

    /** An identifier split into its camel-case parts, lowercased. */
    private fun String.splitCamelCase(): List<String> =
        CAMEL_BOUNDARY.split(this)
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }

    private companion object {
        /** The bridge, the one file that holds the helper itself. */
        const val BRIDGE_FILE = "core/ui/textfields/TextFieldStateBridge.kt"

        /** The name of the bridged helper — the function the bridge exposes. */
        const val BRIDGED_HELPER = "rememberBridgedTextFieldState"

        /** A source that is constructed. */
        val CONSTRUCTION = Regex("""(?:StaticText|OwnedText)\(""")

        /** A counter that advances. */
        val ADVANCE = Regex("""revision[^\n]*\+\s*1""", RegexOption.IGNORE_CASE)

        /** The type that carries a value and the count of its replacements. */
        const val REVISIONED = "Revisioned"

        /** The type whose writes are all named. */
        const val FIELD_OWNED = "FieldOwnedText"

        /** The sealed parent of the two kinds of source. */
        const val TEXT_SOURCE = "TextSource"

        /**
         * The sealed parent, fully qualified.
         *
         * The whole-tree rule asks "does any function in `commonMain` take this
         * type as a parameter?". The answer can only come from the files that
         * import the type or sit in its package — so the search names the
         * package, and the scan checks that the type is really there.
         */
        const val TEXT_SOURCE_QUALIFIED = "com.singularity.todo.core.text.TextSource"

        /** An identifier, as the file writes it. */
        val IDENTIFIER = Regex("""[A-Za-z][A-Za-z0-9]*""")

        /** The boundary between two camel-case parts. */
        val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

        /** Anything that is not a word. */
        val NON_WORD = Regex("""[^a-z0-9]+""")

        /** The words of a Field cell that name no field. */
        val STOP_WORDS = setOf("the", "a", "an", "of", "and", "its", "s", "own", "it", "them")

        /** The only way a `Revisioned`'s count moves. */
        const val REPLACED_BY = "replacedBy("

        /** The same move, conditional on the value having changed. */
        const val REPLACED_IF_CHANGED_BY = "replacedIfChangedBy("
    }
}

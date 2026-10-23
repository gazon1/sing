package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.fail

/**
 * Every column of a task row has a writer, and the writer is named.
 *
 * ## Why this exists
 *
 * `EntityMapperCompletenessTest` already promises to catch this class:
 *
 * > Room `@Upsert` is a full-row REPLACE: any column not set takes its Kotlin
 * > default. If a mapper omits a field, that field is silently destroyed on
 * > every write.
 *
 * It checks the *read* side — `TaskEntity.toTask()` touches every column — and
 * the write side is a private mapper inside a repository, which the gate does
 * not reach. So a column that the read side sees and the write side drops is
 * green: the gate says "someone touches `outgoing_links`" and the truth is
 * that the only writer of the row leaves it at its default.
 *
 * That is not hypothetical. The links of a task were dropped on every pull
 * until the pull path was given a writer for them, and the four vestigial
 * `sync` columns are dropped on every write to this day.
 *
 * ## The rule
 *
 * The writer of a task row — `Task.toEntity()` — passes every column of the
 * row except the ones this file declares. A declaration names the column and
 * says who writes it instead; a column nobody writes has to be said out loud,
 * in a place a reviewer can see.
 *
 * The embedded `SyncColumns` are checked too: a column that hides inside an
 * `@Embedded` is a column the read-side gate cannot see either. The two sets
 * are kept apart — the embedded columns belong to the `sync` property, not to
 * the row — so a column of the row that happens to share a name with a column
 * of the embedded class is not counted as written.
 *
 * ## How the sources are read
 *
 * Through [KotlinText], which is the one lexer of this repository: the sources
 * are read as *code*, so a parenthesis inside a string literal is text and not
 * structure. A gate that reads a file the way a compiler does not reads it
 * wrongly — and this one already had a version that ended a body in the wrong
 * place because of it.
 *
 * A named argument is read at the top level of the constructor call only: an
 * argument of a nested call is not an argument of this one, and reading it as
 * one would give a column a writer that does not write it.
 *
 * ## What this does not check
 *
 * That the named writer is actually *called*. That is behaviour, and behaviour
 * is checked by a test that can fail on it —
 * `TaskDocumentWriteTest` in `com.singularity.todo.feature.tasks` asserts that
 * the links of a row that arrives are in the database.
 *
 * It also does not cover the other writers of a task row: the backup importer
 * writes rows of its own, and `setTags` upserts a row it has read from the
 * database, so both carry the columns they read. The claim of this gate is
 * about the writer the repository uses for a task it is given — the one that
 * could silently reset a column.
 *
 * ## Why this is a fast test
 *
 * It reads two files for the columns and the writer, and the production tree
 * for the targeted updates. A source scan is the "fast" kind of filesystem
 * work in this repository — the "slow" kind is a real database or a real
 * process — and the tree is a few megabytes of text.
 */
@Tag("fast")
class TaskRowWriterTest {

    @Test
    fun `every column of the task row has a writer`() {
        val entity = entityParams("TaskEntity")
        val unwritten = entity - rowWritten

        val undeclared = unwritten.map { "TaskEntity.$it" } - DECLARED_OMISSIONS.keys
        if (undeclared.isNotEmpty()) {
            fail(
                "Column(s) of TaskEntity that the writer does not pass and that nobody " +
                    "declared: ${undeclared.sorted()}.\n\n" +
                    "An undeclared column is reset to its default by @Upsert on every write. " +
                    "Either the writer passes it, or this file declares it with the name of " +
                    "the writer that writes it instead — the declarations are at the bottom " +
                    "of this file.",
            )
        }
    }

    @Test
    fun `every column of the embedded sync columns has a writer`() {
        val embedded = entityParams("SyncColumns")
        val unwritten = embedded - embeddedWritten

        val undeclared = unwritten.map { "TaskEntity.sync.$it" } - DECLARED_OMISSIONS.keys
        if (undeclared.isNotEmpty()) {
            fail(
                "Column(s) of the embedded SyncColumns that the writer does not pass and " +
                    "that nobody declared: ${undeclared.sorted()}.\n\n" +
                    "A column that hides inside an @Embedded is a column the read-side " +
                    "completeness gate cannot see either.",
            )
        }
    }

    @Test
    fun `every declaration names a column that exists`() {
        val entity = entityParams("TaskEntity")
        val embedded = entityParams("SyncColumns")
        val known = entity.map { "TaskEntity.$it" } + embedded.map { "TaskEntity.sync.$it" }

        val unknown = DECLARED_OMISSIONS.keys - known
        if (unknown.isNotEmpty()) {
            fail(
                "Declaration(s) for a column that does not exist: ${unknown.sorted()}. " +
                    "A declaration for a deleted column is a hole with paperwork.",
            )
        }
    }

    @Test
    fun `no declaration is left over`() {
        val obsolete = DECLARED_OMISSIONS.keys.filter { key ->
            val column = key.substringAfterLast('.')
            column in rowWritten || column in embeddedWritten
        }
        if (obsolete.isNotEmpty()) {
            fail(
                "Declaration(s) for a column the writer now passes: ${obsolete.sorted()}. " +
                    "A declaration for a column that has a writer hides the fact that it has one.",
            )
        }
    }

    @Test
    fun `every declaration says who writes the column`() {
        val problems = DECLARED_OMISSIONS.mapNotNull { (column, reason) ->
            val writer = reason.substringBefore(":").trim()
            when {
                reason.isBlank() -> "$column has no reason at all"

                writer.isEmpty() -> "$column does not say who writes it"

                writer != NOBODY && !productionSource.contains(writer) ->
                    "$column names the writer $writer, which no longer exists in the sources"

                else -> null
            }
        }
        if (problems.isNotEmpty()) {
            fail(
                problems.joinToString("\n") + "\n\n" +
                    "A column that nobody writes has to say so, and a column that somebody " +
                    "writes has to name a writer that exists.",
            )
        }
    }

    @Test
    fun `a column declared as unwritten is not written by a targeted update`() {
        val columns = DECLARED_OMISSIONS
            .filterValues { it.startsWith("$NOBODY:") }
            .keys
            .map { it.substringAfterLast('.').toColumnName() }
        val offenders = targetedUpdates().filter { statement ->
            columns.any { column -> Regex("""\b$column\b""").containsMatchIn(statement) }
        }
        if (offenders.isNotEmpty()) {
            fail(
                "Column(s) declared as having no writer are written by a targeted update:\n" +
                    offenders.joinToString("\n") { "  $it" } + "\n\n" +
                    "A declaration that says 'nobody writes this' has to be true, or it has " +
                    "to name the writer that does.",
            )
        }
    }

    // ── the sources ───────────────────────────────────────────────────────────

    /** The entity declarations, read as code: comments and literal contents are blanked. */
    private val entitiesCode: String by lazy {
        KotlinText.stripCode(
            File(SourceScan.commonMainRoot(), "core/database/Entities.kt").readText(),
        )
    }

    /** The repository, read as code: comments and literal contents are blanked. */
    private val repositoryCode: String by lazy {
        KotlinText.stripCode(
            File(
                SourceScan.commonMainRoot(),
                "feature/tasks/data/TaskRepositoryImpl.kt",
            ).readText(),
        )
    }

    /** Every production source, for the checks that look at what else writes a column. */
    private val productionSource: String by lazy {
        SourceScan.commonMainRoot().walkTopDown()
            .filter { it.name.endsWith(".kt") }
            .joinToString("\n") { it.readText() }
    }

    /**
     * The arguments of the two constructor calls the writer makes: the row, and
     * the embedded class inside it.
     */
    private val writerArguments: Pair<String, String?> by lazy {
        val start = repositoryCode.indexOf(WRITE_MAPPER_SIGNATURE)
        if (start < 0) fail("the writer $WRITE_MAPPER_SIGNATURE is not in TaskRepositoryImpl.kt")
        // The signature ends at the parameter list; the row is constructed after
        // it, so the call is looked up from the signature onwards.
        val callStart = repositoryCode.indexOf("TaskEntity(", start)
        if (callStart < 0) return@lazy "" to null
        val arguments = balancedBody(repositoryCode, callStart + "TaskEntity".length)
            ?: return@lazy "" to null
        val embeddedStart = arguments.indexOf("SyncColumns(")
        val embedded = if (embeddedStart < 0) {
            null
        } else {
            balancedBody(arguments, embeddedStart + "SyncColumns".length)
        }
        arguments to embedded
    }

    /** The columns the writer passes to the row constructor. */
    private val rowWritten: Set<String> by lazy {
        namedArguments(writerArguments.first)
    }

    /** The columns the writer passes to the embedded constructor. */
    private val embeddedWritten: Set<String> by lazy {
        namedArguments(writerArguments.second.orEmpty())
    }

    /**
     * The constructor parameters of an entity, read from the source.
     *
     * From the source rather than from a hand-written list, so that a column
     * added to the entity is a column this gate sees.
     */
    private fun entityParams(entityName: String): Set<String> {
        val declaration = "data class $entityName("
        val start = entitiesCode.indexOf(declaration)
        if (start < 0) fail("$entityName is not in Entities.kt — this gate expects it there")
        val body = balancedBody(entitiesCode, start + declaration.length - 1)
            ?: fail("the declaration of $entityName does not close")
        return body
            .splitTopLevel(',')
            .mapNotNull { param ->
                // The name is what comes before the type: `val dueDate: String?`
                // and `@ColumnInfo("due_date") val dueDate: String?` are the same
                // parameter, and the annotation is not part of the name.
                val withoutAnnotation = param.replace(ANNOTATION, " ")
                val withoutDefault = withoutAnnotation.substringBefore('=')
                val withoutType = withoutDefault.substringBefore(':')
                val name = withoutType.substringAfterLast("val ").substringAfterLast("var ").trim()
                name.takeIf { it.isNotEmpty() && it.first().isLetter() }
            }
            .toSet()
    }

    /**
     * The named arguments of a constructor call, at the top level of the call.
     *
     * Top level only: the argument of a *nested* call is not an argument of this
     * one, and reading it as one would give a column a writer that does not
     * write it.
     */
    private fun namedArguments(arguments: String): Set<String> {
        val names = mutableSetOf<String>()
        var depth = 0
        var index = 0
        while (index < arguments.length) {
            val c = arguments[index]
            when {
                c == '(' || c == '[' || c == '{' -> depth++

                c == ')' || c == ']' || c == '}' -> depth--

                c == '=' && depth == 0 && isAssignment(arguments, index) -> {
                    val name = identifierBefore(arguments, index)
                    if (name.isNotEmpty()) names += name
                }
            }
            index++
        }
        return names
    }

    /** Whether the `=` at [index] assigns a name, rather than comparing two values. */
    private fun isAssignment(text: String, index: Int): Boolean {
        val before = if (index > 0) text[index - 1] else ' '
        val after = if (index + 1 < text.length) text[index + 1] else ' '
        return before !in "!<>=+-*/" && after != '='
    }

    /** The identifier that ends just before [endExclusive], spaces ignored. */
    private fun identifierBefore(text: String, endExclusive: Int): String {
        var end = endExclusive - 1
        while (end >= 0 && text[end].isWhitespace()) end--
        var start = end
        while (start >= 0 && (text[start].isLetterOrDigit() || text[start] == '_')) start--
        return text.substring(start + 1, end + 1)
    }

    /** Every `UPDATE tasks SET …` of the production sources, with its set clause. */
    private fun targetedUpdates(): List<String> =
        UPDATE_TASKS.findAll(productionSource).map { it.groupValues[1].trim() }.toList()

    /**
     * The text inside the parentheses that open at [openIndex], or null.
     *
     * The source is expected to be *code* — the literal contents blanked — so
     * that a parenthesis inside a string is text and not structure. Offsets are
     * the same in the code and in the source, so the body is cut out of the
     * source it was given.
     */
    private fun balancedBody(source: String, openIndex: Int): String? {
        if (openIndex < 0 || openIndex >= source.length) return null
        if (source[openIndex] != '(') return null
        var depth = 0
        for (i in openIndex until source.length) {
            when (source[i]) {
                '(' -> depth++

                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(openIndex + 1, i)
                }
            }
        }
        return null
    }

    /**
     * The parts of a parameter list, split at the commas that are not inside a
     * bracket.
     *
     * A list that does not balance is a list this gate cannot read, and a gate
     * that reads half a list sees half the columns — so it fails loudly rather
     * than quietly.
     */
    private fun String.splitTopLevel(separator: Char): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var current = StringBuilder()
        var previous = ' '
        for (c in this) {
            when {
                c in "([{<" -> depth++

                // `->` is an arrow, not the end of a generic type.
                c == '>' && previous != '-' -> depth--

                c in ")]}" -> depth--
            }
            if (c == separator && depth == 0) {
                parts += current.toString()
                current = StringBuilder()
            } else {
                current.append(c)
            }
            previous = c
        }
        if (depth != 0) {
            fail(
                "The parameter list this gate reads does not balance: it ends with " +
                    "$depth unclosed bracket(s).\n\n" +
                    "A list that does not balance is a list the gate cannot read, and a gate " +
                    "that reads half a list sees half the columns.",
            )
        }
        if (current.isNotBlank()) parts += current.toString()
        return parts
    }

    private companion object {
        /** The private mapper that writes a task row. */
        const val WRITE_MAPPER_SIGNATURE = "private fun Task.toEntity("

        /** What a declaration says when no writer exists for the column. */
        const val NOBODY = "nobody"

        /** A named argument, and not a comparison. */
        val ANNOTATION = Regex("""@\w+(\([^)]*\))?""")

        /** The set clause of a targeted update of the task row. */
        val UPDATE_TASKS = Regex("""UPDATE\s+tasks\s+SET\s+([^"']+)""", RegexOption.IGNORE_CASE)

        /**
         * The columns the writer does not pass, and who writes them instead.
         *
         * The reason is written as `writer: why` so that the test can check the
         * writer still exists. `nobody` is an answer of its own: it says the
         * column has no writer today, and says so where a reviewer sees it.
         */
        val DECLARED_OMISSIONS = mapOf(
            "TaskEntity.sync.syncStatus" to
                "$NOBODY: vestigial — the sync state of a task lives in the shadow table",
            "TaskEntity.sync.syncError" to
                "$NOBODY: vestigial — the sync state of a task lives in the shadow table",
            "TaskEntity.sync.lastSyncedAt" to
                "$NOBODY: vestigial — the sync state of a task lives in the shadow table",
            "TaskEntity.sync.deviceId" to
                "$NOBODY: vestigial — the sync state of a task lives in the shadow table",
        )
    }
}

/**
 * The name a column has in SQL.
 *
 * The gate knows a column by the name the Kotlin property has; the SQL that
 * writes a column spells it in the database's own way.
 */
private fun String.toColumnName(): String =
    replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2").lowercase()

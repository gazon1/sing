package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The create editor focuses its title when — and only when — it has no draft key.
 *
 * ## The claim this pins
 *
 * `TaskCreateScreen` decides with one expression: `autoFocusTitle = draftKey == null`.
 * That reads the *absence of a draft id* as "this is a capture flow" — which is
 * true today, and true only because the three flows that prepare a draft are the
 * three flows that pass a key. The rule is an invariant, not a type: nothing in
 * the route says "capture".
 *
 * The invariant holds as long as the mapping holds. A flow that starts to carry
 * a draft key without preparing a draft under it would take the prepared-draft
 * branch — and the editor would refuse to focus the title of a screen the person
 * opened to type into.
 *
 * So the mapping is pinned here: the production call sites that carry a draft key
 * into the create route are exactly the four below, and nothing else may join
 * them without this ADR being revisited.
 *
 * ## What the four are
 *
 * - two agenda flows and one calendar flow that open the editor on a draft the
 *   flow itself wrote (`TaskDraftKeys` builds the key, so the writer and the
 *   reader cannot name different drafts);
 * - the route conversion in `feature/nav`, which forwards the start route's key
 *   into the inner route and prepares nothing.
 *
 * ## Why this is a gate and not a note
 *
 * A note in the ADR is read when someone reads the ADR. A gate is read when
 * someone adds the fourth caller — which is the moment the invariant would
 * break. The failure message says what to do: a new prepared-draft flow has to
 * prepare its draft, and a capture flow must not carry a key at all.
 *
 * ## The positive control
 *
 * A detector that finds nothing may be broken rather than clean, so it is fed
 * the shape it exists for — a fourth caller that carries a key — and has to
 * report it.
 */
@Tag("fast")
class TaskCreateDraftKeyCallerTest {
    /**
     * The prepared-draft flows are the only ones that carry a draft key in.
     *
     * The sites are reported with the expression they carry, so the failure says
     * which key arrived where.
     */
    @Test
    fun `only the prepared-draft flows carry a draft key into the create editor`() {
        val found = draftKeySites().map { it.describe() }.toSet()
        val missing = PREPARED_DRAFT_SITES - found
        val extra = found - PREPARED_DRAFT_SITES

        assertTrue(
            missing.isEmpty() && extra.isEmpty(),
            "The map from a draft key to the create editor has changed.\n" +
                (if (missing.isNotEmpty()) {
                    "\nThese prepared-draft flows no longer carry their key:\n" +
                        missing.joinToString("\n") { "  - $it" }
                } else {
                    ""
                }) +
                (if (extra.isNotEmpty()) {
                    "\nThese call sites now carry a draft key:\n" +
                        extra.joinToString("\n") { "  - $it" }
                } else {
                    ""
                }) +
                "\n\nA capture flow must not carry a draft key: the editor reads the key's " +
                "absence as \"capture\" and focuses the title, so a key that names no prepared " +
                "draft leaves the screen unfocused. A new prepared-draft flow must write its " +
                "draft under a `TaskDraftKeys` key first, and the ADR " +
                "(`docs/decisions/2026-11-16-quick-add-capture-path.md`) has to say so.",
        )
    }

    /**
     * A key that reaches the editor is a key a flow built, not a string.
     *
     * The expression is either a `TaskDraftKeys` reference or the name of a
     * parameter the enclosing flow received from its ViewModel — the two shapes
     * the prepared-draft flows use. A literal or a computed string is neither,
     * and nothing would tie it to a draft that was written.
     */
    @Test
    fun `a draft key is a built key or a forwarded one`() {
        val offenders =
            draftKeySites()
                .filterNot { site -> site.expression.matches(KEY_REFERENCE) || site.expression.matches(FORWARDED_KEY) }
                .map { it.describe() }
                .sorted()

        assertTrue(
            offenders.isEmpty(),
            "These draft keys are neither a `TaskDraftKeys` reference nor a forwarded key:\n" +
                offenders.joinToString("\n") { "  - $it" } +
                "\n\nA key the editor restores a draft by has to be one the writing flow built " +
                "with `TaskDraftKeys`.",
        )
    }

    /**
     * The detector catches a caller that carries a key.
     *
     * Without this, a detector that looks in the wrong place is indistinguishable
     * from a detector that finds nothing.
     */
    @Test
    fun `the detector catches a caller that carries a draft key`() {
        val sites = draftKeySites(listOf("control" to CONTROL_SOURCE))

        assertTrue(
            sites.isNotEmpty(),
            "The detector does not report a create route that carries a draft key, " +
                "so it cannot fail: it is looking in the wrong place.",
        )
    }

    /**
     * The detector leaves a capture flow alone.
     *
     * The other half of the control: a rule that reports everything says nothing.
     */
    @Test
    fun `the detector leaves a capture flow alone`() {
        val sites = draftKeySites(listOf("control" to CAPTURE_SOURCE))

        assertTrue(
            sites.isEmpty(),
            "The detector reports a capture flow as carrying a draft key: " +
                "${sites.joinToString { it.describe() }}",
        )
    }

    // ── the scan ───────────────────────────────────────────────────────────────

    /**
     * One production call site that carries a draft key into the create editor.
     *
     * @param path the file, relative to `com/singularity/todo/`.
     * @param function the function the call sits in.
     * @param expression the argument the call carries as its draft key.
     */
    private data class Site(
        val path: String,
        val function: String,
        val expression: String,
    ) {
        fun describe(): String = "$path#$function -> $expression"
    }

    /**
     * Every production call site that carries a draft key into the create editor.
     *
     * @param sources the sources to scan; the production tree by default, and the
     *   controls' own source for the positive control.
     */
    private fun draftKeySites(sources: List<Pair<String, String>> = productionSources()): List<Site> {
        val sites = mutableListOf<Site>()
        sources.forEach { (path, code) ->
            // `TasksStartRoute.Create(draftKey, projectId, tagIds)` — the draft key
            // is the first parameter. `TasksRoute.Create(initialDueDate, draftKey,
            // projectId, tagIds)` — the inner route puts the date first.
            scan(path, code, "TasksStartRoute.Create(", positionalDraftKey = 0, into = sites)
            scan(path, code, "TasksRoute.Create(", positionalDraftKey = 1, into = sites)
        }
        return sites
    }

    /** The production sources, with their path relative to `com/singularity/todo/`. */
    private fun productionSources(): List<Pair<String, String>> =
        SourceScan.productionSources.map { source ->
            relativePath(source.file) to source.text
        }

    /** The path of [file] under `com/singularity/todo/`. */
    private fun relativePath(file: File): String {
        val path = file.path.replace(File.separatorChar, '/')
        val marker = "/com/singularity/todo/"
        val index = path.indexOf(marker)
        return if (index >= 0) path.substring(index + marker.length) else path
    }

    /**
     * Every call of [call] in [code] that carries a draft key.
     *
     * The argument list is read with a depth counter rather than a regular
     * expression: a key expression may itself contain a call
     * (`TaskDraftKeys.agendaSection(sectionId)`), and a regular expression that
     * stops at the first `)` reads half of it.
     */
    private fun scan(
        path: String,
        code: String,
        call: String,
        positionalDraftKey: Int,
        into: MutableList<Site>,
    ) {
        var index = 0
        while (true) {
            val start = code.indexOf(call, index)
            if (start < 0) return
            val open = start + call.length - 1
            val close = SourceScan.closingParen(code, open)
            if (close < 0) {
                index = start + 1
                continue
            }
            val arguments = splitArguments(code.substring(open + 1, close))
            val expression = namedArgument(arguments, "draftKey") ?: positionalArgument(arguments, positionalDraftKey)
            if (expression != null && expression != "null") {
                into += Site(path, enclosingFunction(code, start), expression)
            }
            index = close + 1
        }
    }

    /**
     * The value of the named argument [name], or null when it is not there.
     *
     * A named argument is recognised by the `=` at the top level of the argument
     * list — a comparison inside an argument (`a == b`) is not a name.
     */
    private fun namedArgument(
        arguments: List<String>,
        name: String,
    ): String? =
        arguments
            .firstOrNull { argument ->
                val trimmed = argument.trim()
                trimmed.startsWith("$name =") || trimmed.startsWith("$name=")
            }
            ?.substringAfter('=')
            ?.trim()

    /** The value of the argument at [position], when the call passes it positionally. */
    private fun positionalArgument(
        arguments: List<String>,
        position: Int,
    ): String? {
        if (arguments.any { argument -> argument.contains('=') }) return null
        return arguments.getOrNull(position)?.trim()
    }

    /**
     * The arguments of a call, split at the commas that are not nested.
     *
     * A comma inside a nested call or a collection literal does not separate
     * arguments; one inside a string literal does not either, but the call sites
     * this scans do not carry one.
     */
    private fun splitArguments(arguments: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (index in arguments.indices) {
            when (arguments[index]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    result += arguments.substring(start, index)
                    start = index + 1
                }
            }
        }
        val last = arguments.substring(start).trim()
        if (last.isNotEmpty() || result.isNotEmpty()) result += last
        return result
    }

    /**
     * The name of the function the code at [offset] sits in.
     *
     * The last declaration before the call is the enclosing one: the call sites
     * this reads are statements in a function body, and a local function inside a
     * body would be a declaration of its own — which is the one this picks.
     */
    private fun enclosingFunction(
        code: String,
        offset: Int,
    ): String {
        var name = "?"
        var at = 0
        for (match in FUNCTION_DECLARATION.findAll(code)) {
            if (match.range.first > offset) break
            name = match.groupValues[1]
            at = match.range.first
        }
        return if (at >= 0) name else "?"
    }

    private companion object {
        /**
         * The call sites that carry a draft key, pinned.
         *
         * The three flows that prepare a draft and open the editor on it, and
         * the route conversion that forwards the key into the inner route.
         */
        val PREPARED_DRAFT_SITES =
            setOf(
                "feature/agenda/presentation/nav/AgendaNavigator.kt" +
                    "#openCreateInSection -> TaskDraftKeys.agendaSection(sectionId)",
                "feature/agenda/presentation/nav/AgendaNavigator.kt" +
                    "#openCreateWithDates -> draftKey",
                "feature/calendar/presentation/nav/CalendarNavigator.kt" +
                    "#openCreateTaskWithDates -> draftKey",
                "feature/nav/NavStartRoutes.kt#toTasksRoute -> draftKey",
            )

        /** A function declaration, with the name it binds. */
        val FUNCTION_DECLARATION =
            Regex("""\bfun\s+(?:[\w<>]+\.)*(\w+)\s*\(""")

        /** A key built by the shared key factory. */
        val KEY_REFERENCE = Regex("""TaskDraftKeys\.\w+(\(.*\))?""")

        /** A key the enclosing flow received and forwards. */
        val FORWARDED_KEY = Regex("""\w+""")

        /** A caller that carries a key the gate has to report. */
        const val CONTROL_SOURCE = """
            class NewFlow {
                fun open(navigator: Navigator) {
                    navigator.go(AppDestination.TasksStartRoute.Create(draftKey = TaskDraftKeys.AGENDA_MULTI_DATE))
                }
            }
        """

        /** A caller that carries no key — a capture flow. */
        const val CAPTURE_SOURCE = """
            class CaptureFlow {
                fun open(navigator: Navigator) {
                    navigator.go(AppDestination.TasksStartRoute.Create())
                    navigator.go(AppDestination.TasksStartRoute.Create(projectId = projectId))
                }
            }
        """
    }
}

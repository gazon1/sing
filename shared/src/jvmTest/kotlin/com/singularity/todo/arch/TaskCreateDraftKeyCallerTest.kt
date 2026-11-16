package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The create editor focuses its title when — and only when — it has no draft key.
 *
 * `TaskCreateScreen` decides with one expression: `autoFocusTitle = draftKey == null`.
 * That reads the *absence of a draft id* as "this is a capture flow" — which is
 * true today, and true only because the three flows that prepare a draft are the
 * three flows that pass a key. The rule is an invariant, not a type: nothing in
 * the route says "capture". A flow that started to carry a key without preparing
 * a draft under it would take the prepared-draft branch — and the editor would
 * refuse to focus the title of a screen the person opened to type into.
 *
 * So the mapping is pinned here: the production call sites that carry a draft key
 * into the create route are exactly the four below, and nothing else may join
 * them without this ADR being revisited. They are two agenda flows and one
 * calendar flow that open the editor on a draft the flow itself wrote
 * (`TaskDraftKeys` builds the key, so the writer and the reader cannot name
 * different drafts), plus the route conversion in `feature/nav`, which forwards
 * the start route's key into the inner route and prepares nothing.
 *
 * A note in the ADR is read when someone reads the ADR. A gate is read when
 * someone adds the fourth caller — which is the moment the invariant would
 * break. The failure message says what to do: a new prepared-draft flow has to
 * prepare its draft, and a capture flow must not carry a key at all.
 *
 * ## What the gate can and cannot read
 *
 * It reads names, not types: a source scan resolves no receiver and no
 * parameter type. So it says that a key is a `TaskDraftKeys` reference or a
 * name, and that it is not a literal or a computed value — but it cannot say
 * where a name came from, and it does not pretend to. The list of sites is
 * pinned exactly, so a new caller cannot slip in unnoticed.
 *
 * ## The controls
 *
 * A detector that finds nothing may be broken rather than clean, so it is fed
 * the shapes it exists for and has to report them: a named key, a positional
 * key, and a call that mixes a positional key with named arguments. The other
 * half: a capture flow, and a call that passes `draftKey = null`, must stay
 * unreported.
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
                (
                    if (missing.isNotEmpty()) {
                        "\nThese prepared-draft flows no longer carry their key:\n" +
                            missing.joinToString("\n") { "  - $it" }
                    } else {
                        ""
                    }
                ) +
                (
                    if (extra.isNotEmpty()) {
                        "\nThese call sites now carry a draft key:\n" +
                            extra.joinToString("\n") { "  - $it" }
                    } else {
                        ""
                    }
                ) +
                "\n\nA capture flow must not carry a draft key: the editor reads the key's " +
                "absence as \"capture\" and focuses the title, so a key that names no prepared " +
                "draft leaves the screen unfocused. A new prepared-draft flow must write its " +
                "draft under a `TaskDraftKeys` key first, and the ADR " +
                "(`docs/decisions/2026-11-16-quick-add-capture-path.md`) has to say so.",
        )
    }

    /**
     * A key that reaches the editor is a key a flow built, not a literal.
     *
     * The expression is either a `TaskDraftKeys` reference or a name — the two
     * shapes the prepared-draft flows use. A literal or a computed value is
     * neither, and nothing would tie it to a draft that was written.
     *
     * What the gate cannot check is where a name came from: reading sources
     * resolves no types. So a name is accepted as a name, and the list of sites
     * above is what pins it.
     */
    @Test
    fun `a draft key is a built key or a name`() {
        val offenders =
            draftKeySites()
                .filterNot { site -> site.expression.matches(KEY_REFERENCE) || site.expression.matches(FORWARDED_KEY) }
                .map { it.describe() }
                .sorted()

        assertTrue(
            offenders.isEmpty(),
            "These draft keys are neither a `TaskDraftKeys` reference nor a name:\n" +
                offenders.joinToString("\n") { "  - $it" } +
                "\n\nA key the editor restores a draft by has to be one the writing flow built " +
                "with `TaskDraftKeys`.",
        )
    }

    /**
     * The detector catches a caller that names a key.
     */
    @Test
    fun `the detector catches a caller that names a draft key`() {
        val sites = draftKeySites(listOf("control" to CONTROL_SOURCE))

        assertTrue(
            sites.isNotEmpty(),
            "The detector does not report a create route that names a draft key, " +
                "so it cannot fail: it is looking in the wrong place.",
        )
    }

    /**
     * The detector catches a caller that passes the key positionally.
     *
     * Kotlin allows a positional argument, so `Create(draftKey)` carries a key
     * exactly as `Create(draftKey = draftKey)` does.
     */
    @Test
    fun `the detector catches a positional draft key`() {
        val sites = draftKeySites(listOf("control" to POSITIONAL_SOURCE))

        assertTrue(
            sites.isNotEmpty(),
            "The detector does not report a create route that passes the draft key " +
                "positionally, so it cannot fail: it is looking in the wrong place.",
        )
    }

    /**
     * The detector catches a call that mixes a positional key with named arguments.
     *
     * Kotlin allows positional arguments *before* named ones, so
     * `Create(draftKey, projectId = projectId)` is legal and carries a key. A
     * detector that gives up as soon as it sees a named argument misses it.
     */
    @Test
    fun `the detector catches a mixed call that passes the key positionally`() {
        val sites = draftKeySites(listOf("control" to MIXED_SOURCE))

        assertTrue(
            sites.isNotEmpty(),
            "The detector does not report a create route that passes the draft key " +
                "positionally next to named arguments, so it cannot fail.",
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

    /**
     * A key that is null is not a key.
     *
     * `draftKey = null` is what a capture flow writes when it names the argument
     * at all, and it must not be reported as a prepared-draft flow.
     */
    @Test
    fun `the detector leaves a call that passes null alone`() {
        val sites = draftKeySites(listOf("control" to NULL_KEY_SOURCE))

        assertTrue(
            sites.isEmpty(),
            "The detector reports `draftKey = null` as carrying a draft key: " +
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
    private data class Site(val path: String, val function: String, val expression: String) {
        fun describe(): String = "$path#$function -> $expression"
    }

    /**
     * Every production call site that carries a draft key into the create editor.
     *
     * @param sources the sources to scan; the production tree by default, and the
     *   controls' own source for the positive controls.
     */
    private fun draftKeySites(sources: List<Pair<String, String>> = productionSources()): List<Site> {
        val sites = mutableListOf<Site>()
        sources.forEach { (path, code) ->
            // The code is read twice: the stripped copy to find the structure
            // (a parenthesis inside a string is not a parenthesis), the original
            // to read the value out of an argument.
            val stripped = KotlinText.stripCode(code)
            // `TasksStartRoute.Create(draftKey, projectId, tagIds)` — the draft key
            // is the first parameter. `TasksRoute.Create(initialDueDate, draftKey,
            // projectId, tagIds)` — the inner route puts the date first.
            scan(path, code, stripped, "TasksStartRoute.Create(", positionalDraftKey = 0, into = sites)
            scan(path, code, stripped, "TasksRoute.Create(", positionalDraftKey = 1, into = sites)
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
        stripped: String,
        call: String,
        positionalDraftKey: Int,
        into: MutableList<Site>,
    ) {
        var index = 0
        while (true) {
            val start = stripped.indexOf(call, index)
            if (start < 0) return
            val open = start + call.length - 1
            val close = SourceScan.closingParen(stripped, open)
            if (close < 0) {
                index = start + 1
                continue
            }
            val arguments = argumentsOf(stripped, open + 1, close)
            val expression = draftKeyExpression(code, arguments, positionalDraftKey)
            if (expression != null && expression != "null") {
                into += Site(path, enclosingFunction(stripped, start), expression)
            }
            index = close + 1
        }
    }

    /**
     * The arguments of a call, split at the commas that are not nested.
     *
     * The text is the stripped copy, so a comma inside a string literal — which
     * the lexer has blanked — does not separate arguments.
     */
    private fun argumentsOf(
        stripped: String,
        from: Int,
        to: Int,
    ): List<Argument> {
        val arguments = mutableListOf<Argument>()
        var depth = 0
        var start = from
        for (index in from until to) {
            when (stripped[index]) {
                '(', '[', '{' -> {
                    depth++
                }

                ')', ']', '}' -> {
                    depth--
                }

                ',' -> {
                    if (depth == 0) {
                        arguments += argument(stripped, start until index)
                        start = index + 1
                    }
                }
            }
        }
        if (to > start) arguments += argument(stripped, start until to)
        return arguments
    }

    /**
     * One argument of a call: where it is, and — when it is named — its name.
     *
     * A named argument is `name = value`; a comparison (`a == b`) is not a name,
     * and neither is one whose `=` belongs to an operator.
     */
    private fun argument(
        stripped: String,
        range: IntRange,
    ): Argument {
        val named =
            NAMED_ARGUMENT.find(stripped.substring(range))
                ?: return Argument(range, name = null, equals = -1)
        return Argument(range, name = named.groupValues[1], equals = range.first + named.range.last)
    }

    /**
     * The value the call carries as its draft key, or null when it carries none.
     *
     * A named argument wins over a positional one — the same argument cannot be
     * both. When the key is positional, only the arguments *before* the first
     * named one count: Kotlin allows positional arguments before named ones, and
     * a named argument shifts the positions of nothing that came before it.
     */
    private fun draftKeyExpression(
        code: String,
        arguments: List<Argument>,
        positionalDraftKey: Int,
    ): String? {
        val named = arguments.firstOrNull { it.name == "draftKey" }
        if (named != null) return code.substring(named.equals + 1, named.range.last + 1).trim()

        val positional = arguments.takeWhile { it.name == null }
        val argument = positional.getOrNull(positionalDraftKey) ?: return null
        return code.substring(argument.range.first, argument.range.last + 1).trim()
    }

    /**
     * The name of the function the code at [offset] sits in.
     *
     * The last declaration before the call is the enclosing one: the call sites
     * this reads are statements in a function body, and a local function inside a
     * body would be a declaration of its own — which is the one this picks.
     */
    private fun enclosingFunction(
        stripped: String,
        offset: Int,
    ): String {
        var name = "?"
        var at = 0
        for (match in FUNCTION_DECLARATION.findAll(stripped)) {
            if (match.range.first > offset) break
            name = match.groupValues[1]
            at = match.range.first
        }
        return if (at >= 0) name else "?"
    }

    /**
     * One argument of a call, and where it sits in the source.
     *
     * @param range the argument's text, as offsets into the source.
     * @param name the argument's name, when it has one.
     * @param equals the offset of the `=` of a named argument, or -1.
     */
    private data class Argument(val range: IntRange, val name: String?, val equals: Int)

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

        /** A named argument: `name =`, and not the `==` of a comparison. */
        val NAMED_ARGUMENT =
            Regex("""^\s*(\w+)\s*=(?!=)""")

        /** A key built by the shared key factory. */
        val KEY_REFERENCE = Regex("""TaskDraftKeys\.\w+(\(.*\))?""")

        /** A key the enclosing flow received and forwards. */
        val FORWARDED_KEY = Regex("""\w+""")

        /** A caller that names a key the gate has to report. */
        const val CONTROL_SOURCE = """
            class NewFlow {
                fun open(navigator: Navigator) {
                    navigator.go(AppDestination.TasksStartRoute.Create(draftKey = TaskDraftKeys.AGENDA_MULTI_DATE))
                }
            }
        """

        /** A caller that passes the key positionally. */
        const val POSITIONAL_SOURCE = """
            class PositionalFlow {
                fun open(navigator: Navigator, draftKey: String) {
                    navigator.go(AppDestination.TasksStartRoute.Create(draftKey))
                }
            }
        """

        /** A caller that passes the key positionally next to a named argument. */
        const val MIXED_SOURCE = """
            class MixedFlow {
                fun open(navigator: Navigator, draftKey: String, projectId: ProjectId) {
                    navigator.go(AppDestination.TasksStartRoute.Create(draftKey, projectId = projectId))
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

        /** A caller that names the key and passes null — still a capture flow. */
        const val NULL_KEY_SOURCE = """
            class NullKeyFlow {
                fun open(navigator: Navigator) {
                    navigator.go(AppDestination.TasksStartRoute.Create(draftKey = null))
                }
            }
        """
    }
}

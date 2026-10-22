package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A seed may not carry the field's own text.
 *
 * ## The claim a seed makes
 *
 * [com.singularity.todo.core.text.FieldOwnedText.seeded] says one thing:
 * *the upstream loaded a value, and the value came from outside the field*. The
 * field never produced it, so the field takes it as it stands — the bridge's
 * echo guard has nothing to say about it.
 *
 * The claim is a claim about a **value**, and the bridge acts on it. The guard
 * drops any value the field itself produced: a value the field holds right now,
 * a value the field held last, and a value the field pushed upstream earlier.
 * A seed that carries such a value is therefore not applied — and the owner
 * believes it replaced the field while the field still shows the old text.
 *
 * That is the lie this test looks for. It is not a naming mistake: the owner
 * may not know where the value came from — the value may arrive as a parameter
 * of the function that handles the owner's own write, and the name of that
 * parameter says nothing about its provenance. The *type* already carries the
 * provenance (`seeded` for a value from outside, `echoed` for the field's own
 * text coming back), so the question this test answers is not "where did this
 * value come from?" but "is the value that is being seeded a value the field
 * produced?".
 *
 * ## How the answer is read
 *
 * A `FieldOwnedText` holds exactly one thing — its text — and the only way to
 * get that text out of it is `.text`. A seed that reads `.text` is therefore
 * seeding a field with a field's text, whatever the receiver happens to be.
 * The read is what makes it visible: the owner's mirror of the field and the
 * field's own value are both `.text` reads, and neither of them is a value
 * that came from outside the field.
 *
 * The scan reads the *declarations* of `commonMain` with the comments
 * stripped, so a mention of `.text` in a KDoc is not a read, and a mention of
 * a seed in a comment is not a seed. Konsist 0.17.3 has no API for call
 * sites, so the text of a declaration is the finest grain available.
 *
 * ## What the rule cannot see
 *
 * The rule sees a seed whose value *is* a field's text. It cannot see a seed
 * whose value *was* a field's text one keystroke ago and is now held by a
 * plain `String` somewhere else — the owner's stale mirror, an intent that
 * carries the value the field pushed. That case is a value the field produced
 * too, and it is invisible here for the same reason the original claim is:
 * provenance is not in the name.
 *
 * The way to make it visible is the type's own: an owner that writes the
 * field's own value back says so with `echoed`. The rule below is the
 * mechanical half of that; the other half is a reviewer's eye on the call
 * site, which the type has already made possible.
 *
 * ## The positive control
 *
 * A rule that has never caught anything may be broken rather than clean, so
 * the detector is fed the shape it is there for and has to report it.
 */
@Tag("fast")
class FieldOwnedTextSeedContractTest {

    /**
     * No seed carries a field's own text.
     *
     * The sites are reported with the argument that was read, so the failure
     * says which value was the field's.
     */
    @Test
    fun `a seed does not carry the field's own text`() {
        val offending = seedSites()
            .filter { (_, argument) -> readsFieldText(argument) }
            .map { (site, argument) -> "$site -> seeded(${argument.trim()})" }
            .sorted()

        assertTrue(
            offending.isEmpty(),
            "These seeds carry a field's own text:\n" +
                offending.joinToString("\n") { "  - $it" } +
                "\n\nA seed says \"the upstream loaded a value from outside the field\". " +
                "A value read out of a field is a value the field produced, and the " +
                "bridge's echo guard drops it — the owner replaces the field and the " +
                "field keeps the old text. Say it with the type instead: `echoed` for " +
                "the field's own value, `seeded` for a value that came from outside.",
        )
    }

    /**
     * The detector catches the shape it is there for.
     *
     * Without this, a detector that finds nothing is indistinguishable from a
     * detector that looks in the wrong place.
     */
    @Test
    fun `the detector catches a seed that carries the field's own text`() {
        val sites = seedSites(CONTROL_SOURCE)
        val reported = sites.filter { (_, argument) -> readsFieldText(argument) }

        assertTrue(
            reported.isNotEmpty(),
            "The detector does not report a seed that carries the field's own text, " +
                "so it cannot fail: it is looking in the wrong place.",
        )
        assertTrue(
            reported.any { it.second.contains(".text") },
            "The detector reports a seed for the wrong reason: ${reported.joinToString()}",
        )
    }

    /**
     * The detector does not report a seed of a value from outside the field.
     *
     * The other half of the control: a rule that reports everything is a rule
     * that says nothing.
     */
    @Test
    fun `the detector leaves a seed of an outside value alone`() {
        val sites = seedSites(OUTSIDE_SOURCE)
        val reported = sites.filter { (_, argument) -> readsFieldText(argument) }

        assertFalse(
            reported.isNotEmpty(),
            "The detector reports a seed of a value from outside the field: " +
                "${reported.joinToString()}",
        )
    }

    // ── the scan ───────────────────────────────────────────────────────────────

    /**
     * The seeds of the production tree, with the value each one carries.
     *
     * @param source the text to scan instead of the tree; the positive control's
     *   own source.
     * @return the argument of every seed, in the order the seeds appear.
     */
    private fun seedSites(source: String? = null): List<Pair<String, String>> {
        val texts = if (source != null) {
            listOf("control" to source)
        } else {
            TextFieldShapeScan.allFileTexts()
        }
        return texts.flatMap { (site, text) ->
            val code = KotlinText.stripComments(text)
            seedArguments(code).map { argument -> "$site" to argument }
        }
    }

    /**
     * The first argument of every `seeded(...)` call in [code].
     *
     * The argument is read with a brace counter rather than a regular
     * expression: a seed's value may itself contain a call with parentheses
     * (`seeded(intent.range.start.toString())`), and a regular expression that
     * stops at the first `)` reads `intent.range.start.toString(` as the value.
     */
    private fun seedArguments(code: String): List<String> {
        val arguments = mutableListOf<String>()
        var index = 0
        var searching = true
        while (searching) {
            val start = code.indexOf(SEEDED_CALL, index)
            val open = start + SEEDED_CALL.length - 1
            val close = if (start < 0) -1 else matchingParen(code, open)
            if (close < 0) {
                searching = false
            } else {
                arguments += firstArgument(code.substring(open + 1, close))
                index = close + 1
            }
        }
        return arguments
    }

    /**
     * The index of the parenthesis that closes the one at [open], or -1.
     *
     * Braces and brackets are counted too: a call inside a list literal
     * (`seeded(listOf(a, b))`) has its parentheses balanced, and a string
     * holding a parenthesis does not — the comments are already stripped, and
     * a literal's contents cannot hold an unbalanced parenthesis in Kotlin
     * without being a raw string.
     */
    private fun matchingParen(code: String, open: Int): Int {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++

                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    /**
     * The first argument of a call's argument list.
     *
     * A comma at depth zero separates the arguments; a comma inside a nested
     * call or a collection literal does not.
     */
    private fun firstArgument(arguments: String): String {
        var depth = 0
        for (index in arguments.indices) {
            when (arguments[index]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) return arguments.substring(0, index)
            }
        }
        return arguments
    }

    /**
     * Whether [argument] reads a field's text.
     *
     * A `.text` read is a read of the only thing a `FieldOwnedText` holds.
     * The name is spelled with a word boundary, so `textLabel` and `.textSource`
     * are not reads.
     */
    private fun readsFieldText(argument: String): Boolean = TEXT_READ.containsMatchIn(argument)

    private companion object {
        /** The call that starts a seed. */
        const val SEEDED_CALL = ".seeded("

        /** A read of a field's text: a dot, the name, and no word after it. */
        val TEXT_READ = Regex("""\.\s*text\b""")

        /** A seed that carries the field's own text — the shape the rule is for. */
        const val CONTROL_SOURCE = """
            class Owner {
                val mirror: FieldOwnedText = FieldOwnedText.empty()
                val lied: FieldOwnedText = mirror.seeded(mirror.text)
            }
        """

        /** A seed of a value that came from outside the field. */
        const val OUTSIDE_SOURCE = """
            class Owner {
                val loaded: Project = load()
                val honest: FieldOwnedText = FieldOwnedText.empty().seeded(loaded.name)
            }
        """
    }
}

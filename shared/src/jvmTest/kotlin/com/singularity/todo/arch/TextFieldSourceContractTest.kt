package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Where a text field reads its text from.
 *
 * ## The question
 *
 * A field bound to a value somewhere else needs two things: the value, and whether the
 * value was replaced from outside. The second one is a **count** — without it, a rewrite
 * that lands on a value the field had already sent is indistinguishable from a lagging
 * upstream, and the bridge's echo guard drops it. The field then keeps the text the user
 * typed while the server believes it wrote something else.
 *
 * So the count has to travel with the value, and the place that decides whether a value
 * is a rewrite has to be the place the value is read. For a GenUI surface that place is
 * the data context: it knows the value at a path **and** the number of the server's
 * writes to it, and it hands the two out together as a source.
 *
 * ## What the context offers, and why the names matter
 *
 * The context has two reads of a path:
 *
 * - `textSource(path, fallback)` — the value **and** the count. This is the read a text
 *   field takes.
 * - `valueWithoutCount(path)` — the value alone. Right for a value that is not text and
 *   is not rewritten from outside: a checkbox's flag, a due date on a card.
 *
 * The count-less read is named for what it does not carry. That is the whole point of the
 * name: a renderer that reads a text field's value through it has dropped the count, and
 * the call says so at the place where the mistake is made. A method called `value` reads
 * like the obvious way to get a value, and the obvious way is the wrong one here.
 *
 * ## What this test holds
 *
 * 1. **A field that bridges a surface path reads it through `textSource`.** The read is
 *    not `valueWithoutCount`, and it is not a value the renderer assembled: the renderer
 *    reads the path in the one way that carries the count.
 * 2. **The source the context hands out carries the count.** A source whose text is a
 *    plain string would satisfy the first rule and break the second — the field would be
 *    bridged and would still drop a rewrite.
 *
 * ## What it cannot check
 *
 * Konsist 0.17.3 has no call-site API, so the *call* is read from the declaration's text
 * rather than from the compiler's record of it. What cannot be fooled by this is a
 * mention in a comment outside the declaration, or a call in a comment inside one: the
 * comments are stripped before the text is read, and only declarations are read at all.
 * What *can* be fooled by it is a call that is written in a way the scan does not
 * recognise — a source built through a helper the scan cannot see. The first rule is
 * therefore paired with the second: a source that carries the count is what the field
 * ends up with, however it was obtained.
 *
 * @see TextFieldRewriteContractTest — who may rewrite a field, and the type that says so.
 * @see TextFieldShapeConventionTest — the shape of a field, derived from its imports.
 */
@Tag("fast")
class TextFieldSourceContractTest {

    /**
     * A field bound to a surface path reads the path through the source.
     *
     * The renderer of a bound text field is the one place where the count can be lost:
     * the value is right there in the model, and reading it is one call. A read that
     * loses the count is not a crash — the field shows the server's value, until the
     * server rewrites the field to something the field had already sent. Then the
     * rewrite is dropped as an echo, and the two disagree without either of them
     * saying anything.
     */
    @Test
    fun `a field bound to a surface path reads it through the source`() {
        val offenders = genuiFieldSites().mapNotNull { path ->
            val code = TextFieldShapeScan.declarationTexts(path)
                .joinToString("\n") { KotlinText.stripCode(it) }

            val readsTheSource = code.contains(TEXT_SOURCE_CALL)
            val readsWithoutTheCount = code.contains(COUNT_LESS_CALL)
            if (readsTheSource && !readsWithoutTheCount) {
                null
            } else {
                buildString {
                    append(path)
                    if (!readsTheSource) append(" -> reads no $TEXT_SOURCE_CALL")
                    if (readsWithoutTheCount) append(" -> reads through $COUNT_LESS_CALL")
                }
            }
        }.sorted()

        assertTrue(
            offenders.isEmpty(),
            "These fields bridge a surface path, but do not read it through the source:\n" +
                offenders.joinToString("\n") { "  - $it" } +
                "\n\nThe count of the server's writes travels with the value, and the source " +
                "is what carries it. A field that reads the value alone drops the count, " +
                "and a rewrite that lands on a value the field had already sent is then " +
                "dropped as an echo.",
        )
    }

    /**
     * The source the context hands out carries the count.
     *
     * The first rule is about the *call*; this one is about the *type*. A source whose
     * text were a plain string would pass the first rule — the renderer would read
     * through `textSource` — and break the second: the field would be bridged, the count
     * would be nowhere, and the rewrite would still be dropped.
     */
    @Test
    fun `the source a surface hands out carries the count`() {
        val current = TextFieldShapeScan.properties(BOUND_SOURCE_FILE)
            .firstOrNull { it.name == "current" }
        val owned = TextFieldShapeScan.properties(OWNED_SOURCE_FILE)
            .firstOrNull { it.name == "revision" }

        assertTrue(
            current != null && current.type?.bareSourceType == OWNED_SOURCE,
            "$BOUND_SOURCE_FILE must hand out an $OWNED_SOURCE as the source's current " +
                "value, not a plain string — a source without the count is a value that " +
                "lost the answer to \"was this replaced from outside?\".",
        )
        assertTrue(
            owned != null && owned.type?.bareSourceType == "Long",
            "$OWNED_SOURCE_FILE must declare the count of the replacements. The owned " +
                "source is the one that says \"the upstream may replace this with " +
                "anything\", and the count is how the field learns that a value is a " +
                "rewrite rather than an echo of what it pushed.",
        )
    }

    /**
     * The files that bridge a field bound to a surface path.
     *
     * Derived the same way the shape is: a file that calls the bridged helper has to
     * import it, and the import is the compiler's own record of the call. The GenUI
     * layer is the one that binds a field to a *path in a data model*, so the rule
     * applies to the files under it.
     */
    private fun genuiFieldSites(): List<String> =
        TextFieldShapeScan.shapes()
            .filterValues { shapes -> FieldShape.BRIDGED in shapes }
            .keys
            .filter { it.startsWith(GENUI_RENDERER_PACKAGE) }
            .toList()

    private companion object {
        /** The GenUI render layer — where a field is bound to a path in a data model. */
        const val GENUI_RENDERER_PACKAGE = "feature/genui/render/"

        /** The source the context hands out for a bound path. */
        const val BOUND_SOURCE_FILE = "feature/genui/render/BoundTextSource.kt"

        /** The owned source — the one that carries the count. */
        const val OWNED_SOURCE_FILE = "core/text/TextFieldSource.kt"

        /** The name of the owned source. */
        const val OWNED_SOURCE = "OwnedText"

        /** The call that reads a path together with its count. */
        const val TEXT_SOURCE_CALL = "textSource("

        /** The call that reads a path without its count. */
        const val COUNT_LESS_CALL = "valueWithoutCount("
    }
}

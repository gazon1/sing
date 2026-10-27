package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chain's count is bounded before the password is proven.
 *
 * ## The hole this closes
 *
 * The count in the chain's head is read out of the container before the password
 * is checked, so a corrupt or hostile count would otherwise become a loop that
 * allocates. The guard that refuses it was verified by hand: delete the line,
 * run the test, watch it fail, put the line back.
 *
 * A manual check is a check that stops being done.
 *
 * ## What this test is
 *
 * The line has to be **there**. The test that depends on it already exists and
 * fails when the line is gone — `EncryptedArchiveChainTest`, "a head that
 * promises too many changes is refused before the password is proven" — but that
 * test can only say "something else went wrong": the failure it reports is a
 * missing exception type, and a reader has to work out that the guard is what
 * went missing. This test names the guard.
 *
 * It also says *where* the guard has to be: before the session that needs the
 * password. A guard that runs after the password is proven is a guard that does
 * not do its job — the count has already become a loop by then.
 *
 * ## The control
 *
 * The second test is the control that makes the first one mean something: a
 * body without the guard is reported, and the report names the guard.
 */
@Tag("slow")
@Tag("slow:data")
class BackupChainGuardTest {

    @Test
    fun `the chain count is bounded before the password is proven`() {
        val sources = backupSources()
        val holders = sources.filter { it.readText().contains(GUARD) }

        assertTrue(
            holders.isNotEmpty(),
            "The guard is not there: `$GUARD` is in no file of core/backup any more.\n\n" +
                "The count in the chain's head is read before the password is checked, so a " +
                "corrupt or hostile count becomes a loop that allocates. Put the guard back — " +
                "and put it back before the session that needs the password, not after it.\n\n" +
                "This test matches the guard literally: a rename or a line broken in two is not " +
                "the guard any more either, and the message says so.",
        )
        assertEquals(
            1,
            holders.size,
            "The guard is in more than one place: " +
                holders.joinToString { it.name } +
                ". One place means one rule; two places means one of them is wrong.",
        )

        val holder = holders.single()
        val method = SourceScan.methods(holder.readText())
            .firstOrNull { it.body.contains(GUARD) }
            ?: error("The guard is in ${holder.path}, but not in a function this test can read.")

        val reason = missingGuard(method.body)
        assertNull(
            reason,
            "The guard is in ${holder.path}:${method.line}, but it is in the wrong place: $reason",
        )
    }

    @Test
    fun `a body without the guard is reported`() {
        val body = """
            fun openChain(source: ByteSource, sink: ByteSink, password: CharArray) {
                requirePassword(password)
                val container = archiveErrors { ArchiveContainerReader.read(source) }
                val session = contentSession(container, password)
            }
        """.trimIndent()

        assertEquals(
            "the guard `requireChainFits(container.head.count, \"reads\")` is not there",
            missingGuard(body),
            "a body without the guard must be reported, and the report must name the guard",
        )
    }

    /** The reason the body has no usable guard, or null when it has one. */
    private fun missingGuard(body: String): String? = when {
        !body.contains(GUARD) ->
            "the guard `$GUARD` is not there"

        !body.contains(SESSION) ->
            "the session call `$SESSION` is not there, so the order cannot be checked"

        body.indexOf(GUARD) > body.indexOf(SESSION) ->
            "the guard runs after the session that needs the password, so the count is already " +
                "a loop by the time it is refused"

        else -> null
    }

    /** The production sources of the backup package. */
    private fun backupSources(): List<File> =
        SourceScan.productionFiles()
            .filter { it.path.replace(File.separatorChar, '/').contains("/core/backup/") }
            .sortedBy { it.path }

    private companion object {
        /** The guard: the head's count is bounded before the password is proven. */
        const val GUARD = """requireChainFits(container.head.count, "reads")"""

        /** The call that needs the password, and therefore has to come after the guard. */
        const val SESSION = "contentSession(container, password)"
    }
}

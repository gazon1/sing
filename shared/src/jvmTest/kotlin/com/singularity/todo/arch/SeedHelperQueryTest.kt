package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.assertTrue

/**
 * The seed helper does not read the query it is given.
 *
 * The helper is handed a query string and, optionally, the name of an element
 * to wait for. The query belongs to the seeder: the key vocabulary is the
 * seeder's (`SeedLinkKeys`), and a helper that read it would be a second
 * reader of it — a second dialect of a language the app owns, and one that no
 * test could hold to the app.
 *
 * The check is a class of its own because the requirement is a requirement of
 * the helper's *shape*, not of its waiting: the rules of the wait live in
 * `SeedHelperWaitTest`, and the rule that the helper forwards the query
 * untouched lives here.
 *
 * Run from repo root:
 * ```
 * ./gw :shared:archTest --tests "com.singularity.todo.arch.SeedHelperQueryTest"
 * ```
 */
@Tag("fast")
class SeedHelperQueryTest {
    /**
     * The tree holding the flows, passed in rather than derived.
     *
     * The path is declared in `shared/build.gradle.kts` for the reason
     * `MaestroFlowTagsTest` says it is: a derived path is invisible to
     * `scripts/check-test-task-inputs.py`, and a gate that cannot be
     * invalidated by the change it watches reports a verdict about a file it
     * never re-reads.
     */
    private val maestroRoot: Path =
        run {
            val root =
                System.getProperty("maestro.root")
                    ?: error(
                        "maestro.root system property is not set — see shared/build.gradle.kts. " +
                            "This test reads Maestro/, which is outside :shared, so the path has " +
                            "to be passed in and declared as a task input.",
                    )
            Paths.get(root)
        }

    @Test
    fun `the helper forwards the query and does not read it`() {
        val helper = maestroRoot.resolve("helpers/seed.yaml").readText()

        val problem = queryReadingProblem(helper)
        assertTrue(
            problem == null,
            "The seed helper reads the query it is given: $problem\n\nThe query " +
                "belongs to the seeder — the key vocabulary is `SeedLinkKeys` — and " +
                "the helper hands it over whole, in the deep link, and reads " +
                "nothing of it.",
        )

        // The check has to be able to fail: a helper that reads the query is
        // the thing it must not do.
        assertTrue(
            queryReadingProblem(A_HELPER_THAT_READS_THE_QUERY) != null,
            "The check accepts a helper that reads the query it is given. A check " +
                "that cannot fail holds nothing.",
        )
    }

    /**
     * The way the helper reads the query, if it reads it at all.
     *
     * A helper that reads the query has to name it: every value the deep link
     * carries comes from `QUERY`, so a helper that splits it, filters it or
     * interprets a key names the query on the way. The query may therefore
     * appear in the body of the helper once — in the deep link, which forwards
     * it whole.
     */
    private fun queryReadingProblem(helper: String): String? {
        val mentions = helper.lines().filter { line -> line.isBody() && line.contains(QUERY) }
        if (mentions.size > 1) {
            return "the helper names the query ${mentions.size} times: " +
                mentions.joinToString(" / ") { it.trim() }
        }
        val forwarded = mentions.singleOrNull() ?: return "the helper does not forward the query at all"
        if (!forwarded.contains(FORWARDED_QUERY)) {
            return "the helper does not forward the query whole: ${forwarded.trim()}"
        }
        return null
    }

    /** Whether the line is part of the helper's body rather than of its header. */
    private fun String.isBody(): Boolean = !trim().startsWith("#")

    private companion object {
        /** The name the seeder's query arrives under. */
        const val QUERY = "QUERY"

        /** The deep link the helper forwards the query in. */
        const val FORWARDED_QUERY = "todo-debug://seed?\${QUERY}"

        /**
         * A helper that reads the query.
         *
         * The fixture splits the query to see what the link names — the thing
         * the helper must not do. A helper that read the query and the seeder
         * that reads it could disagree about a key, a value or a parameter the
         * seeder does not know, and the flow would fail on the device for a
         * reason the YAML does not show.
         */
        const val A_HELPER_THAT_READS_THE_QUERY =
            """
            env:
              WAIT_FOR: "${'$'}{WAIT_FOR}"
            ---
            - evalScript: ${'$'}{QUERY.split('&').length}
            - openLink: "todo-debug://seed?${'$'}{QUERY}"
            """
    }
}

package com.singularity.todo.arch

import com.singularity.todo.core.seed.SeedQuery
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seed helper's documented key table and the handler's own keys agree.
 *
 * The table is where a flow author learns which keys exist, and the handler is
 * what decides what a key does. A key that is in one and not the other is the
 * defect this gate exists for: the health drill-down flow wrote `dependsOn=`
 * where the handler reads `blockedBy=`, and the key table did not list the
 * dependency key at all — so the misspelling was undiscoverable except by
 * reading the handler.
 *
 * The comparison is in both directions: a key the handler knows but the table
 * does not document is as broken as one the table documents and the handler
 * does not know.
 */
@Tag("fast")
class SeedQueryKeyTableTest {
    /**
     * The tree holding the helpers, passed in rather than derived.
     *
     * The helper's table is outside :shared, so the path has to be passed in
     * and declared as a task input — the reason `maestro.root` exists.
     */
    private val maestroRoot: Path =
        Paths.get(
            System.getProperty("maestro.root")
                ?: error(
                    "maestro.root system property is not set — see shared/build.gradle.kts. " +
                        "This test reads the seed helper's documented key table, which lives " +
                        "outside :shared, so the path has to be passed in and declared as a " +
                        "task input; deriving it made the gate silently stale.",
                ),
        )

    private val seedHelper = maestroRoot.resolve("helpers/seed.yaml")

    @Test
    fun `the seed helper's documented key table is the handler's set of known keys`() {
        val documented = documentedKeys()

        assertTrue(
            documented.isNotEmpty(),
            "the helper documents no keys at all — the comparison would be vacuous",
        )
        assertEquals(SeedQuery.KNOWN_KEYS, documented)
    }

    /** The keys the helper's header documents — one per documented line. */
    private fun documentedKeys(): Set<String> =
        seedHelper
            .readText()
            .lineSequence()
            .mapNotNull { line -> KEY_LINE.find(line)?.groupValues?.get(1) }
            .toSet()

    private companion object {
        /**
         * A documented key: a comment line that starts with the key and an `=`.
         *
         * The key has to be the first word after the comment marker, so the
         * usage example (`QUERY: "task=…&due=today"`) is not read as a
         * documented key of its own.
         */
        val KEY_LINE = Regex("""^\s*#\s+([A-Za-z][A-Za-z0-9]*)=""")
    }
}

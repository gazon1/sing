package com.singularity.todo.feature.ai.tools

import com.singularity.todo.test.fakes.TEST_TZ
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.core.platform.HostEnvironmentPort
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where [AdrStorage] decides to read and write, without touching a single file.
 *
 * The judgement call this class records: **it is `fast`, and it does ask the OS
 * whether a path exists.** It stats, and nothing else — no directory is created, no
 * file is written, no cleanup can be left behind, and there is no timing in it, so
 * none of the reasons `slow` exists apply. If a reviewer disagrees, the honest fix is
 * to retag this class; the round-trip half in [AdrStorageRoundTripTest] is `slow`
 * because it does create and delete directories, and that split is what lets both
 * halves be honest at once.
 *
 * Why it needs to be `fast` at all: the coverage ratchet measures a `fast`-only run,
 * and `feature/ai`'s floor is the one that guards a billing path. Code in this
 * feature whose only honest test needs the filesystem is invisible to that floor —
 * which is an argument for splitting the class along exactly this line.
 *
 * [AdrStorage] used to be an `object` reading `System.getProperty` for itself, so
 * there was no way to point it anywhere; the fake below is what the port buys.
 */
@Tag("fast")
class AdrStorageResolutionTest {

    /** A path that does not exist, on either platform, and never will. */
    private class AbsentHost(private val root: String) : HostEnvironmentPort {
        override fun workingDirectory(): String = "$root/working"
        override fun homeDirectory(): String = "$root/home"
    }

    private fun absentStorage() = AdrStorage(
        host = AbsentHost("/singularity-adr-resolution-test-absent"),
        clock = FakeClock(),
        timeZone = TEST_TZ,
    )

    @Test
    fun `with no checkout present the per-user copy is used`() {
        val storage = absentStorage()

        assertEquals(
            "/singularity-adr-resolution-test-absent/home/.singularity-todo/docs/decisions",
            storage.decisionsDir(),
        )
    }

    @Test
    fun `a slug maps to a markdown file inside the resolved directory`() {
        val storage = absentStorage()

        assertEquals(
            "${storage.decisionsDir()}/2026-10-05-example.md",
            storage.filePath("2026-10-05-example"),
        )
    }

    /** The tools must answer "nothing" for a directory that is not there, not fail. */
    @Test
    fun `an absent directory lists as empty rather than throwing`() {
        val storage = absentStorage()

        assertEquals(emptyList(), storage.listAdrs())
        assertEquals(null, storage.readAdr("2026-10-05-example"))
    }

    /** Frontmatter parsing is pure, and was untested before the class took a port. */
    @Test
    fun `frontmatter round-trips through the parser`() {
        val parsed = absentStorage().parseFrontmatter(
            "---\ntitle: \"A title\"\ndate: 2026-10-05\ntags: [\"one\", \"two\"]\n---\n\nBody.",
        )

        assertEquals("A title", parsed?.title)
        assertEquals("2026-10-05", parsed?.date)
        assertEquals(listOf("one", "two"), parsed?.tags)
    }

    @Test
    fun `a document with no frontmatter parses as nothing rather than guessing`() {
        assertEquals(null, absentStorage().parseFrontmatter("Just a body."))
    }

    /**
     * The ancestor walk has to stop at the filesystem root.
     *
     * It did not, and the failure mode was invisible: `""` was fed back into its own
     * `substringBeforeLast('/', "")`, so [AdrStorage]'s constructor spun in `Files.exists`
     * on a core and the run simply never finished. No failure, no message — just a
     * build that hangs. Hence the explicit bound: a regression has to arrive as a red
     * test within five seconds, not as a CI job somebody kills by hand.
     */
    @Test
    fun `resolving the corpus config terminates instead of looping at the root`() {
        val storage = assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            AdrStorage(
                host = AbsentHost("/singularity-adr-deep/nested/checkout"),
                clock = FakeClock(),
                timeZone = TEST_TZ,
            )
        }

        assertEquals(
            "/singularity-adr-deep/nested/checkout/home/.singularity-todo/docs/decisions",
            storage.decisionsDir(),
        )
    }
}

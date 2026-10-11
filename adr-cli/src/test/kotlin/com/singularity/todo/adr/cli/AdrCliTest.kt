package com.singularity.todo.adr.cli

import com.singularity.todo.core.platform.HostEnvironmentPort
import com.singularity.todo.feature.ai.tools.AdrStorage
import com.singularity.todo.adr.cli.TEST_TZ
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The `singularity-adr` command line, in-process.
 *
 * Tagged `slow`: every case writes and deletes real directories under the corpus rules,
 * which crosses the process boundary — that is the project's own definition of `slow`.
 *
 * Two things are being defended here. The first is the exit code: `adr updat` used to
 * print usage and exit 0, so a typo in a CI script looked like success. The second is
 * the `--json` contract (#524): once an agent parses that output, renaming a field is
 * a breaking change, and a test is the only thing that notices.
 */
@Tag("slow")
class AdrCliTest {

    private val temporaries = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        temporaries.forEach { it.deleteRecursively() }
        temporaries.clear()
    }

    private class FixedHost(private val workingDir: String, private val homeDir: String) : HostEnvironmentPort {
        override fun workingDirectory(): String = workingDir
        override fun homeDirectory(): String = homeDir
    }

    private val out = StringBuilder()
    private val err = StringBuilder()

    /** A checkout-shaped temp directory: `docs/decisions/` exists, `scripts/` does not. */
    private fun cli(withRepoScripts: Boolean = false): Pair<AdrCli, File> {
        val working = createTempDirectory("adr-cli").toFile().also { temporaries += it }
        working.resolve("docs/decisions").mkdirs()
        if (withRepoScripts) working.resolve("scripts").mkdirs()
        val storage = AdrStorage(
            host = FixedHost(working.absolutePath, working.resolve("home").absolutePath),
            clock = FixedClock,
            timeZone = TEST_TZ,
        )
        return AdrCli(storage, out, err, working.absolutePath) to working
    }

    @Test
    fun `an unknown subcommand fails instead of looking like success`() {
        val (cli, _) = cli()

        val code = cli.run(listOf("updat", "2026-10-05-x", "accepted"))

        assertEquals(ExitCode.USAGE, code, "a typo must not exit 0 — that is #510")
        assertTrue(err.contains("unknown subcommand 'updat'"), err.toString())
    }

    @Test
    fun `no arguments at all is a usage error, not a run`() {
        val (cli, _) = cli()

        assertEquals(ExitCode.USAGE, cli.run(emptyList()))
    }

    @Test
    fun `new then update then read round-trips`() {
        val (cli, _) = cli()
        assertEquals(ExitCode.OK, cli.run(listOf("new", "2026-10-10-round", "Round trip", "arch")))

        val updateCode = cli.run(listOf("update", "2026-10-10-round", "accepted"))
        assertEquals(ExitCode.OK, updateCode, "err=${errText()}")

        out.clear()
        assertEquals(ExitCode.OK, cli.run(listOf("read", "2026-10-10-round")))
        assertTrue(out.contains("status: accepted"), "expected the updated status, got:\n${outText()}")
    }

    @Test
    fun `update is the wired path, not a copy of writeAdr`() {
        val (cli, working) = cli()
        cli.run(listOf("new", "2026-10-10-wired", "Wired", "arch"))

        cli.run(listOf("update", "2026-10-10-wired", "accepted"))

        // Written into the checkout, and only the frontmatter changed.
        val text = File(working, "docs/decisions/2026-10-10-wired.md").readText()
        assertTrue(text.contains("status: accepted"), text)
        assertTrue(text.contains("title: \"Wired\""), text)
    }

    @Test
    fun `update refuses a status outside the vocabulary and writes nothing`() {
        val (cli, working) = cli()
        cli.run(listOf("new", "2026-10-10-vocab", "Vocab"))
        val before = File(working, "docs/decisions/2026-10-10-vocab.md").readText()

        val code = cli.run(listOf("update", "2026-10-10-vocab", "CLOSED"))

        assertEquals(ExitCode.ERROR, code)
        assertEquals(before, File(working, "docs/decisions/2026-10-10-vocab.md").readText())
    }

    @Test
    fun `superseded without a target explains itself`() {
        val (cli, _) = cli()
        cli.run(listOf("new", "2026-10-10-sup", "Superseded"))

        val code = cli.run(listOf("update", "2026-10-10-sup", "superseded"))

        assertEquals(ExitCode.ERROR, code)
        assertTrue(err.contains("requires supersededBy"), errText())
    }

    @Test
    fun `update with no status is a usage error`() {
        val (cli, _) = cli()
        cli.run(listOf("new", "2026-10-10-args", "Args"))

        assertEquals(ExitCode.USAGE, cli.run(listOf("update", "2026-10-10-args")))
        assertTrue(err.contains("needs <slug> <status>"), errText())
    }

    @Test
    fun `list --json emits the documented shape`() {
        val (cli, _) = cli()
        cli.run(listOf("new", "2026-10-10-json", "JSON row", "arch"))

        out.clear()
        assertEquals(ExitCode.OK, cli.run(listOf("list", "--json")))

        val root = Json.parseToJsonElement(outText()).jsonObject
        val row = root.getValue("adrs").jsonArray.first().jsonObject
        assertEquals("2026-10-10-json", row.getValue("slug").jsonPrimitive.content)
        assertEquals("JSON row", row.getValue("title").jsonPrimitive.content)
        assertEquals(listOf("arch"), row.getValue("tags").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `the json flag is accepted before the subcommand arguments`() {
        val (cli, _) = cli()
        cli.run(listOf("new", "2026-10-10-order", "Order"))

        out.clear()
        assertEquals(ExitCode.OK, cli.run(listOf("read", "--json", "2026-10-10-order")))

        assertEquals(
            "2026-10-10-order",
            Json.parseToJsonElement(outText()).jsonObject.getValue("slug").jsonPrimitive.content,
        )
    }

    @Test
    fun `a flag value is not mistaken for a positional argument`() {
        val (cli, _) = cli()
        cli.run(listOf("new", "2026-10-10-flagval", "Flag value"))

        out.clear()
        assertEquals(ExitCode.OK, cli.run(listOf("list", "--days", "7")))
        assertTrue(out.contains("2026-10-10-flagval"), outText())
    }

    @Test
    fun `stale reports a decision older than the threshold`() {
        val (cli, working) = cli()
        val decisions = File(working, "docs/decisions")
        // 200 days old, comfortably past the 30-day gate threshold.
        decisions.resolve("2026-01-01-old.md").writeText(
            "---\ntitle: \"Old\"\ndate: 2026-01-01\nstatus: open\ntags: []\n---\n\nBody.\n",
        )

        out.clear()
        assertEquals(ExitCode.OK, cli.run(listOf("stale")))

        assertTrue(out.contains("2026-01-01-old"), outText())
        // The age is the whole point of the command — the gate reports only that it failed.
        assertTrue(AGE_SUFFIX.containsMatchIn(outText()), "expected an age in the output:\n${outText()}")
    }

    @Test
    fun `stale ignores decisions that are not in the status it was asked about`() {
        val (cli, working) = cli()
        File(working, "docs/decisions").resolve("2026-01-01-accepted.md").writeText(
            "---\ntitle: \"Accepted\"\ndate: 2026-01-01\nstatus: accepted\ntags: []\n---\n\nBody.\n",
        )

        out.clear()
        assertEquals(ExitCode.OK, cli.run(listOf("stale")))

        assertTrue(!out.contains("2026-01-01-accepted"), outText())
    }

    @Test
    fun `stale --json carries the age so a script does not have to compute it`() {
        val (cli, working) = cli()
        File(working, "docs/decisions").resolve("2026-01-01-old.md").writeText(
            "---\ntitle: \"Old\"\ndate: 2026-01-01\nstatus: open\ntags: []\n---\n\nBody.\n",
        )

        out.clear()
        assertEquals(ExitCode.OK, cli.run(listOf("stale", "--json")))

        val root = Json.parseToJsonElement(outText()).jsonObject
        assertEquals(30, root.getValue("days").jsonPrimitive.content.toInt())
        val entry = root.getValue("entries").jsonArray.first().jsonObject
        assertEquals("2026-01-01-old", entry.getValue("slug").jsonPrimitive.content)
        assertTrue(entry.getValue("ageDays").jsonPrimitive.content.toInt() >= 30)
    }

    @Test
    fun `a decision dated in the future is not reported as stale`() {
        val (cli, working) = cli()
        File(working, "docs/decisions").resolve("2999-01-01-future.md").writeText(
            "---\ntitle: \"Future\"\ndate: 2999-01-01\nstatus: open\ntags: []\n---\n\nBody.\n",
        )

        out.clear()
        assertEquals(ExitCode.OK, cli.run(listOf("stale")))

        assertTrue(!out.contains("2999-01-01-future"), outText())
    }

    @Test
    fun `prune lists but never deletes`() {
        val (cli, working) = cli(withRepoScripts = true)
        // A stub, not the real gate: this case is about the CLI's contract with its
        // dependency — invocation, parsing, and the absence of a delete path. The
        // scanner itself is tested where it lives.
        writeStubScanner(working, cited = setOf("2026-10-10-cited"))
        cli.run(listOf("new", "2026-10-10-uncited", "Uncited"))
        cli.run(listOf("new", "2026-10-10-cited", "Cited"))
        val path = File(working, "docs/decisions/2026-10-10-uncited.md")
        assertTrue(path.exists())

        out.clear()
        val code = cli.run(listOf("prune"))

        assertEquals(ExitCode.OK, code, "err=${errText()}")
        assertTrue(path.exists(), "prune must not delete — it has no delete path at all")
        assertTrue(out.contains("2026-10-10-uncited"), "the uncited decision should be listed:\n${outText()}")
        assertTrue(!out.contains("2026-10-10-cited "), "a cited decision is not a candidate:\n${outText()}")
    }

    @Test
    fun `prune --keep protects a decision even when nothing cites it`() {
        val (cli, working) = cli(withRepoScripts = true)
        writeStubScanner(working, cited = emptySet())
        cli.run(listOf("new", "2026-10-10-kept", "Kept"))

        out.clear()
        assertEquals(ExitCode.OK, cli.run(listOf("prune", "--keep", "2026-10-10-kept")))

        assertTrue(!out.contains("2026-10-10-kept"), "--keep must survive as a positional guard:\n${outText()}")
    }

    @Test
    fun `a scanner that fails is reported, not swallowed`() {
        val (cli, working) = cli(withRepoScripts = true)
        File(working, "scripts/check-adr-references.py").writeText("import sys\nsys.exit(3)\n")

        val code = cli.run(listOf("prune"))

        assertEquals(ExitCode.ERROR, code)
        assertTrue(err.contains("check-adr-references.py failed"), errText())
    }

    @Test
    fun `prune without the repository scripts says so instead of guessing`() {
        val (cli, _) = cli(withRepoScripts = false)

        assertEquals(ExitCode.ERROR, cli.run(listOf("prune")))
        assertTrue(err.contains("check-adr-references.py"), errText())
    }

    @Test
    fun `list-open finds backlog entries that the corpus can now see`() {
        val (cli, working) = cli()
        val backlog = File(working, "docs/decisions/deferred")
        backlog.mkdirs()
        backlog.resolve("planned-thing.md").writeText(
            "---\ntitle: \"Planned\"\ndate: 2000-01-01\nstatus: open\ntags: [\"deferred\"]\n---\n\nLater.\n",
        )
        backlog.resolve("done-thing.md").writeText(
            "---\ntitle: \"Done\"\ndate: 2000-01-01\nstatus: closed\ntags: [\"deferred\"]\n---\n\nLater.\n",
        )

        out.clear()
        assertEquals(ExitCode.OK, cli.run(listOf("list-open")))

        assertTrue(out.contains("planned-thing"), outText())
        assertTrue(!out.contains("done-thing"), "a closed backlog entry is not open:\n${outText()}")
    }

    @Test
    fun `validate refuses to pretend when it is not in a repository`() {
        val (cli, _) = cli(withRepoScripts = false)

        assertEquals(ExitCode.ERROR, cli.run(listOf("validate")))
        assertTrue(err.contains("run from the repository root"), errText())
    }

    private fun writeStubScanner(repo: File, cited: Set<String>) {
        val body = cited.joinToString("\n") { "print('$it')" }
        File(repo, "scripts/check-adr-references.py").writeText(
            "# stub for AdrCliTest — the real scanner is tested in scripts/tests\n$body\n",
        )
    }

    private fun outText(): String = out.toString()
    private fun errText(): String = err.toString()

    private companion object {
        /** Matches the trailing `283d` age column in `adr stale` output. */
        val AGE_SUFFIX = Regex("""\b\d+d$""", RegexOption.MULTILINE)

        val FixedClock: Clock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-10-11T00:00:00Z")
        }
    }
}
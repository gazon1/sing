package com.singularity.todo.arch

import com.singularity.todo.core.ui.TestTags
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.assertTrue

/**
 * The seed helper waits for the entity the caller names.
 *
 * The helper used to wait for the shell and nothing else: it brought the app
 * up and returned, and every flow that seeded a row had to wait for that row
 * itself. A flow that forgot the wait asserted on a row that had not arrived
 * yet, and failed on a device for a reason the YAML does not show.
 *
 * The helper now takes an optional `WAIT_FOR` — the element the flow is going
 * to assert on — and waits for it before it returns. This test holds the three
 * halves of that contract:
 *
 *  - the *helper* keeps the wait, and the wait is optional and guarded: a seed
 *    that writes no entity (`session=`, `cleanup=`, `reset=`) has no row to
 *    wait for, and a wait for an element nobody named would fail every such
 *    seed;
 *  - a *caller* that names an entity names one the seed wrote — a title-named
 *    entity by the row the app renders for that title, an id-named entity by
 *    that id;
 *  - every flow that *asserts* a seeded entity waits for it first, either in
 *    the helper (through `WAIT_FOR`) or in the flow itself.
 *
 * The last rule is the one the flows were written against before the helper
 * grew the wait, and it is what keeps a new flow from repeating the mistake:
 * a bare `assertVisible` on a seeded row is not a wait.
 *
 * Run from repo root:
 * ```
 * ./gw :shared:archTest --tests "com.singularity.todo.arch.SeedHelperWaitTest"
 * ```
 */
@Tag("fast")
class SeedHelperWaitTest {
    /**
     * The tree holding the flows, passed in rather than derived.
     *
     * The path is declared in `shared/build.gradle.kts` for the reason
     * `MaestroFlowTagsTest` says it is: a derived path is invisible to
     * `scripts/check-test-task-inputs.py`, and a gate that cannot be
     * invalidated by the change it watches reports a verdict about a file it
     * never re-read.
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

    /** The worktree root, for the paths that are not under Maestro/. */
    private val workspaceRoot: Path = maestroRoot.parent

    @Test
    fun `the seed helper waits for the entity the caller names`() {
        val lines = maestroRoot.resolve("helpers/seed.yaml").readText().lines()

        // The input is optional. A caller that names no entity leaves WAIT_FOR
        // undefined, and the helper turns that into an empty target rather
        // than into Maestro's missing-variable error — which is what makes the
        // input optional at all.
        assertTrue(
            lines.any { it.trim() == OPTIONAL_INPUT },
            "The seed helper does not derive its wait target from WAIT_FOR. " +
                "The helper has to read the caller's WAIT_FOR through a guard that " +
                "turns an undefined one into an empty target — otherwise every " +
                "caller that names no entity fails on a missing variable.",
        )

        // The wait is guarded for the same reason: a seed that writes no entity
        // has no row to wait for, and a wait for an element nobody named would
        // fail every such seed.
        val guard = lines.indexOfFirst { it.trim() == GUARD }
        assertTrue(
            guard >= 0,
            "The seed helper's entity wait is not guarded by \"$GUARD\". A seed " +
                "that writes no entity — session=, cleanup=, reset= — has no row to " +
                "wait for, so the wait has to be skipped when no entity is named.",
        )

        // The guarded block waits for the named element, and it waits — it does
        // not assert. The timeout of the wait is held to the budget of its kind
        // by scripts/check-ui-wait-budgets.py, which reads the waits nested in
        // a conditional runFlow as well.
        val guarded = lines.drop(guard).takeWhile { !it.startsWith("- ") }
        assertTrue(
            guarded.any { it.trim() == "- extendedWaitUntil:" },
            "The seed helper's entity wait is not an extendedWaitUntil. A wait is " +
                "what absorbs a slow arrival; an assertion fails the flow instead.",
        )
        assertTrue(
            guarded.any { it.trim() == WAIT_TARGET_ID },
            "The seed helper's entity wait does not target \$WAIT_TARGET. The helper " +
                "waits for the element the caller named — the id the caller wrote " +
                "in WAIT_FOR.",
        )
    }

    @Test
    fun `a seed that writes no entity names no entity to wait for`() {
        val violations = mutableListOf<String>()
        var checked = 0

        forEachFile { file, events ->
            for (seed in events.filterIsInstance<Event.Seed>()) {
                val (rows, _) = entityTargets(parseQuery(seed.query))
                checked++
                if (rows.isEmpty() && seed.waitFor != null) {
                    violations +=
                        "${rel(file)}: a seed that writes no entity names " +
                        "${seed.waitFor} to wait for — there is no row to wait for"
                }
            }
        }

        assertTrue(
            checked >= 50,
            "scanned only $checked seeds — the collector is likely broken " +
                "(expected >= 50 on the current tree)",
        )
        assertTrue(
            violations.isEmpty(),
            "Seeds that write no entity but name one to wait for " +
                "(${violations.size}/$checked):\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nA seed that changes the session, cleans up or resets the " +
                "profile writes no row. Such a flow names no entity, and the " +
                "helper waits for the shell alone.",
        )
    }

    @Test
    fun `a WAIT_FOR names an entity the seed wrote`() {
        val violations = mutableListOf<String>()
        var checked = 0

        forEachFile { file, events ->
            val named =
                events.filterIsInstance<Event.Seed>().mapNotNull { seed ->
                    val waitFor = seed.waitFor ?: return@mapNotNull null
                    val (rows, containers) = entityTargets(parseQuery(seed.query))
                    if (rows.isEmpty()) {
                        null
                    } else {
                        Triple(seed, waitFor, (rows + containers).toSet())
                    }
                }
            for ((seed, waitFor, targets) in named) {
                checked++
                if (waitFor !in targets) {
                    violations +=
                        "${rel(file)}: WAIT_FOR=$waitFor is not an element of the " +
                        "seed \"${seed.query}\" — the seed writes ${targets.joinToString()}"
                }
            }
        }

        assertTrue(
            checked >= 1,
            "scanned only $checked WAIT_FORs — the collector is likely broken",
        )
        assertTrue(
            violations.isEmpty(),
            "WAIT_FORs that name an entity the seed did not write " +
                "(${violations.size}/$checked):\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nA title-named entity is named by the row the app renders for " +
                "that title (TestTags.taskItem, TestTags.noteItemByTitle), an " +
                "id-named one by that id (TestTags.menuProjectNode).",
        )
    }

    @Test
    fun `every flow that asserts a seeded entity waits for it first`() {
        val violations = mutableListOf<String>()
        var checked = 0
        var parsedAsserts = 0

        forEachFile { file, events ->
            val seeds = events.filterIsInstance<Event.Seed>()
            val waits = events.filterIsInstance<Event.Wait>()
            val asserts = events.filterIsInstance<Event.Assert>()
            parsedAsserts += asserts.size
            for (seed in seeds) {
                val (rows, containers) = entityTargets(parseQuery(seed.query))
                if (rows.isEmpty()) continue
                val targets = (rows + containers).toSet()
                for (assert in asserts) {
                    if (assert.id !in rows) continue
                    checked++
                    // A wait in the flow, or the helper's own wait for the
                    // entity the caller named — either way, before the assert.
                    val waited =
                        waits.any { it.id in targets && it.position <= assert.position } ||
                            seeds.any {
                                it.waitFor != null &&
                                    it.waitFor in targets &&
                                    it.position <= assert.position
                            }
                    if (!waited) {
                        violations +=
                            "${rel(file)}: asserts $assert.id without waiting for it — " +
                            "wait for the row (${rows.joinToString()}) or for the " +
                            "container it is in (${containers.joinToString()})"
                    }
                }
            }
        }

        // The collector has to find the tree's assertions at all, and the rule
        // has to have something to hold.
        assertTrue(
            parsedAsserts >= 100,
            "parsed only $parsedAsserts assertions in the tree — the collector is " +
                "likely broken (expected >= 100 on the current tree)",
        )
        assertTrue(
            checked >= 10,
            "checked only $checked assertions on seeded entities — the collector is " +
                "likely broken (expected >= 10 on the current tree)",
        )
        assertTrue(
            violations.isEmpty(),
            "Assertions on a seeded entity that nothing waited for " +
                "(${violations.size}/$checked):\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nA bare assertVisible is not a wait: the row may not have " +
                "arrived yet, and the flow fails on a device for a reason the " +
                "YAML does not show. Wait for the row, or for the container that " +
                "renders only because the entity is in it.",
        )
    }

    // ── The flow as events ───────────────────────────────────────────────────

    /**
     * One thing a flow does, and where it does it.
     *
     * The position is the index of the top-level command, so a wait that comes
     * after an assertion cannot cover it. A helper's own commands take the
     * position of the `runFlow` that pulled the helper in: the helper runs
     * there, so its waits are available from there.
     */
    private sealed interface Event {
        val position: Int

        /** A seed: the query it seeds and the entity the caller wants waited for. */
        data class Seed(override val position: Int, val query: String, val waitFor: String?) : Event

        /** A wait for an element. */
        data class Wait(override val position: Int, val id: String) : Event

        /** An assertion that an element is there. */
        data class Assert(override val position: Int, val id: String) : Event
    }

    /** One top-level command of a Maestro file. */
    private data class Command(
        val name: String,
        val ids: List<String>,
        val env: Map<String, String>,
        val file: String?,
    )

    /** Every Maestro file, with the events it makes. */
    private fun forEachFile(action: (Path, List<Event>) -> Unit) {
        Files.walk(maestroRoot).use { stream ->
            stream
                .filter { it.isRegularFile() && it.extension in listOf("yaml", "yml") }
                .forEach { file -> action(file, eventsOf(file)) }
        }
    }

    /** The events a file makes, helpers and all. */
    private fun eventsOf(file: Path): List<Event> {
        val events = mutableListOf<Event>()
        collect(file, emptyMap(), null, events, setOf(file.normalize()))
        return events
    }

    /**
     * Reads the commands of a file and the commands of the helpers it runs.
     */
    private fun collect(
        file: Path,
        env: Map<String, String>,
        position: Int?,
        events: MutableList<Event>,
        seen: Set<Path>,
    ) {
        for ((index, command) in commands(file.readText()).withIndex()) {
            val at = position ?: index
            val target = command.file?.let { file.parent.resolve(it).normalize() }
            if (command.name == "runFlow" && target != null) {
                collectRunFlow(target, command, env, at, events, seen)
                continue
            }
            val ids = command.ids.map { substitute(it, env) }
            when (command.name) {
                in WAIT_COMMANDS -> ids.forEach { events += Event.Wait(at, it) }
                in ASSERT_COMMANDS -> ids.forEach { events += Event.Assert(at, it) }
            }
        }
    }

    /**
     * Runs the flow a `runFlow` names, or records the seed it is.
     *
     * The seed helper is not descended into: it is the one file whose own
     * `env:` block would answer for the caller. What it does with the query it
     * is given is a seed event — the query, and the entity the caller wants
     * waited for.
     */
    private fun collectRunFlow(
        target: Path,
        command: Command,
        env: Map<String, String>,
        at: Int,
        events: MutableList<Event>,
        seen: Set<Path>,
    ) {
        if (!target.isRegularFile()) return
        val inherited = env + command.env.mapValues { (_, value) -> substitute(value, env) }
        if (target.fileName?.toString() == SEED_HELPER) {
            events += Event.Seed(at, inherited["QUERY"].orEmpty(), inherited["WAIT_FOR"])
            return
        }
        val normalized = target.normalize()
        if (normalized in seen) return
        collect(target, inherited, at, events, seen + normalized)
    }

    /**
     * The top-level commands of a Maestro file.
     *
     * The commands are read as the lines they are written as — the tree's
     * Maestro files are read the same way by `MaestroFlowTagsTest`, and a
     * second YAML parser here would be a second dialect of the same language.
     */
    private fun commands(text: String): List<Command> {
        val lines = text.lines()
        val out = mutableListOf<Command>()
        var index = 0
        while (index < lines.size) {
            val header = COMMAND.matchEntire(lines[index])
            if (header == null) {
                index++
                continue
            }
            val name = header.groupValues[1]
            val inline = header.groupValues[2].trim()
            val block = mutableListOf<String>()
            index++
            while (index < lines.size && COMMAND.matchEntire(lines[index]) == null) {
                block += lines[index]
                index++
            }
            val parsed = parseBlock(block)
            val file = parsed.file ?: inline.takeIf { name == "runFlow" && it.isNotEmpty() }
            out += Command(name, parsed.ids, parsed.env, file)
        }
        return out
    }

    /** What the body of a command says: the ids it addresses, its env, its file. */
    private data class Block(val ids: List<String>, val env: Map<String, String>, val file: String?)

    /** Reads the body of a command. */
    private fun parseBlock(block: List<String>): Block {
        var inEnv = false
        val ids = mutableListOf<String>()
        val env = mutableMapOf<String, String>()
        var file: String? = null
        for (line in block) {
            val trimmed = line.trim()
            val fileMatch = FILE_KEY.matchEntire(line)
            val idMatch = ID_KEY.matchEntire(line)
            val envMatch = ENV_KEY.matchEntire(line)
            when {
                trimmed.startsWith("#") -> Unit

                trimmed == "env:" -> inEnv = true

                fileMatch != null && !inEnv -> file = fileMatch.groupValues[1]

                idMatch != null -> ids += idMatch.groupValues[1]

                inEnv && envMatch != null -> env[envMatch.groupValues[1]] = envMatch.groupValues[2]

                // Another key ends the env block.
                KEY_ONLY.matchEntire(trimmed) != null -> inEnv = false
            }
        }
        return Block(ids, env, file)
    }

    /** Replaces `${NAME}` with what the caller passed for it. */
    private fun substitute(
        value: String,
        env: Map<String, String>,
    ): String = ENV_REFERENCE.replace(value) { match -> env[match.groupValues[1]] ?: match.value }

    // ── What a seed writes ───────────────────────────────────────────────────

    /** The parameters of a seed query. */
    private fun parseQuery(query: String): Map<String, String> =
        query
            .split("&")
            .filter { it.contains("=") }
            .associate { pair ->
                pair.substringBefore("=").trim() to decode(pair.substringAfter("="))
            }

    /**
     * Decodes a query value the way the seeder reads it.
     *
     * Android's `Uri` does not read a `+` as a space, so `due=+1d` is "+1d" for
     * the seeder — and `URLDecoder`, which is written for form encoding, would
     * have read it as " 1d".
     */
    private fun decode(raw: String): String {
        if (!raw.contains('%') && !raw.contains('+')) return raw
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8)
    }

    /**
     * The elements the app renders for the entity a seed query creates.
     *
     * The row of a title-named entity is the app's own slug of that title, so
     * the ids are produced by `TestTags` and not by a second copy of the slug
     * rule living in this test.
     *
     * The container is the agenda section the entity is in. A section renders
     * because it holds a task, so waiting for it is waiting for the entity —
     * as long as it is the section the entity belongs to.
     */
    private fun entityTargets(params: Map<String, String>): Pair<List<String>, List<String>> {
        val rows = mutableListOf<String>()
        val containers = mutableListOf<String>()
        params["task"]?.let { title ->
            val count = params["count"]?.toIntOrNull() ?: 1
            if (count > 1) {
                (1..count).forEach { index -> rows += TestTags.taskItem("$title-$index") }
            } else {
                rows += TestTags.taskItem(title)
            }
            DUE_SECTIONS[params["due"]]?.let { section ->
                containers += TestTags.agendaSection(section)
            }
        }
        params["note"]?.let { title -> rows += TestTags.noteItemByTitle(title) }
        params["project"]?.let { name ->
            rows += TestTags.projectCard(name)
            params["id"]?.let { id -> rows += TestTags.menuProjectNode(id) }
        }
        params["projects"]?.toIntOrNull()?.let { count ->
            (1..count).forEach { index ->
                rows += TestTags.projectCard("Bulk ${index.toString().padStart(3, '0')}")
            }
        }
        params["profile"]?.let { name -> rows += TestTags.profileItem(name) }
        return rows to containers
    }

    /** The path of a file, relative to the worktree root. */
    private fun rel(file: Path): String = workspaceRoot.relativize(file).toString()

    private companion object {
        /** The helper that seeds. Its `env:` block answers for the caller. */
        const val SEED_HELPER = "seed.yaml"

        /** The helper's derivation of the caller's optional input. */
        const val OPTIONAL_INPUT =
            "WAIT_TARGET: \"\${typeof WAIT_FOR === 'undefined' ? '' : WAIT_FOR}\""

        /** The condition that skips the wait when no entity is named. */
        const val GUARD = "true: \"\${WAIT_TARGET !== ''}\""

        /** The element the helper waits for. */
        const val WAIT_TARGET_ID = "id: \"\${WAIT_TARGET}\""

        /** The commands that wait for an element. */
        val WAIT_COMMANDS = setOf("extendedWaitUntil", "scrollUntilVisible")

        /** The commands that assert an element is there. */
        val ASSERT_COMMANDS = setOf("assertVisible")

        /**
         * The agenda section a task is in, by its due date.
         *
         * Only the sections that are named the same way whatever the day is:
         * Overdue, Today, Tomorrow. A task due further out is grouped under
         * its date, and that name is not knowable from the query — so such a
         * task is waited for by its row.
         */
        val DUE_SECTIONS =
            mapOf(
                "-1d" to "Overdue",
                "today" to "Today",
                "tomorrow" to "Tomorrow",
                "+1d" to "Tomorrow",
            )

        /** A top-level command: `- name: …`. */
        val COMMAND = Regex("""^- ([a-zA-Z][a-zA-Z0-9]*):\s*(.*)$""")

        /** The `id:` of a selector. */
        val ID_KEY = Regex("""^\s+id:\s*"?([^"\n#]*)"?\s*$""")

        /** The `file:` of a `runFlow:` that carries its path in a mapping. */
        val FILE_KEY = Regex("""^\s+file:\s*([^\s:]+)\s*$""")

        /** A key of an `env:` block — the env keys are upper case. */
        val ENV_KEY = Regex("""^\s+([A-Z_][A-Z0-9_]*):\s*"?([^"\n]*)"?\s*$""")

        /** A key with no value — it ends the env block it is in. */
        val KEY_ONLY = Regex("""^[a-zA-Z_][a-zA-Z0-9_]*:\s*$""")

        /** An env reference: `${NAME}`. */
        val ENV_REFERENCE = Regex("""\$\{([^}]*)}""")
    }
}

package com.singularity.todo.arch

import com.singularity.todo.core.ui.TestTags
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * How the tree's flows are read, and the rule those flows are held to.
 *
 * The gate reads a flow the way it runs: every command carries the path of its
 * own place in the flow — its index in its file, behind the `runFlow` that
 * pulled that file in — so a wait that runs *before* the seed inside a helper
 * is before the seed, and not beside it.
 *
 * The rule is the one the helper's contract promises: a flow that asserts on
 * an entity it seeded waits for that entity first. A wait covers an assertion
 * when it names the entity — by the row the app renders it under, by the name
 * it is called, or by the container that renders only because the entity is in
 * it — and when it stands between the seed that wrote the entity and the
 * assertion.
 */
internal object SeedWaitTree {
    // ── The rule ─────────────────────────────────────────────────────────────

    /**
     * The assertions on a seeded entity that no wait covers.
     *
     * A wait covers an assertion when it names the entity — by the row the app
     * renders it under, by the name it is called, or by the container that
     * renders only because the entity is in it — and when it sits between the
     * seed that wrote the entity and the assertion.
     *
     * The seed matters twice over. A wait before the seed says nothing about
     * the entity, and a seed that rewrote the row needs a wait of its own: the
     * row is the same row, but what the flow asserts on is what the *last*
     * seed wrote.
     */
    fun waitViolations(
        label: String,
        events: List<Event>,
    ): RuleResult {
        val violations = mutableListOf<String>()
        var checked = 0

        val seeds = events.filterIsInstance<Event.Seed>()
        val waits = events.filterIsInstance<Event.Wait>()
        val asserts = events.filterIsInstance<Event.Assert>()

        for (seed in seeds) {
            val seeded = seededEntity(parseQuery(seed.query))
            for (entity in seeded.entities) {
                val handles = (entity.rows + entity.names + seeded.containers).toSet()
                for (assert in asserts) {
                    if (!assertsEntity(assert, entity)) continue
                    checked++
                    // A wait in the flow, or the helper's own wait for the entity
                    // the caller named — either way, after the seed and before the
                    // assert. A wait that names the element under `notVisible`
                    // waits for it to be gone, so it covers nothing.
                    val waited =
                        waits.any {
                            it.positive &&
                                it.handles.any { handle -> handle in handles } &&
                                compareOrder(it.position, seed.position) >= 0 &&
                                compareOrder(it.position, assert.position) <= 0
                        } ||
                            seeds.any {
                                it.waitFor != null &&
                                    it.waitFor in handles &&
                                    compareOrder(it.position, seed.position) >= 0 &&
                                    compareOrder(it.position, assert.position) <= 0
                            }
                    if (!waited) {
                        violations +=
                            "$label: asserts ${subjectOf(assert)} without waiting for it — " +
                            "wait for the row (${entity.rows.joinToString()}), for the " +
                            "name it is called (${entity.names.joinToString()}) or for " +
                            "the container it is in (${seeded.containers.joinToString()})"
                    }
                }
            }
        }
        return RuleResult(violations, checked)
    }

    /**
     * Whether the assertion is about the entity the seed wrote.
     *
     * An assertion by id is about the entity when the id is the row the app
     * renders it under; an assertion by text is about it when the text is the
     * name the entity is called. A text that is not the name — a count, a
     * heading, a pattern — is not about the entity.
     *
     * The entity is the one the assertion names, not the seed it came from: a
     * seed that writes several rows writes several entities, and a wait for
     * one of them says nothing about the others.
     */
    private fun assertsEntity(
        assert: Event.Assert,
        entity: Entity,
    ): Boolean =
        (assert.id != null && assert.id in entity.rows) ||
            (assert.text != null && assert.text in entity.names)

    /** What the assertion names, for the message. */
    private fun subjectOf(assert: Event.Assert): String = assert.id?.let { "id: $it" } ?: "text: ${assert.text}"

    /** What the rule found: the assertions nothing waited for, and how many it looked at. */
    data class RuleResult(val violations: List<String>, val checked: Int)

    // ── The flow as events ───────────────────────────────────────────────────

    /**
     * One thing a flow does, and where it does it.
     *
     * The position is the path of the command: the index of the command in its
     * own file, prefixed by the path of the `runFlow` that pulled that file in.
     * So the first command of the helper invoked at index 2 sits at `[2, 0]`,
     * behind the `runFlow` at `[2]` that invoked it and ahead of the command
     * at `[3]`.
     *
     * The path is what keeps the order honest inside a helper. A flat index
     * would give every command of a helper the index of the `runFlow` that
     * pulled it in, and a wait that ran *before* the seed inside that helper
     * would then look like it ran after it.
     *
     * A helper's own seed takes the position of the `runFlow` that pulled it
     * in: the helper runs there, so its wait is available from there.
     */
    sealed interface Event {
        val position: List<Int>

        /** A seed: the query it seeds and the entity the caller wants waited for. */
        data class Seed(override val position: List<Int>, val query: String, val waitFor: String?) : Event

        /** A wait for an element — by its id, or by the text it shows. */
        data class Wait(override val position: List<Int>, val handles: Set<String>, val positive: Boolean) : Event

        /** An assertion that an element is there — by its id, or by the text it shows. */
        data class Assert(override val position: List<Int>, val id: String?, val text: String?) : Event
    }

    /**
     * The order of two command paths.
     *
     * A path that is a prefix of another comes first: the `runFlow` at `[2]`
     * runs before the first command of the helper it invokes at `[2, 0]`.
     */
    private fun compareOrder(
        a: List<Int>,
        b: List<Int>,
    ): Int {
        for (index in 0 until minOf(a.size, b.size)) {
            val difference = a[index].compareTo(b[index])
            if (difference != 0) return difference
        }
        return a.size.compareTo(b.size)
    }

    /** One top-level command of a Maestro file. */
    private data class Command(
        val name: String,
        val ids: List<Selector>,
        val texts: List<Selector>,
        val env: Map<String, String>,
        val file: String?,
    )

    /**
     * What a command names, and whether it waits for it to be there.
     *
     * A wait that names an element under `notVisible` waits for the element to
     * be *gone* — the opposite of an arrival — so it cannot cover a seed.
     */
    private data class Selector(val value: String, val positive: Boolean)

    /** Every Maestro file, with the events it makes. */
    fun forEachFile(
        maestroRoot: Path,
        action: (Path, List<Event>) -> Unit,
    ) {
        Files.walk(maestroRoot).use { stream ->
            stream
                .filter { it.isRegularFile() && it.extension in listOf("yaml", "yml") }
                .forEach { file -> action(file, eventsOf(file)) }
        }
    }

    /** The events a file makes, helpers and all. */
    private fun eventsOf(file: Path): List<Event> {
        val events = mutableListOf<Event>()
        collect(file, emptyMap(), emptyList(), events, setOf(file.normalize()))
        return events
    }

    /**
     * A flow written for this test, in a directory of its own.
     *
     * The seed helper beside it is the one the collector recognises — a file
     * named `seed.yaml` is a seed, not a helper to descend into — so the
     * fixture says what it seeds without restating the helper.
     */
    fun fixture(vararg files: Pair<String, String>): List<Event> {
        val directory = Files.createTempDirectory("seed-helper-wait")
        try {
            files.forEach { (name, text) -> directory.resolve(name).writeText(text) }
            directory.resolve(SEED_HELPER).writeText("appId: com.singularity.todo\nname: seed\n")
            return eventsOf(directory.resolve("flow.yaml"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * Reads the commands of a file and the commands of the helpers it runs.
     *
     * The prefix is the path of the `runFlow` that pulled this file in, or an
     * empty path for the flow itself.
     */
    private fun collect(
        file: Path,
        env: Map<String, String>,
        prefix: List<Int>,
        events: MutableList<Event>,
        seen: Set<Path>,
    ) {
        for ((index, command) in commands(file.readText()).withIndex()) {
            val at = prefix + index
            val target = command.file?.let { file.parent.resolve(it).normalize() }
            if (command.name == "runFlow" && target != null) {
                collectRunFlow(target, command, env, at, events, seen)
                continue
            }
            val ids = command.ids.map { substitute(it.value, env) to it.positive }
            val texts = command.texts.map { substitute(it.value, env) to it.positive }
            when (command.name) {
                in WAIT_COMMANDS -> {
                    events +=
                        Event.Wait(
                            at,
                            (ids.map { it.first } + texts.map { it.first }).toSet(),
                            ids.all { it.second } && texts.all { it.second },
                        )
                }

                in ASSERT_COMMANDS -> {
                    ids.forEach { events += Event.Assert(at, it.first, null) }
                    texts.forEach { events += Event.Assert(at, null, it.first) }
                }
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
        at: List<Int>,
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
            out += Command(name, parsed.ids, parsed.texts, parsed.env, file)
        }
        return out
    }

    /** What the body of a command says: the ids it addresses, its env, its file. */
    private data class Block(
        val ids: List<Selector>,
        val texts: List<Selector>,
        val env: Map<String, String>,
        val file: String?,
    )

    /** Reads the body of a command. */
    private fun parseBlock(block: List<String>): Block {
        var inEnv = false
        val ids = mutableListOf<Selector>()
        val texts = mutableListOf<Selector>()
        val env = mutableMapOf<String, String>()
        var file: String? = null
        // The selector the ids and texts under it belong to: `visible` and
        // `element` wait for the element to be there, `notVisible` and
        // `absent` wait for it to be gone.
        var selector = VISIBLE
        for (line in block) {
            val trimmed = line.trim()
            val fileMatch = FILE_KEY.matchEntire(line)
            val idMatch = ID_KEY.matchEntire(line)
            val textMatch = TEXT_KEY.matchEntire(line)
            val envMatch = ENV_KEY.matchEntire(line)
            val selectorMatch = SELECTOR_KEY.matchEntire(line)
            when {
                trimmed.startsWith("#") -> {
                    Unit
                }

                trimmed == "env:" -> {
                    inEnv = true
                }

                selectorMatch != null -> {
                    selector = selectorMatch.groupValues[1]
                }

                fileMatch != null && !inEnv -> {
                    file = fileMatch.groupValues[1]
                }

                idMatch != null -> {
                    ids += Selector(idMatch.groupValues[1], isPositive(selector))
                }

                textMatch != null -> {
                    texts += Selector(textMatch.groupValues[1], isPositive(selector))
                }

                inEnv && envMatch != null -> {
                    env[envMatch.groupValues[1]] = envMatch.groupValues[2]
                }

                // Another key ends the env block.
                KEY_ONLY.matchEntire(trimmed) != null -> {
                    inEnv = false
                    selector = VISIBLE
                }
            }
        }
        return Block(ids, texts, env, file)
    }

    /** Whether a selector waits for the element to be there. */
    private fun isPositive(selector: String): Boolean = selector == VISIBLE || selector == ELEMENT

    /** Replaces `${NAME}` with what the caller passed for it. */
    private fun substitute(
        value: String,
        env: Map<String, String>,
    ): String = ENV_REFERENCE.replace(value) { match -> env[match.groupValues[1]] ?: match.value }

    // ── What a seed writes ───────────────────────────────────────────────────

    /** The parameters of a seed query. */
    fun parseQuery(query: String): Map<String, String> =
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
     * The entity a seed query creates, and the ways a flow can name it.
     *
     * The row of a title-named entity is the app's own slug of that title, so
     * the ids are produced by `TestTags` and not by a second copy of the slug
     * rule living in this test.
     *
     * The container is the agenda section the entity is in. A section renders
     * because it holds a task, so waiting for it is waiting for the entity —
     * as long as it is the section the entity belongs to.
     *
     * The names are what the entity is called. A name is a handle of its own:
     * the renamed project is on screen under its new name, and a wait for that
     * name is a wait for the entity — the one wait that can tell a rename
     * from the row it renamed.
     *
     * A seed that writes several rows writes several *entities*: `count=3` is
     * three tasks, and each of them is a row a flow may assert on. They are
     * kept apart here, because one wait for one of them says nothing about
     * the others — the entities are what the rule holds a flow to.
     */
    fun seededEntity(params: Map<String, String>): Seeded {
        val entities = mutableListOf<Entity>()
        val containers = mutableListOf<String>()
        params["task"]?.let { title ->
            val count = params["count"]?.toIntOrNull() ?: 1
            if (count > 1) {
                (1..count).forEach { index ->
                    entities += Entity(listOf(TestTags.taskItem("$title-$index")), listOf("$title-$index"))
                }
            } else {
                entities += Entity(listOf(TestTags.taskItem(title)), listOf(title))
            }
            DUE_SECTIONS[params["due"]]?.let { section ->
                containers += TestTags.agendaSection(section)
            }
        }
        params["note"]?.let { title ->
            entities += Entity(listOf(TestTags.noteItemByTitle(title)), listOf(title))
        }
        params["project"]?.let { name ->
            val rows = mutableListOf(TestTags.projectCard(name))
            params["id"]?.let { id -> rows += TestTags.menuProjectNode(id) }
            entities += Entity(rows, listOf(name))
        }
        params["projects"]?.toIntOrNull()?.let { count ->
            (1..count).forEach { index ->
                val number = index.toString().padStart(3, '0')
                entities +=
                    Entity(
                        listOf(
                            TestTags.projectCard("Bulk $number"),
                            TestTags.menuProjectNode("$BULK_ID_PREFIX-$number"),
                        ),
                        listOf("Bulk $number"),
                    )
            }
        }
        params["profile"]?.let { name ->
            entities += Entity(listOf(TestTags.profileItem(name)), listOf(name))
        }
        return Seeded(entities, containers)
    }

    /**
     * What a seed writes: the entities it created, and the containers they are in.
     *
     * The containers are the seed's, not an entity's: a section that holds a
     * thousand overdue rows holds every one of them, and a wait for that
     * section is a wait for all of them.
     */
    data class Seeded(val entities: List<Entity>, val containers: List<String>)

    /** One entity the seed wrote: the rows it is rendered under, the names it is called. */
    data class Entity(val rows: List<String>, val names: List<String>)

    /**
     * The path of a file, relative to the worktree root.
     *
     * The root is the parent of `Maestro/`, so the path is the one a reader of
     * the repository would type.
     */
    fun rel(
        maestroRoot: Path,
        file: Path,
    ): String = maestroRoot.parent.relativize(file).toString()

    /** The helper that seeds. Its `env:` block answers for the caller. */
    const val SEED_HELPER = "seed.yaml"

    /** The prefix of the ids the bulk project seed writes, as `SeedWriter` writes them. */
    const val BULK_ID_PREFIX = "bulk"

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

    /** The selector a wait uses when it waits for an element to be there. */
    const val VISIBLE = "visible"

    /** The selector a scroll waits for. */
    const val ELEMENT = "element"

    /** The selector key a wait's target sits under. */
    val SELECTOR_KEY = Regex("^\\s*(visible|notVisible|element|absent):\\s*$")

    /** A top-level command: `- name: …`. */
    val COMMAND = Regex("""^- ([a-zA-Z][a-zA-Z0-9]*):\s*(.*)$""")

    /** The `id:` of a selector. */
    val ID_KEY = Regex("""^\s+id:\s*"?([^"\n#]*)"?\s*$""")

    /** The `text:` of a selector. */
    val TEXT_KEY = Regex("""^\s+text:\s*"?([^"\n]*)"?\s*$""")

    /** The `file:` of a `runFlow:` that carries its path in a mapping. */
    val FILE_KEY = Regex("""^\s+file:\s*([^\s:]+)\s*$""")

    /** A key of an `env:` block — the env keys are upper case. */
    val ENV_KEY = Regex("""^\s+([A-Z_][A-Z0-9_]*):\s*"?([^"\n]*)"?\s*$""")

    /** A key with no value — it ends the env block it is in. */
    val KEY_ONLY = Regex("""^[a-zA-Z_][a-zA-Z0-9_]*:\s*$""")

    /** An env reference: `${NAME}`. */
    val ENV_REFERENCE = Regex("""\$\{([^}]*)}""")
}

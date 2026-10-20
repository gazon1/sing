package com.singularity.todo.arch

import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.TestTagsCatalog
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.test.fakes.FakeClock
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.assertTrue

/**
 * Every `id:` selector in a Maestro flow must resolve to an entry in
 * [TestTagsCatalog]. This is the blocking JVM equivalent of the shell-script
 * check in `scripts/ci/validate-maestro-selectors.py`.
 *
 * The two assertions:
 * 1. Every `id:` in a flow is derivable from `TestTags.kt` (static const or
 *    dynamic function prefix).
 * 2. At least 40 ids are scanned — guards against a broken collector giving
 *    a vacuous green result.
 *
 * [calendarDay] ids (`calendar_day_YYYY_MM_DD`) and [profileItem] ids
 * (`profile_item_<slug>`) are accepted as known exceptions: neither function
 * routes through `slug()` as the terminal interpolation in its template literal,
 * so both are intentionally excluded from [TestTagsCatalog.dynamicFunctions].
 *
 * Run from repo root:
 * ```
 * ./gradlew :shared:jvmTest --tests "com.singularity.todo.arch.MaestroFlowTagsTest"
 * ```
 */
@Tag("fast")
class MaestroFlowTagsTest {

    /**
     * The tree holding the flows, passed in rather than derived.
     *
     * This used to walk up four levels from `commonMain.root`, which produced a path no
     * build file mentions. An undeclared path is an undeclared path: the dependency is
     * real, Gradle cannot see it, and editing a flow left `:shared:jvmTest` UP-TO-DATE —
     * so this gate re-reported its previous verdict. Measured 2026-10-04, by probe:
     * inject a valid command carrying an unknown `id:` into a flow, re-run the same
     * command, and the task reported UP-TO-DATE / BUILD SUCCESSFUL with the bad selector
     * still in the tree.
     *
     * Declaring the root fixes the staleness and makes the dependency legible to
     * `scripts/check-test-task-inputs.py`, which fails when a path-valued system property
     * points outside its module and no `inputs.dir` covers it. A derived path is invisible
     * to that gate by construction, so deriving it was the defect and not the style.
     */
    private val maestroRoot: Path = run {
        val root = System.getProperty("maestro.root")
            ?: error(
                "maestro.root system property is not set — see shared/build.gradle.kts. " +
                    "This test scans Maestro/flows, which is outside :shared, so the path " +
                    "has to be passed in and declared as a task input; deriving it made the " +
                    "gate silently stale.",
            )
        Paths.get(root)
    }

    /** The worktree root, for the few checks that need a path outside Maestro/ itself. */
    private val workspaceRoot: Path = maestroRoot.parent

    private val idPattern = Regex("""^\s+id:\s*"?([^"#\s]+)"?\s*$""", RegexOption.MULTILINE)

    /** Matches calendar_day_YYYY_MM_DD — calendarDay() expanded form, not in dynamicFunctions(). */
    private val calendarDayPattern = Regex("""^calendar_day_\d{4}(_\d{2})?(_\d{2})?$""")

    /**
     * `profileItem(name)` expands to `"profile_item_${slug(name)}"`.
     * The function does NOT end with `${slug(...)` in the template literal
     * (it reads `${PROFILE_ITEM_PREFIX}${slug(name)}`), so it is intentionally
     * excluded from [TestTagsCatalog.dynamicFunctions].  Accept the expanded
     * form directly.
     */
    private val profileItemPattern = Regex("""^profile_item_[a-z][a-z0-9_]*$""")

    /**
     * Walks [dir] (flows or helpers) and collects every `id:` value → flow path.
     * Template expressions (`${...}`) are skipped.
     */
    private fun collectIds(dir: Path): Map<String, String> {
        if (!dir.exists()) return emptyMap()
        val out = mutableMapOf<String, String>()
        Files.walk(dir).use { stream ->
            stream.filter { it.isRegularFile() && it.extension in listOf("yaml", "yml") }
                .forEach { file ->
                    val text = file.readText()
                    idPattern.findAll(text).forEach { match ->
                        val id = match.groupValues[1]
                        if (id.isNotBlank() && !id.startsWith("\${")) {
                            out.putIfAbsent(id, file.toString())
                        }
                    }
                }
        }
        return out
    }

    @Test
    fun `every id in a Maestro flow resolves to TestTagsCatalog`() {
        val flows = workspaceRoot.resolve("Maestro/flows")
        val helpers = workspaceRoot.resolve("Maestro/helpers")
        val ids = collectIds(flows) + collectIds(helpers)

        assertTrue(ids.size > 40, "scanned ${ids.size} ids — collector likely broken (expected > 40)")

        val staticValues = TestTagsCatalog.staticTags().map { it.second }.toSet()
        val dynamicPrefixes = TestTagsCatalog.dynamicFunctions()
            .mapNotNull { it.second.substringBefore("\${").takeIf(String::isNotBlank) }
            .toSet()

        val unknown = ids.keys.filter { id ->
            id !in staticValues &&
                dynamicPrefixes.none { prefix -> id.startsWith(prefix) } &&
                !id.matches(calendarDayPattern) &&
                !id.matches(profileItemPattern)
        }

        val failureMessage = if (unknown.isEmpty()) {
            ""
        } else {
            "Unknown Maestro flow ids (${unknown.size}/${ids.size}): " +
                unknown.take(5).joinToString(", ") { "$it (e.g. ${ids[it]})" } +
                if (unknown.size > 5) {
                    " ... and ${unknown.size - 5} more"
                } else {
                    ""
                } +
                ". Fix: add a const val to TestTags.kt, or remove the flow that needs it."
        }
        assertTrue(unknown.isEmpty(), failureMessage)
    }

    /**
     * Every relative `runFlow:` path must resolve to a file that exists.
     *
     * Maestro reports a wrong relative path as `Invalid File Path at …` and then
     * fails the flow for a reason that has nothing to do with the app — five
     * flows in `flows/tasks/` and `flows/agenda/` said `../helpers/…` where the
     * helpers directory is two levels up, not one. The id check above could not
     * see it: those flows parsed fine, they just pointed at nothing, and the
     * only symptom was a red run on a device.
     *
     * Paths are resolved against the *including* flow's own directory, which is
     * what Maestro does, so a flow nested one level deeper needs `../../`.
     */
    @Test
    fun `every relative runFlow path resolves to an existing file`() {
        val flowsRoot = workspaceRoot.resolve("Maestro/flows")
        val broken = mutableListOf<String>()
        var checked = 0

        Files.walk(flowsRoot).use { stream ->
            stream.filter { it.isRegularFile() && it.extension in listOf("yaml", "yml") }
                .forEach { file ->
                    val dir = file.parent
                    RUN_FLOW.findAll(file.readText()).forEach { match ->
                        val raw = match.groupValues[1].trim()
                        // Absolute paths and expressions cannot be resolved statically.
                        if (raw.startsWith("/") || raw.contains("\${")) return@forEach
                        checked++
                        val target = dir.resolve(raw).normalize()
                        if (!target.exists()) {
                            broken += "${file.fileName}: $raw"
                        }
                    }
                }
        }

        assertTrue(
            checked >= 20,
            "scanned only $checked runFlow references — collector likely broken (expected >= 20)",
        )
        assertTrue(
            broken.isEmpty(),
            "runFlow paths that resolve to nothing (${broken.size}/$checked):\n" +
                broken.joinToString("\n") { "  - $it" } +
                "\nPaths are relative to the flow's own directory. Helpers live in " +
                "Maestro/helpers/, which is ../../ from a flow in Maestro/flows/<group>/.",
        )
    }

    /**
     * Every top-level list item in a flow must name a real Maestro command.
     *
     * A flow file is a test that ships with no compiler, so a mistyped command
     * is not a build error — it is a red run on a device, months later, for a
     * reason unrelated to whatever the flow was testing. Two were found in one
     * session: `- longPress:` (the command is `longPressOn`) and
     * `- clearState:` (an argument of `launchApp`, not a command at all). Both
     * sat in `smoke`, the set the `maestro-smoke` CI job runs.
     *
     * Neither existing check here could see either: ids resolved, paths
     * resolved, and a flow that does not parse is still a file full of
     * perfectly valid `id:` selectors.
     */
    @Test
    fun `every flow command is a real Maestro command`() {
        val maestroRoot = workspaceRoot.resolve("Maestro")
        val unknown = mutableListOf<String>()
        var checked = 0

        Files.walk(maestroRoot).use { stream ->
            stream.filter { it.isRegularFile() && it.extension in listOf("yaml", "yml") }
                .forEach { file ->
                    COMMAND_ITEM.findAll(file.readText()).forEach { match ->
                        checked++
                        val command = match.groupValues[1]
                        if (command !in KNOWN_COMMANDS) {
                            val line = file.readText().substring(0, match.range.first)
                                .count { it == '\n' } + 1
                            unknown += "${maestroRoot.relativize(file)}:$line $command"
                        }
                    }
                }
        }

        assertTrue(
            checked >= 100,
            "scanned only $checked command items — the collector is likely broken " +
                "(expected >= 100 on the current tree)",
        )
        assertTrue(
            unknown.isEmpty(),
            "list items that are not Maestro commands (${unknown.size}/$checked):\n" +
                unknown.joinToString("\n") { "  - $it" } +
                "\n\nOnly top-level `- command:` items are matched; nested keys are arguments. " +
                "If the command is real but missing from KNOWN_COMMANDS, add it there.",
        )
    }

    // ── Dynamic ids ───────────────────────────────────────────────────────────

    /**
     * A dynamic tag generator whose input is a *text the flow supplies*.
     *
     * The prefix is read off the generator rather than written down a second
     * time: `TestTags.taskItem("probe")` is `task_item_probe`, so the prefix is
     * whatever comes before the slug. A generator that changes its template
     * changes this check with it, instead of leaving a second copy of the
     * naming scheme behind to drift.
     *
     * @property appSideInputs inputs the app itself supplies, which a flow may
     *   address without ever typing them — the default profile, for one.
     */
    private data class DynamicGenerator(
        val name: String,
        val appSideInputs: List<String> = emptyList(),
        val produce: (String) -> String,
    ) {
        /** The literal prefix of every id this generator produces. */
        val prefix: String = produce(PROBE).removeSuffix(PROBE)

        private companion object {
            /** A probe the slug rule leaves unchanged, so the prefix is what remains. */
            const val PROBE = "probe"
        }
    }

    /**
     * The texts a flow supplies, split by where they came from.
     *
     * @property typed what the flow types into a field
     * @property seeded what the flow seeds through the debug deep link
     * @property helpers what the helper files it `runFlow:`s supply
     */
    private data class FlowTexts(val typed: List<String>, val seeded: List<String>, val helpers: List<String>) {
        /** Every text the flow can put into the app. */
        val all: List<String> get() = typed + seeded + helpers
    }

    /**
     * Every dynamic id a flow addresses must be one the app can produce.
     *
     * The catalogue check above proves an id's *prefix* is a real generator. It
     * cannot see the other half: a generator that takes a title produces
     * `task_item_<slug of the title>`, so a flow that types "Buy milk" and
     * asserts `task_item_buy_milk2` addresses a row that can never exist — and
     * the flow fails on a device for a reason nobody can read off the YAML.
     *
     * The texts a flow supplies are:
     * - what it types (`inputText:`, in both the inline and the mapping form);
     * - what it seeds through the debug deep link (`todo-debug://seed?task=…`),
     *   including the `QUERY` env the seed helper takes;
     * - what the helper files it `runFlow:`s supply, transitively — a flow that
     *   seeds "Buy milk" through `helpers/seed-task.yaml` may address
     *   `task_item_buy_milk` without typing anything itself.
     *
     * The ids are produced by the app's own `TestTags` functions, so the slug
     * rule is the app's and not a second copy of it living in this test.
     */
    @Test
    fun `every dynamic id in a flow is one the app can produce`() {
        val flowsRoot = workspaceRoot.resolve("Maestro/flows")
        val violations = mutableListOf<String>()
        var checked = 0

        Files.walk(flowsRoot).use { stream ->
            stream.filter { it.isRegularFile() && it.extension in listOf("yaml", "yml") }
                .forEach { file ->
                    val text = file.readText()
                    val texts = textsFor(file)
                    idPattern.findAll(text).forEach { match ->
                        val id = match.groupValues[1].replace(RUN_ID, RUN_ID_PLACEHOLDER)
                        // An id built from an expression this test cannot resolve is
                        // not a verdict it is allowed to reach.
                        val generator = if (id.contains('$')) null else generatorFor(id)
                        if (generator != null) {
                            checked++
                            val candidates = texts.all + generator.appSideInputs
                            if (candidates.none { generator.produce(it) == id }) {
                                violations += violation(file, id, generator, texts)
                            }
                        }
                    }
                }
        }

        assertTrue(
            checked >= 100,
            "checked only $checked dynamic ids — the collector is likely broken " +
                "(expected >= 100 on the current tree)",
        )
        assertTrue(
            violations.isEmpty(),
            "dynamic ids the app cannot produce (${violations.size}/$checked):\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nThe app builds these ids from the text the flow types or seeds, " +
                "through TestTags.<generator>(text). An id outside that set addresses a " +
                "row that can never exist — the flow fails on a device for a reason " +
                "the YAML does not show.",
        )
    }

    /** The generator whose prefix the id carries — the longest one, so the specific tag wins. */
    private fun generatorFor(id: String): DynamicGenerator? =
        TEXT_DERIVED_GENERATORS
            .filter { id.startsWith(it.prefix) }
            .maxByOrNull { it.prefix.length }

    /**
     * The texts a flow can put into the app, with the run id replaced by a
     * placeholder so the two sides of the comparison line up.
     */
    private fun textsFor(flow: Path): FlowTexts {
        val helpers = helperFiles(flow)
        val own = textsIn(flow.readText())
        val helperTexts = helpers.flatMap { textsIn(it.readText()).all }
        return FlowTexts(
            typed = own.typed.map { it.replace(RUN_ID, RUN_ID_PLACEHOLDER) },
            seeded = own.seeded.map { it.replace(RUN_ID, RUN_ID_PLACEHOLDER) },
            helpers = helperTexts.map { it.replace(RUN_ID, RUN_ID_PLACEHOLDER) },
        )
    }

    /**
     * The texts a single flow file supplies.
     *
     * Three sources, all of them things the app will hold as a title:
     * - `inputText: "…"` and the mapping form `- inputText:` / `text: "…"`;
     * - the debug deep link `todo-debug://seed?task=…`, whose parameters carry
     *   URL-encoded titles;
     * - the `QUERY` env the seed helper takes, which carries the same query.
     */
    private fun textsIn(text: String): FlowTexts = FlowTexts(
        typed = inputTextsIn(text),
        seeded = seedTitlesIn(text),
        helpers = emptyList(),
    )

    /** The literals the file types into the app. */
    private fun inputTextsIn(text: String): List<String> {
        val lines = text.lines()
        val typed = mutableListOf<String>()
        for (index in lines.indices) {
            val match = INPUT_TEXT.find(lines[index])
            if (match != null) {
                val inline = match.groupValues[1].trim()
                val value = if (inline.isNotEmpty()) {
                    inline
                } else {
                    // `- inputText:` with the value on the lines below it.
                    val nested = lines.drop(index + 1)
                        .takeWhile { !it.trimStart().startsWith("- ") }
                        .firstOrNull { TEXT_KEY.containsMatchIn(it) }
                    nested?.let { TEXT_KEY.find(it)?.groupValues?.get(1) }.orEmpty()
                }
                if (value.isNotEmpty()) {
                    typed.add(unquote(value))
                }
            }
        }
        return typed
    }

    /** The titles the file seeds through the debug deep link. */
    private fun seedTitlesIn(text: String): List<String> {
        val titles = mutableListOf<String>()
        SEED_LINK.findAll(text).forEach { match ->
            // The link is `todo-debug://seed?<query>`; the query is what names
            // the title, and the scheme in front of it is not part of it.
            titles += queryTitles(match.groupValues[1].substringAfter('?', ""))
        }
        SEED_QUERY.findAll(text).forEach { match ->
            titles += queryTitles(match.groupValues[1])
        }
        return titles
    }

    /**
     * The titles a seed query names.
     *
     * The deep link seeds at most one kind of entity, and only these four
     * parameters carry a title — `due`, `desc` and `cleanup` do not.
     */
    private fun queryTitles(query: String): List<String> =
        query.split("&")
            .mapNotNull { pair ->
                val key = pair.substringBefore("=").trim()
                if (key !in SEED_TITLE_KEYS) return@mapNotNull null
                val raw = pair.substringAfter("=", "")
                if (raw.isEmpty()) null else decode(raw)
            }

    /**
     * URL-decodes a seeded title.
     *
     * A `%` that is not part of an escape makes the decoder throw, and a gate
     * that throws on a malformed flow is a gate that reports nothing, so the
     * value is left as it is when it cannot be decoded safely.
     */
    private fun decode(raw: String): String {
        if (!raw.contains('%')) return raw
        if (PERCENT_ESCAPE.findAll(raw).count() != raw.count { it == '%' }) return raw
        return URLDecoder.decode(raw, StandardCharsets.UTF_8)
    }

    /**
     * Strips the quotes and the trailing comment off a YAML scalar.
     *
     * The comment is only cut off an *unquoted* value: a quoted one may carry
     * a `#` of its own ("Buy #1 milk"), and a `#` inside a quoted scalar is
     * part of the text rather than the start of a comment.
     */
    private fun unquote(value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith('"')) {
            val end = trimmed.indexOf('"', 1)
            if (end > 0) return trimmed.substring(1, end)
        }
        if (trimmed.startsWith('\'')) {
            val end = trimmed.indexOf('\'', 1)
            if (end > 0) return trimmed.substring(1, end)
        }
        return trimmed.substringBefore(" #").trim()
    }

    /**
     * The helper files a flow pulls in, transitively.
     *
     * `helpers/seed-task.yaml` runs `helpers/seed.yaml` in turn, and it is
     * `seed.yaml` that carries the seeded title — so the closure has to
     * follow the chain rather than stop at the first hop.
     */
    private fun helperFiles(flow: Path): List<Path> {
        val found = mutableListOf<Path>()
        val seen = mutableSetOf<Path>()
        val queue = ArrayDeque<Path>()
        queue.addLast(flow)
        while (queue.isNotEmpty()) {
            val file = queue.removeFirst()
            val dir = file.parent
            if (dir != null) {
                for (raw in runFlowTargets(file.readText())) {
                    // A path the flow builds from a variable is not one this
                    // test can resolve.
                    val target = if (raw.startsWith("/") || raw.contains('$')) {
                        null
                    } else {
                        dir.resolve(raw).normalize()
                    }
                    if (target != null && target.isRegularFile() && seen.add(target)) {
                        // `add`, not `+=`: a Path is an Iterable<Path>, and `+=`
                        // would read as "concatenate the path's elements".
                        found.add(target)
                        queue.addLast(target)
                    }
                }
            }
        }
        return found
    }

    /** The files the flow runs, in both the shorthand and the `file:` form. */
    private fun runFlowTargets(text: String): List<String> =
        RUN_FLOW.findAll(text).map { it.groupValues[1] }.toList() +
            RUN_FLOW_FILE.findAll(text).map { it.groupValues[1] }.toList()

    /** The report line for one id the app cannot produce. */
    private fun violation(
        file: Path,
        id: String,
        generator: DynamicGenerator,
        texts: FlowTexts,
    ): String {
        val typed = texts.typed.take(REPORTED_TEXTS).joinToString(", ") { "\"$it\"" }
        val seeded = texts.seeded.take(REPORTED_TEXTS).joinToString(", ") { "\"$it\"" }
        val helperTexts = texts.helpers.take(REPORTED_TEXTS).joinToString(", ") { "\"$it\"" }
        val produced = (texts.all + generator.appSideInputs)
            .take(REPORTED_TEXTS)
            .joinToString(", ") { generator.produce(it) }
        return "${workspaceRoot.relativize(file)}\n" +
            "        id:                $id\n" +
            "        texts it typed:    $typed\n" +
            "        texts it seeded:   $seeded\n" +
            "        texts its helpers: $helperTexts\n" +
            "        the app produces:  $produced\n" +
            "        generator:         TestTags.${generator.name}(text)"
    }

    private companion object {
        val RUN_FLOW = Regex("""^\s*-?\s*runFlow:\s*(\S+)\s*$""", RegexOption.MULTILINE)

        /** The `file:` key of a `runFlow:` that carries its path in a mapping. */
        val RUN_FLOW_FILE = Regex("""^\s*file:\s*(\S+)\s*$""", RegexOption.MULTILINE)

        /** `- inputText: "…"` — the value may be empty when the mapping form is used. */
        val INPUT_TEXT = Regex("""^\s*-\s*inputText:\s*(.*)$""", RegexOption.MULTILINE)

        /** The `text:` key of the mapping form of `inputText:`. */
        val TEXT_KEY = Regex("""^\s*text:\s*(.+)$""", RegexOption.MULTILINE)

        /** The debug deep link, whose query carries the seeded title. */
        val SEED_LINK = Regex("""openLink:\s*"?([^"\n]*)"?""")

        /** The `QUERY` env the seed helper takes — the same query, one hop up. */
        val SEED_QUERY = Regex("""QUERY:\s*"?([^"\n]*)"?""")

        /** A `%` that starts a valid percent-escape. */
        val PERCENT_ESCAPE = Regex("""%[0-9A-Fa-f]{2}""")

        /** The query parameters that name a title rather than a date or a flag. */
        val SEED_TITLE_KEYS = setOf("task", "note", "project", "profile")

        /** The run id the runner stamps into the flows, as it appears in the YAML. */
        const val RUN_ID = "\${RUN_ID}"

        /** What the run id becomes in the comparison, so both sides line up. */
        const val RUN_ID_PLACEHOLDER = "runid"

        /** How many texts the failure message lists. */
        const val REPORTED_TEXTS = 5

        /**
         * The app creates this profile on first launch, so every flow can address
         * it by name even though no flow types the name.
         */
        val DEFAULT_PROFILE_NAME: String = Profile.createDefault(FakeClock()).name

        /**
         * The dynamic generators whose input is a text a flow supplies.
         *
         * Only these are checked. A generator whose input is an app-side
         * constant — `navTab("Today")`, `noteFilterChip("All")`,
         * `settingsThemeMode("Dark")` — takes a label the flow never types, so
         * there is no text in the flow to compare its id against; the catalogue
         * check above covers those by prefix.
         *
         * `noteItem(id)` is absent for the same reason from the other side: the
         * note's id is a generated ULID the flow never learns.
         */
        val TEXT_DERIVED_GENERATORS = listOf(
            DynamicGenerator("noteItemByTitle") { TestTags.noteItemByTitle(it) },
            DynamicGenerator("taskItem") { TestTags.taskItem(it) },
            DynamicGenerator("taskCheckbox") { TestTags.taskCheckbox(it) },
            DynamicGenerator("pomodoroTaskChip") { TestTags.pomodoroTaskChip(it) },
            DynamicGenerator("projectCard") { TestTags.projectCard(it) },
            DynamicGenerator("savedAgendaCard") { TestTags.savedAgendaCard(it) },
            DynamicGenerator("subtaskItem") { TestTags.subtaskItem(it) },
            DynamicGenerator("subtaskCheckbox") { TestTags.subtaskCheckbox(it) },
            DynamicGenerator("subtaskDelete") { TestTags.subtaskDelete(it) },
            DynamicGenerator("checklistItem") { TestTags.checklistItem("id", it) },
            DynamicGenerator("checklistItemCheckbox") { TestTags.checklistItemCheckbox("id", it) },
            DynamicGenerator("checklistItemDelete") { TestTags.checklistItemDelete("id", it) },
            DynamicGenerator(
                name = "profileItem",
                appSideInputs = listOf(DEFAULT_PROFILE_NAME),
                produce = { TestTags.profileItem(it) },
            ),
        )

        /**
         * A command is a key on a **top-level list item** — `- tapOn:`. Keys one
         * level deeper (`id:`, `visible:`, `timeout:`) are arguments, and
         * matching those instead is how a naive version of this check reports
         * 1200 violations and tells you nothing.
         */
        val COMMAND_ITEM = Regex("""^-\s+([a-zA-Z][a-zA-Z0-9]*)\s*:""", RegexOption.MULTILINE)

        /**
         * Maestro commands in use on this tree, plus the rest of the documented
         * set. A command missing from here is a command nobody has used yet,
         * which is the safe direction to be wrong in: adding one to a flow
         * without adding it here fails the build and asks the question.
         */
        val KNOWN_COMMANDS = setOf(
            "assertAbsent", "assertNotVisible", "assertVisible", "assertTrue", "back",
            "clearState",
            "copyTextFrom", "doubleTapOn", "eraseText", "exec", "extendedWaitUntil",
            "hideKeyboard",
            "inputRandomText", "inputText", "killApp", "launchApp", "longPressOn",
            "openLink", "openNotifications", "pasteText", "pressKey", "repeat",
            "runFlow", "runScript", "scroll", "scrollUntilVisible", "setAirplaneMode",
            "setOrientation", "startRecording", "stopApp", "stopRecording", "swipe",
            "takeScreenshot", "tapOn", "waitForAnimationToEnd", "waitForAnimations",
        )
    }
}

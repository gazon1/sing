package com.singularity.todo.arch

import com.singularity.todo.arch.SeedWaitTree.Event
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
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
 * to assert on — and waits for it before it returns. This test holds the
 * helper's own half of that contract: the helper keeps the wait, the wait is
 * optional and guarded, a seed that writes no entity names none, and a
 * `WAIT_FOR` names an entity the seed wrote.
 *
 * The rule over the flows themselves — every flow that asserts a seeded
 * entity waits for it first — is held by `SeededEntityWaitTest`.
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
    fun `the helper waits for the entity inside the conditional block`() {
        val helper = maestroRoot.resolve("helpers/seed.yaml").readText()

        val problem = guardedWaitProblem(helper)
        assertTrue(
            problem == null,
            "The seed helper's entity wait is not where the contract says it is: " +
                "$problem\n\nThe wait has to be inside the guarded runFlow: a wait " +
                "outside it runs for a seed that names no entity, and a seed that " +
                "writes no entity has no row to wait for.",
        )

        // The same check, on a helper whose wait is outside the guarded block:
        // the check has to be able to fail, or the check above proves nothing.
        val escaped =
            """
            env:
              $OPTIONAL_INPUT
            ---
            - runFlow:
                when:
                  $GUARD
                commands:
                  - assertNotVisible:
                      text: "Seed failed:.*"
            - extendedWaitUntil:
                visible:
                  $WAIT_TARGET_ID
                timeout: 8000
            """.trimIndent()
        assertTrue(
            guardedWaitProblem(escaped) != null,
            "The check does not notice a helper whose entity wait sits outside the " +
                "guarded runFlow. A check that cannot fail holds nothing.",
        )
    }

    @Test
    fun `the helper waits for the entity to be there, not to be gone`() {
        // A wait under `notVisible` waits for the element to be *gone* — the
        // opposite of an arrival. The helper would hold the flow until the
        // entity it seeded disappears, and every flow that seeds a row would
        // run out the timeout instead of waiting for the row.
        val disappearing =
            """
            env:
              $OPTIONAL_INPUT
            ---
            - runFlow:
                when:
                  $GUARD
                commands:
                  - extendedWaitUntil:
                      notVisible:
                        $WAIT_TARGET_ID
                      timeout: 8000
            """.trimIndent()
        assertTrue(
            guardedWaitProblem(disappearing) != null,
            "The check accepts a helper whose entity wait waits for the element " +
                "to be gone. Such a wait is the opposite of an arrival, and the " +
                "check has to be able to fail.",
        )
    }

    @Test
    fun `a seed that writes no entity names no entity to wait for`() {
        val violations = mutableListOf<String>()
        var checked = 0

        SeedWaitTree.forEachFile(maestroRoot) { file, events ->
            for (seed in events.filterIsInstance<Event.Seed>()) {
                val seeded = SeedEntityModel.seededEntity(SeedEntityModel.parseQuery(seed.query))
                checked++
                if (seeded.entities.isEmpty() && seed.waitFor != null) {
                    violations +=
                        "${SeedWaitTree.rel(maestroRoot, file)}: a seed that writes no entity names " +
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
    fun `a link that cleans up or resets writes no entity`() {
        // The seeder answers such a link and returns before it writes
        // anything, so a link that carries an entity param alongside it writes
        // that entity nowhere. The model that writes it would name a row the
        // device never shows.
        val queries =
            listOf(
                "reset=content",
                "cleanup=BackupRoundTripTask",
                "session=anonymous",
                "reset=content&task=Fixture&due=today",
            )
        for (query in queries) {
            val seeded = SeedEntityModel.seededEntity(SeedEntityModel.parseQuery(query))
            assertTrue(
                seeded.entities.isEmpty(),
                "the model writes ${seeded.entities.size} entities for \"$query\" — the " +
                    "seeder writes nothing for a link that cleans up, resets or changes " +
                    "the session",
            )
        }
    }

    @Test
    fun `a WAIT_FOR names an entity the seed wrote`() {
        val violations = mutableListOf<String>()
        var checked = 0

        SeedWaitTree.forEachFile(maestroRoot) { file, events ->
            val named =
                events.filterIsInstance<Event.Seed>().mapNotNull { seed ->
                    val waitFor = seed.waitFor ?: return@mapNotNull null
                    val seeded = SeedEntityModel.seededEntity(SeedEntityModel.parseQuery(seed.query))
                    if (seeded.entities.isEmpty()) {
                        null
                    } else {
                        Triple(seed, waitFor, seedTargets(seeded))
                    }
                }
            for ((seed, waitFor, targets) in named) {
                checked++
                if (waitFor !in targets) {
                    violations +=
                        "${SeedWaitTree.rel(maestroRoot, file)}: WAIT_FOR=$waitFor is not an element of the " +
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
    fun `a WAIT_FOR names an element, not a display name`() {
        // The helper waits for an *id*: `visible: {id: "${WAIT_TARGET}"}`. A
        // WAIT_FOR that carries the display name of an entity is an id the app
        // never renders, and the wait times out on the device.
        val seeded =
            SeedEntityModel.seededEntity(
                SeedEntityModel.parseQuery("task=Buy%20milk&due=today"),
            )
        val targets = seedTargets(seeded)

        assertTrue(
            targets.contains("task_item_buy_milk"),
            "the row the app renders for the title is not among the targets: ${targets.joinToString()}",
        )
        assertTrue(
            "Buy milk" !in targets,
            "the display name is among the targets a WAIT_FOR may name: ${targets.joinToString()}",
        )
    }

    /**
     * The elements a seed writes: the rows the app renders the entities under,
     * and the containers they are in.
     *
     * The helper waits for an *id* — `visible: {id: "${WAIT_TARGET}"}` — so the
     * display names of the entities are not among them: a `WAIT_FOR` that
     * carries a name is an id the app never renders.
     */
    private fun seedTargets(seeded: SeedEntityModel.Seeded): Set<String> =
        (seeded.entities.flatMap { entity -> entity.rows } + seeded.containers).toSet()

    @Test
    fun `a WAIT_FOR names an entity the seed brought to the screen it lands on`() {
        val violations = mutableListOf<String>()
        var checkedCount = 0

        SeedWaitTree.forEachFile(maestroRoot) { file, events ->
            val waits = events.filterIsInstance<Event.Wait>()
            val asserts = events.filterIsInstance<Event.Assert>()
            val seeds = events.filterIsInstance<Event.Seed>()
            val named =
                seeds.mapNotNull { seed ->
                    val waitFor = seed.waitFor ?: return@mapNotNull null
                    val seeded = SeedEntityModel.seededEntity(SeedEntityModel.parseQuery(seed.query))
                    if (seeded.entities.isEmpty()) {
                        null
                    } else {
                        Triple(seed, waitFor, seeded)
                    }
                }
            for ((seed, waitFor, seeded) in named) {
                checkedCount++
                val targets =
                    SeedWaitTree.landingTargets(
                        seeded,
                        seed.position,
                        waits,
                        asserts,
                        SeedWaitTree.containersBefore(seed, seeds),
                    )
                if (waitFor !in targets) {
                    violations +=
                        "${SeedWaitTree.rel(maestroRoot, file)}: WAIT_FOR=$waitFor is not on the screen the " +
                        "seed leaves the app on — the seed brings ${seedTargets(seeded).joinToString()} " +
                        "up, and the helper waits on the agenda's Today tab"
                }
            }
        }

        assertTrue(
            checkedCount >= 1,
            "scanned only $checkedCount WAIT_FORs — the collector is likely broken",
        )
        assertTrue(
            violations.isEmpty(),
            "WAIT_FORs that name an entity the seed does not bring on screen " +
                "(${violations.size}/$checkedCount):\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nThe seed leaves the app on the agenda's Today tab, and the " +
                "helper waits there without navigating. A note, a project, a " +
                "profile and a task in another tab are not on that screen — the " +
                "flow waits for those itself, after it navigates to them.",
        )

        // The check has to be able to fail: a note is on the Notes screen, and
        // the helper would wait out its timeout on an element the app never
        // renders on the tab it is left on.
        assertTrue(
            landingWaitProblem("note=Alpha", "note_item_by_title_alpha") != null,
            "The check does not notice a WAIT_FOR that names a note. A note is on " +
                "the Notes screen; the helper waits on the agenda's Today tab and " +
                "would run out its timeout there.",
        )

        // A task that is due today is on that screen: the helper may wait for it.
        assertTrue(
            landingWaitProblem("task=TodayTask&due=today", "task_item_todaytask") == null,
            "The check rejects a WAIT_FOR for a task that is due today. Such a task " +
                "is in the Today section of the tab the seed leaves the app on.",
        )
    }

    /**
     * What is wrong with a `WAIT_FOR` that names an element, if anything.
     *
     * The helper waits on the agenda's Today tab — the screen the seed leaves
     * the app on — so the element it names has to be rendered there.
     */
    private fun landingWaitProblem(
        query: String,
        waitFor: String,
    ): String? {
        val seeded = SeedEntityModel.seededEntity(SeedEntityModel.parseQuery(query))
        return if (waitFor in SeedWaitTree.landingTargets(seeded, SEED_POSITION, emptyList(), emptyList())) {
            null
        } else {
            "the seed \"$query\" does not bring $waitFor on screen"
        }
    }

    @Test
    fun `a row of a multi-row seed is not a target of the helper's wait`() {
        // The helper waits on the agenda's Today tab and does not scroll. A
        // seed that wrote five rows put the last of them below the fold of the
        // list it filled, so a wait for one of those rows would run out its
        // timeout on a row that is there but not on screen.
        val targets = landingTargetsOf(MULTI_ROW_SEED)

        assertTrue(
            "task_item_fixture_1" !in targets && "task_item_fixture_2" !in targets,
            "the rows of a multi-row seed are among the targets the helper may " +
                "wait for: ${targets.joinToString()}. The helper does not scroll, " +
                "so a row below the fold is not a thing it can wait for.",
        )
        assertTrue(
            "agenda_section_today" in targets,
            "the section the rows are in is not among the targets: ${targets.joinToString()}",
        )
    }

    @Test
    fun `a section the flow named before the seed is not a target of the helper's wait`() {
        // The section was on screen before the seed ran, so a wait for it
        // returns at once: it cannot be the evidence that the row the seed
        // wrote has arrived.
        val targets = landingTargetsOf(SECTION_NAMED_BEFORE)

        assertTrue(
            "agenda_section_today" !in targets,
            "a section the flow named before the seed is among the targets the " +
                "helper may wait for: ${targets.joinToString()}. That section was " +
                "there before the seed, so the wait would return at once.",
        )
        assertTrue(
            "task_item_fixture" in targets,
            "the row the seed wrote is not among the targets: ${targets.joinToString()}",
        )
    }

    @Test
    fun `an overdue row is not a target of the helper's wait`() {
        // The Today tab collapses the overdue section once the profile holds
        // enough overdue tasks (`AgendaPresets.LARGE_OVERDUE_THRESHOLD`), and
        // the helper neither expands the section nor scrolls to the row. A row
        // of an overdue task is therefore not a thing the helper can wait for.
        //
        // The section itself is a target: its header is rendered whether the
        // section is expanded or collapsed.
        val targets = landingTargetsOf(OVERDUE_SEED)

        assertTrue(
            "task_item_fixture" !in targets,
            "the row of an overdue task is among the targets the helper may wait " +
                "for: ${targets.joinToString()}. The overdue section is collapsed " +
                "once the profile holds enough overdue tasks, and the helper does " +
                "not expand it — the wait would run out its timeout on a row the " +
                "app does not render.",
        )
        assertTrue(
            "agenda_section_overdue" in targets,
            "the overdue section is not among the targets: ${targets.joinToString()}. " +
                "Its header is rendered whether the section is expanded or collapsed.",
        )
    }

    @Test
    fun `a task due days ago is overdue too`() {
        // The overdue section holds every task that was due in the past, not
        // only the one that was due yesterday.
        for (due in listOf("-1d", "-2d", "-30d")) {
            assertTrue(
                landingWaitProblem("task=Fixture&due=$due", "task_item_fixture") != null,
                "The check accepts a WAIT_FOR for the row of a task that was due " +
                    "$due. Such a task is overdue, and an overdue row is not a " +
                    "thing the helper can wait for.",
            )
            assertTrue(
                landingWaitProblem("task=Fixture&due=$due", "agenda_section_overdue") == null,
                "The check rejects a WAIT_FOR for the overdue section of a task " +
                    "that was due $due. The section is rendered whether it is " +
                    "expanded or collapsed.",
            )
        }
    }

    @Test
    fun `a count the seeder refuses writes no entity`() {
        // `SeedCount.parse` refuses a count outside 1..10000, and the seeder
        // then writes nothing at all. Such a seed creates no entity, so there
        // is no row for the helper to wait for.
        for (count in listOf("0", "10001", "-1", "abc")) {
            val seeded = SeedEntityModel.seededEntity(SeedEntityModel.parseQuery("task=Fixture&count=$count"))
            assertTrue(
                seeded.entities.isEmpty(),
                "the model writes ${seeded.entities.size} entities for count=$count — the " +
                    "seeder refuses that count and writes nothing",
            )
        }

        // The count the seeder accepts is the one the model writes: two rows.
        val accepted = SeedEntityModel.seededEntity(SeedEntityModel.parseQuery("task=Fixture&count=2"))
        assertTrue(
            accepted.entities.size == 2,
            "the model writes ${accepted.entities.size} entities for count=2 — the " +
                "seeder writes one row per count",
        )
    }

    @Test
    fun `a seed with a blank value writes no entity`() {
        // The seeder drops a parameter whose value is blank: `task=` is not a
        // task with an empty title, it is no task at all. A model that kept the
        // pair would say the link wrote a task, and the gate would hold a flow
        // to a wait for a row that was never written.
        for (blank in listOf("task=", "note=", "project=")) {
            val seeded = SeedEntityModel.seededEntity(SeedEntityModel.parseQuery("$blank&due=today"))
            assertTrue(
                seeded.entities.isEmpty(),
                "the model writes ${seeded.entities.size} entities for \"$blank\" — the seeder " +
                    "drops a blank value and writes nothing",
            )
        }
    }

    @Test
    fun `a seed with a key the seeder does not know writes no entity`() {
        // The seeder reads the key as the link spells it: ` task=Fixture` names
        // no parameter the seeder knows, so it writes nothing. A model that
        // trimmed the key away would say the link wrote a task — and the gate
        // would let a flow through that fails on the device, on a row that was
        // never written.
        val seeded = SeedEntityModel.seededEntity(SeedEntityModel.parseQuery(" task=Fixture&due=today"))

        assertTrue(
            seeded.entities.isEmpty(),
            "the model writes ${seeded.entities.size} entities for \" task=Fixture\" — the " +
                "seeder reads the key as \" task\", knows no such parameter, and writes nothing",
        )
    }

    @Test
    fun `a task due today is a target of the helper's wait`() {
        // The seeder reads every relative date but the negative one as
        // positive: `+0d` is today, and so is `0d`. A gate that does not know
        // that rejects a WAIT_FOR the helper can honour.
        for (due in listOf("today", "0d", "+0d")) {
            assertTrue(
                landingWaitProblem("task=Fixture&due=$due", "task_item_fixture") == null,
                "The check rejects a WAIT_FOR for a task that is due $due. Such a task " +
                    "is due today, and the helper waits on the agenda's Today tab.",
            )
        }
    }

    @Test
    fun `a section an earlier seed brought in is not a target of the helper's wait`() {
        // The first seed put a row in the section, so the section was on
        // screen before the second seed ran: a wait for it returns at once,
        // and it cannot be the evidence that the second seed's row arrived.
        val targets = landingTargetsOf(HELPER_SECTION_FROM_EARLIER_SEED, seedIndex = 1)

        assertTrue(
            "agenda_section_today" !in targets,
            "a section an earlier seed brought in is among the targets the helper " +
                "may wait for: ${targets.joinToString()}. That section was already on " +
                "screen when the seed ran, so the wait would return at once.",
        )
        assertTrue(
            "task_item_second" in targets,
            "the row the second seed wrote is not among the targets: ${targets.joinToString()}. " +
                "That row is the evidence the helper's wait can be.",
        )
    }

    /**
     * The targets the helper's wait may name for the seed in a flow.
     *
     * The flow is read the way the tree reads it, so the elements it named
     * before the seed are known to the model — and a section among them is
     * not a target. So are the sections the flow's own earlier seeds brought
     * in: they were on screen before this seed ran.
     */
    private fun landingTargetsOf(
        flow: String,
        seedIndex: Int = 0,
    ): Set<String> {
        val events = SeedWaitTree.fixture("flow.yaml" to flow)
        val waits = events.filterIsInstance<Event.Wait>()
        val asserts = events.filterIsInstance<Event.Assert>()
        val seeds = events.filterIsInstance<Event.Seed>()
        val seed = seeds[seedIndex]
        val seeded = SeedEntityModel.seededEntity(SeedEntityModel.parseQuery(seed.query))
        return SeedWaitTree.landingTargets(
            seeded,
            seed.position,
            waits,
            asserts,
            SeedWaitTree.containersBefore(seed, seeds),
        )
    }

    @Test
    fun `the helper waits for the entity after the seed it waits for`() {
        val helper = maestroRoot.resolve("helpers/seed.yaml").readText()

        val problem = helperOrderProblem(helper)
        assertTrue(
            problem == null,
            "The seed helper's commands are not in the order the contract says: " +
                "$problem\n\nThe order is the contract: the link opens the seed, the " +
                "shell wait lets the app come up, and the entity wait absorbs the " +
                "arrival of the row the seed wrote. A wait that runs *before* the " +
                "link is a wait for whatever was on screen already — the row the " +
                "seed is about to rewrite satisfies it, and the flow goes on to " +
                "assert on a row that has not been written yet.",
        )

        // The same check, on a helper whose entity wait runs before the link:
        // the check has to be able to fail, or the check above proves nothing.
        val early =
            """
            env:
              $OPTIONAL_INPUT
            ---
            - runFlow:
                when:
                  $GUARD
                commands:
                  - extendedWaitUntil:
                      visible:
                        $WAIT_TARGET_ID
                      timeout: 8000
            - openLink: "todo-debug://seed?${'$'}{QUERY}"
            """.trimIndent()
        assertTrue(
            helperOrderProblem(early) != null,
            "The check does not notice a helper whose entity wait runs before the " +
                "seed link. A check that cannot fail holds nothing.",
        )

        // And on a helper whose entity wait runs after the link but before the
        // shell wait: the shell wait is what closes the stale-row race, and an
        // entity wait above it waits for a row the seed may not have written.
        val beforeShell =
            """
            env:
              $OPTIONAL_INPUT
            ---
            - openLink: "todo-debug://seed?${'$'}{QUERY}"
            - runFlow:
                when:
                  $GUARD
                commands:
                  - extendedWaitUntil:
                      visible:
                        $WAIT_TARGET_ID
                      timeout: 8000
            - extendedWaitUntil:
                visible:
                  id: nav_tab_today
                timeout: 30000
            """.trimIndent()
        assertTrue(
            helperOrderProblem(beforeShell) != null,
            "The check does not notice a helper whose entity wait runs before the " +
                "shell wait. The shell wait lets the app come up; an entity wait " +
                "above it is a wait for a row that may not have been written yet.",
        )
    }

    /**
     * What is wrong with the order of the helper's commands, if anything.
     *
     * The order is three steps, and every one of them has to hold:
     *
     *   1. the link opens the seed;
     *   2. the shell wait lets the app come up;
     *   3. the entity wait absorbs the arrival of the row the seed wrote.
     *
     * The link has to come first: the entity wait is the wait for the row the
     * link writes, and a wait that runs before the link waits for a row that is
     * not there yet — or for the old row the link is about to replace.
     *
     * The shell wait has to come between the two: the app was already up
     * before the link arrived, so the shell wait returns at once, and the row
     * the seed wrote is still on its way. An entity wait that runs *before*
     * the shell wait is a wait for a row that may not have been written yet —
     * the stale-row race the shell wait exists to close.
     */
    private fun helperOrderProblem(text: String): String? {
        val lines = text.lines()
        val linkIndex = lines.indexOfFirst { it.trim().startsWith("- openLink:") }
        val waitIndex =
            lines.indices.firstOrNull { index ->
                lines[index].trim() == "- extendedWaitUntil:" &&
                    lines.getOrNull(index + 2)?.trim() == WAIT_TARGET_ID
            }
        // The shell wait is the other wait: the one that brings the app up,
        // before the entity wait. It is found by exclusion — the entity wait
        // is the only wait that targets $WAIT_TARGET — so the check does not
        // depend on how the shell wait is written.
        val shellIndex =
            lines.indices.firstOrNull { index ->
                index != waitIndex &&
                    index > linkIndex &&
                    lines[index].trim() == "- extendedWaitUntil:"
            }
        return when {
            linkIndex < 0 -> "the helper has no seed link"
            waitIndex == null -> "the helper has no entity wait"
            waitIndex < linkIndex -> "the entity wait runs before the seed link"
            shellIndex == null -> "the helper has no shell wait after the seed link"
            waitIndex < shellIndex -> "the entity wait runs before the shell wait"
            else -> null
        }
    }

    /**
     * What is wrong with the helper's entity wait, if anything.
     *
     * The wait has to be inside the guarded block: a wait outside it runs for
     * every seed, including the ones that write no entity and have no row to
     * wait for.
     *
     * And it has to wait for the element to *be there*: a wait under
     * `notVisible` waits for the element to be gone, which is the opposite of
     * an arrival — the helper would sit there until the entity it seeded
     * disappears, and a flow that asserted on that entity would fail on the
     * helper instead of on its own assertion.
     */
    private fun guardedWaitProblem(text: String): String? {
        val lines = text.lines()
        val guardIndex = lines.indexOfFirst { it.trim() == GUARD }
        val runFlowIndex =
            lines.indices.lastOrNull { it < guardIndex && lines[it].trim() == "- runFlow:" }
        val commandsIndex =
            lines.indices.firstOrNull { it > guardIndex && lines[it].trim() == "commands:" }
        val waitIndex =
            commandsIndex?.let { start ->
                lines.indices.firstOrNull { it > start && lines[it].trim() == "- extendedWaitUntil:" }
            }
        val targetIndex =
            waitIndex?.let { start ->
                lines.indices.firstOrNull { it > start && lines[it].trim() == WAIT_TARGET_ID }
            }
        val selectorIndex =
            waitIndex?.let { start ->
                lines.indices.firstOrNull { it > start && SELECTOR.matchEntire(lines[it].trim()) != null }
            }
        val escaped =
            commandsIndex != null &&
                targetIndex != null &&
                lines.subList(commandsIndex, targetIndex).any { COMMAND.matchEntire(it) != null }

        return when {
            guardIndex < 0 -> {
                "the entity wait is not guarded by \"$GUARD\""
            }

            runFlowIndex == null -> {
                "the guard is not inside a runFlow"
            }

            commandsIndex == null -> {
                "the guarded runFlow has no commands"
            }

            waitIndex == null -> {
                "the guarded runFlow waits for nothing"
            }

            targetIndex == null -> {
                "the wait does not target \$WAIT_TARGET"
            }

            selectorIndex == null -> {
                "the entity wait names no selector"
            }

            !POSITIVE_SELECTORS.contains(lines[selectorIndex].trim().substringBefore(":")) -> {
                "the entity wait waits for the element to be gone"
            }

            escaped -> {
                "the entity wait sits outside the guarded runFlow's commands"
            }

            else -> {
                null
            }
        }
    }

    /** The path of a file, relative to the worktree root. */
    private companion object {
        /** The helper's derivation of the caller's optional input. */
        const val OPTIONAL_INPUT =
            "WAIT_TARGET: \"\${typeof WAIT_FOR === 'undefined' ? '' : WAIT_FOR}\""

        /** The condition that skips the wait when no entity is named. */
        const val GUARD = "true: \"\${WAIT_TARGET !== ''}\""

        /** The element the helper waits for. */
        const val WAIT_TARGET_ID = "id: \"\${WAIT_TARGET}\""

        /** A top-level command: `- name: …`. */
        val COMMAND = Regex("""^- ([a-zA-Z][a-zA-Z0-9]*):\s*(.*)$""")

        /** The selector a wait names its target under. */
        val SELECTOR = Regex("""^(visible|notVisible|element|absent):$""")

        /** The selectors that wait for an element to be there. */
        val POSITIVE_SELECTORS = setOf("visible", "element")

        /**
         * The position of a seed that has no flow around it.
         *
         * A position is only compared with other positions, and a flow that
         * has one seed has nothing to compare it with — so any position works.
         */
        val SEED_POSITION = listOf(0)
    }
}

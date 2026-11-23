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
    fun `a seed that writes no entity names no entity to wait for`() {
        val violations = mutableListOf<String>()
        var checked = 0

        SeedWaitTree.forEachFile(maestroRoot) { file, events ->
            for (seed in events.filterIsInstance<Event.Seed>()) {
                val seeded = SeedWaitTree.seededEntity(SeedWaitTree.parseQuery(seed.query))
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
    fun `a WAIT_FOR names an entity the seed wrote`() {
        val violations = mutableListOf<String>()
        var checked = 0

        SeedWaitTree.forEachFile(maestroRoot) { file, events ->
            val named =
                events.filterIsInstance<Event.Seed>().mapNotNull { seed ->
                    val waitFor = seed.waitFor ?: return@mapNotNull null
                    val seeded = SeedWaitTree.seededEntity(SeedWaitTree.parseQuery(seed.query))
                    if (seeded.entities.isEmpty()) {
                        null
                    } else {
                        Triple(seed, waitFor, waitForTargets(seeded))
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
            SeedWaitTree.seededEntity(
                SeedWaitTree.parseQuery("task=Buy%20milk&due=today"),
            )
        val targets = waitForTargets(seeded)

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
     * The elements a `WAIT_FOR` may name for a seed.
     *
     * The helper waits for an id — `visible: {id: "${WAIT_TARGET}"}` — so the
     * display names of the entities are not among them: a `WAIT_FOR` that
     * carries a name is an id the app never renders.
     */
    private fun waitForTargets(seeded: SeedWaitTree.Seeded): Set<String> =
        (seeded.entities.flatMap { entity -> entity.rows } + seeded.containers).toSet()

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
    }

    /**
     * What is wrong with the order of the helper's commands, if anything.
     *
     * The link has to come first: the entity wait is the wait for the row the
     * link writes, and a wait that runs before the link waits for a row that is
     * not there yet — or for the old row the link is about to replace.
     */
    private fun helperOrderProblem(text: String): String? {
        val lines = text.lines()
        val linkIndex = lines.indexOfFirst { it.trim().startsWith("- openLink:") }
        val waitIndex =
            lines.indices.firstOrNull { index ->
                lines[index].trim() == "- extendedWaitUntil:" &&
                    lines.getOrNull(index + 2)?.trim() == WAIT_TARGET_ID
            }
        return when {
            linkIndex < 0 -> "the helper has no seed link"
            waitIndex == null -> "the helper has no entity wait"
            waitIndex < linkIndex -> "the entity wait runs before the seed link"
            else -> null
        }
    }

    /**
     * What is wrong with the helper's entity wait, if anything.
     *
     * The wait has to be inside the guarded block: a wait outside it runs for
     * every seed, including the ones that write no entity and have no row to
     * wait for.
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
        val escaped =
            commandsIndex != null &&
                targetIndex != null &&
                lines.subList(commandsIndex, targetIndex).any { COMMAND.matchEntire(it) != null }

        return when {
            guardIndex < 0 -> "the entity wait is not guarded by \"$GUARD\""
            runFlowIndex == null -> "the guard is not inside a runFlow"
            commandsIndex == null -> "the guarded runFlow has no commands"
            waitIndex == null -> "the guarded runFlow waits for nothing"
            targetIndex == null -> "the wait does not target \$WAIT_TARGET"
            escaped -> "the entity wait sits outside the guarded runFlow's commands"
            else -> null
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
    }
}

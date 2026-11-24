package com.singularity.todo.arch

import com.singularity.todo.arch.SeedWaitTree.Event
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * Every flow that asserts a seeded entity waits for it first.
 *
 * The rule the flows were written against before the helper grew the wait, and
 * what keeps a new flow from repeating the mistake: a bare `assertVisible` on
 * a seeded row is not a wait.
 *
 * The rule is about the *order* too. A wait that precedes the seed says
 * nothing about the entity: the row could have been on screen before the seed
 * wrote it. And an assertion belongs to the seed that wrote the entity: a wait
 * that covered an earlier seed of the same row does not cover the seed that
 * rewrote it.
 *
 * Run from repo root:
 * ```
 * ./gw :shared:archTest --tests "com.singularity.todo.arch.SeededEntityWaitTest"
 * ```
 */
@Tag("fast")
class SeededEntityWaitTest {
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
    fun `every flow that asserts a seeded entity waits for it first`() {
        val violations = mutableListOf<String>()
        var checked = 0
        var parsedAsserts = 0

        SeedWaitTree.forEachFile(maestroRoot) { file, events ->
            parsedAsserts += events.filterIsInstance<Event.Assert>().size
            val result = SeedWaitTree.waitViolations(SeedWaitTree.rel(maestroRoot, file), events)
            checked += result.checked
            violations += result.violations
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
                "YAML does not show. Wait for the row — or for the name the " +
                "entity is called, or for the container that renders only " +
                "because the entity is in it — after the seed that wrote it.",
        )
    }

    @Test
    fun `a wait that precedes the seed says nothing about the entity`() {
        val events =
            SeedWaitTree.fixture(
                "flow.yaml" to
                    """
                    appId: com.singularity.todo
                    name: fixture
                    ---
                    - extendedWaitUntil:
                        visible:
                          id: task_item_fixture
                        timeout: 8000
                    - runFlow:
                        file: seed.yaml
                        env:
                          QUERY: "task=Fixture&due=today"
                    - assertVisible:
                        id: task_item_fixture
                    """.trimIndent(),
            )

        val result = SeedWaitTree.waitViolations("fixture", events)
        assertTrue(
            result.violations.isNotEmpty(),
            "The rule accepts a wait that precedes the seed. The row could have " +
                "been on screen before the seed wrote it, so such a wait says " +
                "nothing about the entity the seed wrote.",
        )
    }

    @Test
    fun `a wait that covers only the first of two seeds says nothing about the second`() {
        // Two seeds of the same row, and a wait that covers only the first one:
        // the second seed rewrote the row, and nothing waited for that.
        val events = SeedWaitTree.fixture("flow.yaml" to ONE_SEED_OF_TWO_UNCOVERED)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule accepts an assertion that no wait of its own seed covers. " +
                "A wait that covered an earlier seed of the same row says nothing " +
                "about the seed that rewrote it.",
        )
    }

    @Test
    fun `a wait after every seed covers the assertion`() {
        val events = SeedWaitTree.fixture("flow.yaml" to ONE_SEED_OF_TWO_COVERED)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isEmpty(),
            "The rule reports a flow whose every seed is covered by a wait of its " +
                "own: ${SeedWaitTree.waitViolations("fixture", events).violations}",
        )
    }

    @Test
    fun `a wait by the name the entity is called covers the entity`() {
        // The name is on screen only because the entity is — and for a rename it
        // is the only handle that tells the rename from the row it renamed.
        val events = SeedWaitTree.fixture("flow.yaml" to WAITED_BY_NAME)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isEmpty(),
            "The rule rejects a wait by the name the entity is called: " +
                "${SeedWaitTree.waitViolations("fixture", events).violations}",
        )
    }

    @Test
    fun `a wait that runs before the seed inside a helper covers nothing`() {
        // The wait is in the helper, above the seed the helper runs. It can
        // return before the seed wrote the row, so it says nothing about the
        // entity — and the rule has to see that, not mistake the helper's
        // position for the position of the wait.
        val events =
            SeedWaitTree.fixture(
                "flow.yaml" to NESTED_WAIT_BEFORE_SEED,
                "wrapper.yaml" to NESTED_WAIT_BEFORE_SEED_HELPER,
            )

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule accepts a wait that runs before the seed inside a helper. " +
                "The wait can return before the seed writes the row, so it says " +
                "nothing about the entity.",
        )
    }

    @Test
    fun `a wait that runs after the assertion inside a helper covers nothing`() {
        val events =
            SeedWaitTree.fixture(
                "flow.yaml" to NESTED_WAIT_AFTER_ASSERT,
                "wrapper.yaml" to NESTED_WAIT_AFTER_ASSERT_HELPER,
            )

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule accepts a wait that runs after the assertion inside a " +
                "helper. A wait that comes after the assertion cannot cover it.",
        )
    }

    @Test
    fun `a wait that runs after the seed inside a helper covers the entity`() {
        val events =
            SeedWaitTree.fixture(
                "flow.yaml" to NESTED_WAIT_AFTER_SEED,
                "wrapper.yaml" to NESTED_WAIT_AFTER_SEED_HELPER,
            )

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isEmpty(),
            "The rule rejects a wait that runs after the seed inside a helper: " +
                "${SeedWaitTree.waitViolations("fixture", events).violations}",
        )
    }

    @Test
    fun `a wait that the element is gone does not cover the entity`() {
        // A wait under `notVisible` waits for the element to be gone. That is
        // the opposite of the arrival the assertion needs, so it covers nothing.
        val events = SeedWaitTree.fixture("flow.yaml" to WAITED_AWAY)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule accepts a wait that the element is gone. Such a wait says " +
                "the opposite of what the assertion needs.",
        )
    }

    @Test
    fun `an assertion by the name the entity is called is an assertion on the entity`() {
        val events = SeedWaitTree.fixture("flow.yaml" to ASSERTED_BY_NAME)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule ignores an assertion by the name the entity is called. " +
                "That assertion is about the seeded entity, so something has to " +
                "wait for it.",
        )
    }

    @Test
    fun `an assertion by the name the entity is called is covered by a wait for the row`() {
        val events = SeedWaitTree.fixture("flow.yaml" to ASSERTED_BY_NAME_WAITED)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isEmpty(),
            "The rule rejects an assertion by the name that a wait for the row " +
                "covers: ${SeedWaitTree.waitViolations("fixture", events).violations}",
        )
    }

    @Test
    fun `a wait for one of two entities of one seed says nothing about the other`() {
        // One seed, two rows: `count=2` writes "Fixture-1" and "Fixture-2".
        // A wait for the first row says nothing about the second one — the
        // second row can still be on its way when the flow asserts on it.
        val events = SeedWaitTree.fixture("flow.yaml" to ONE_SEED_TWO_ENTITIES_ONE_WAIT)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule accepts an assertion on the second row of a seed that " +
                "only the first row was waited for. One wait is one entity.",
        )
    }

    @Test
    fun `a wait for each of two entities of one seed covers both`() {
        val events = SeedWaitTree.fixture("flow.yaml" to ONE_SEED_TWO_ENTITIES_TWO_WAITS)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isEmpty(),
            "The rule reports a flow that waited for both rows of a seed: " +
                "${SeedWaitTree.waitViolations("fixture", events).violations}",
        )
    }

    @Test
    fun `a wait for the container covers every entity of the seed`() {
        // A section renders because the rows in it are there, so a wait for
        // the section is a wait for each of them.
        val events = SeedWaitTree.fixture("flow.yaml" to ONE_SEED_TWO_ENTITIES_CONTAINER)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isEmpty(),
            "The rule rejects a wait for the container the entities are in: " +
                "${SeedWaitTree.waitViolations("fixture", events).violations}",
        )
    }

    @Test
    fun `a container that was already there before the seed covers nothing`() {
        // The section was named before the seed, so it was already on screen
        // when the seed ran: a wait for it returns at once and cannot be the
        // evidence that the seeded row arrived.
        val events = SeedWaitTree.fixture("flow.yaml" to CONTAINER_ALREADY_THERE)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule accepts a wait for a section the flow had already named " +
                "before the seed. That section was there before the seed, so " +
                "the wait says nothing about the row the seed wrote.",
        )
    }

    @Test
    fun `the helper's wait belongs to the seed that asked for it`() {
        // The helper's wait runs inside the invocation of the seed that asked
        // for it, so it is evidence for that seed and for no other. A wait the
        // helper made for a later seed says nothing about the entity an
        // earlier seed wrote — and the row the earlier seed wrote is already
        // on screen, so the later wait returns at once and proves nothing
        // about the write the assertion is about.
        val events = SeedWaitTree.fixture("flow.yaml" to LATER_SEED_HELPER_WAIT)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule accepts a flow whose only wait is the helper's wait for a " +
                "later seed of a row an earlier seed already wrote. That wait " +
                "returns at once on the row that was already there, so it is no " +
                "evidence of the write the assertion is about.",
        )
    }

    @Test
    fun `the helper's wait covers the entity of the seed that asked for it`() {
        // The positive half of the rule above: a seed that asks the helper to
        // wait for the row it writes is covered by that wait.
        val events = SeedWaitTree.fixture("flow.yaml" to ONE_SEED_HELPER_WAIT)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isEmpty(),
            "The rule rejects a flow whose seed asks the helper to wait for the " +
                "row it writes. That wait is exactly the evidence the rule asks " +
                "for.",
        )
    }

    @Test
    fun `an assertion written as a scalar is an assertion on the entity`() {
        // `assertVisible: "Fixture"` is the short form of the block form. A
        // gate that reads only the block form does not see the assertion at
        // all, and the flow it belongs to passes the gate while asserting on
        // a row that may not have arrived.
        val events = SeedWaitTree.fixture("flow.yaml" to ASSERTED_BY_SCALAR_NAME)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule ignores an assertion written as a scalar. That assertion " +
                "is about the seeded entity, so something has to wait for it.",
        )
    }

    @Test
    fun `a link seeds one kind of entity`() {
        // The seeder takes the first kind the query names: a link that names a
        // task and a note seeds the task, and the note is never written. The
        // model that invents the note would hold the flow to a wait for a row
        // that does not exist.
        val seeded = SeedWaitTree.seededEntity(SeedWaitTree.parseQuery("task=Fixture&due=today&note=Alpha"))

        assertTrue(
            seeded.entities.size == 1,
            "the model writes ${seeded.entities.size} entities for a link that names a task " +
                "and a note — the seeder writes the task and nothing else",
        )
        assertTrue(
            seeded.entities.none { entity -> entity.names.contains("Alpha") },
            "the model writes a note the seeder never writes",
        )
        assertTrue(
            seeded.containers == listOf("agenda_section_today"),
            "the containers of the seed are ${seeded.containers.joinToString()} — the section " +
                "belongs to the task the seed wrote, and to nothing else",
        )
    }

    private companion object {
        /**
         * A flow that seeds the same row twice and waits only for the first seed.
         *
         * The second seed rewrote the row, and nothing waited for that.
         */
        val ONE_SEED_OF_TWO_UNCOVERED =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&due=today"
            - extendedWaitUntil:
                visible:
                  id: task_item_fixture
                timeout: 8000
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&due=today"
            - assertVisible:
                id: task_item_fixture
            """.trimIndent()

        /** The same flow with a wait after each seed: nothing to report. */
        val ONE_SEED_OF_TWO_COVERED =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&due=today"
            - extendedWaitUntil:
                visible:
                  id: task_item_fixture
                timeout: 8000
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&due=today"
            - extendedWaitUntil:
                visible:
                  id: task_item_fixture
                timeout: 8000
            - assertVisible:
                id: task_item_fixture
            """.trimIndent()

        /** A flow that waits for the entity by the name it is called. */
        val WAITED_BY_NAME =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "project=Renamed&id=roadmap"
            - extendedWaitUntil:
                visible:
                  text: "Renamed"
                timeout: 8000
            - assertVisible:
                id: menu_project_node_roadmap
            """.trimIndent()

        /**
         * A flow whose helper waits for the entity *before* it seeds it.
         *
         * The wait runs first, so it can return before the row is written.
         */
        val NESTED_WAIT_BEFORE_SEED =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - runFlow:
                file: wrapper.yaml
            - assertVisible:
                id: task_item_fixture
            """.trimIndent()

        /** The helper that waits before it seeds. */
        val NESTED_WAIT_BEFORE_SEED_HELPER =
            """
            - extendedWaitUntil:
                visible:
                  id: task_item_fixture
                timeout: 8000
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&due=today"
            """.trimIndent()

        /**
         * A flow that asserts on the entity before the helper that seeds and
         * waits for it.
         */
        val NESTED_WAIT_AFTER_ASSERT =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - assertVisible:
                id: task_item_fixture
            - runFlow:
                file: wrapper.yaml
            """.trimIndent()

        /** The helper that seeds and then waits — after the assertion. */
        val NESTED_WAIT_AFTER_ASSERT_HELPER =
            """
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&due=today"
            - extendedWaitUntil:
                visible:
                  id: task_item_fixture
                timeout: 8000
            """.trimIndent()

        /** A flow whose helper seeds the entity and then waits for it. */
        val NESTED_WAIT_AFTER_SEED =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - runFlow:
                file: wrapper.yaml
            - assertVisible:
                id: task_item_fixture
            """.trimIndent()

        /** The helper that seeds the entity and then waits for it. */
        val NESTED_WAIT_AFTER_SEED_HELPER =
            """
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&due=today"
            - extendedWaitUntil:
                visible:
                  id: task_item_fixture
                timeout: 8000
            """.trimIndent()

        /**
         * A flow that waits for the row to be *gone*.
         *
         * The wait says the row is not there, which is the opposite of the
         * arrival the assertion needs.
         */
        val WAITED_AWAY =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&due=today"
            - extendedWaitUntil:
                notVisible:
                  id: task_item_fixture
                timeout: 8000
            - assertVisible:
                id: task_item_fixture
            """.trimIndent()

        /** A flow that asserts on the entity by the name it is called. */
        val ASSERTED_BY_NAME =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "project=Renamed&id=roadmap"
            - assertVisible:
                text: "Renamed"
            """.trimIndent()

        /**
         * The same assertion, written in the short form.
         *
         * The text is the value of the command itself, and a gate that reads
         * only the block form does not see it.
         */
        val ASSERTED_BY_SCALAR_NAME =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&due=today"
            - assertVisible: "Fixture"
            """.trimIndent()

        /**
         * One seed that writes two rows, one wait, and an assertion on both.
         *
         * The wait is for the first row, so the second one is not covered.
         */
        val ONE_SEED_TWO_ENTITIES_ONE_WAIT =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&count=2"
            - extendedWaitUntil:
                visible:
                  id: task_item_fixture_1
                timeout: 8000
            - assertVisible:
                id: task_item_fixture_1
            - assertVisible:
                id: task_item_fixture_2
            """.trimIndent()

        /** The same seed, with a wait for each of the two rows. */
        val ONE_SEED_TWO_ENTITIES_TWO_WAITS =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&count=2"
            - extendedWaitUntil:
                visible:
                  id: task_item_fixture_1
                timeout: 8000
            - extendedWaitUntil:
                visible:
                  id: task_item_fixture_2
                timeout: 8000
            - assertVisible:
                id: task_item_fixture_1
            - assertVisible:
                id: task_item_fixture_2
            """.trimIndent()

        /** The same seed, with a wait for the section the two rows are in. */
        val ONE_SEED_TWO_ENTITIES_CONTAINER =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&count=2&due=today"
            - extendedWaitUntil:
                visible:
                  id: agenda_section_today
                timeout: 8000
            - assertVisible:
                id: task_item_fixture_1
            - assertVisible:
                id: task_item_fixture_2
            """.trimIndent()

        /**
         * The same flow, but the section was named before the seed.
         *
         * The section was already on screen when the seed ran, so the wait for
         * it returns at once — it is not the evidence that the seeded row
         * arrived.
         */
        val CONTAINER_ALREADY_THERE =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - extendedWaitUntil:
                visible:
                  id: agenda_section_today
                timeout: 8000
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&count=2&due=today"
            - extendedWaitUntil:
                visible:
                  id: agenda_section_today
                timeout: 8000
            - assertVisible:
                id: task_item_fixture_1
            - assertVisible:
                id: task_item_fixture_2
            """.trimIndent()

        /** The same flow, with a wait for the row before the assertion by name. */
        val ASSERTED_BY_NAME_WAITED =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "project=Renamed&id=roadmap"
            - extendedWaitUntil:
                visible:
                  id: menu_project_node_roadmap
                timeout: 8000
            - assertVisible:
                text: "Renamed"
            """.trimIndent()

        /**
         * Two seeds of the same row, and only the second one asks the helper
         * to wait for it.
         *
         * The helper's wait belongs to the seed that asked for it. The wait of
         * the second seed cannot be the evidence that the second seed's write
         * arrived: the row the first seed wrote is already on screen.
         */
        val LATER_SEED_HELPER_WAIT =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&due=today"
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&due=today"
                  WAIT_FOR: "task_item_fixture"
            - assertVisible:
                id: task_item_fixture
            """.trimIndent()

        /** One seed, and the helper waits for the row it writes. */
        val ONE_SEED_HELPER_WAIT =
            """
            appId: com.singularity.todo
            name: fixture
            ---
            - runFlow:
                file: seed.yaml
                env:
                  QUERY: "task=Fixture&due=today"
                  WAIT_FOR: "task_item_fixture"
            - assertVisible:
                id: task_item_fixture
            """.trimIndent()
    }
}

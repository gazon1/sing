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
    fun `a section an earlier seed brought in covers nothing`() {
        // The first seed put a row in the section, so the section was already
        // on screen when the second seed ran: a wait for it returns at once
        // and cannot be the evidence that the second seed's row arrived.
        val events = SeedWaitTree.fixture("flow.yaml" to SECTION_FROM_EARLIER_SEED)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule accepts a wait for a section an earlier seed of the same " +
                "flow brought in. That section was on screen before the seed ran, " +
                "so the wait says nothing about the row the seed wrote.",
        )
    }

    @Test
    fun `the helper's wait for a section an earlier seed brought in covers nothing`() {
        // The same, with the wait made by the helper: the helper waits for the
        // section the first seed brought in, so its wait returns at once and
        // proves nothing about the second seed's write.
        val events = SeedWaitTree.fixture("flow.yaml" to SECTION_FROM_EARLIER_SEED_HELPER)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule accepts a helper wait for a section an earlier seed of the " +
                "same flow brought in. That section was on screen before the seed " +
                "ran, so the wait says nothing about the row the seed wrote.",
        )
    }

    @Test
    fun `the helper's wait for the row of the second seed covers it`() {
        // The positive half of the rule above: the row of the second seed is
        // fresh, and the helper's wait for it is the evidence the rule asks
        // for.
        val events = SeedWaitTree.fixture("flow.yaml" to SECOND_SEED_HELPER_WAIT)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isEmpty(),
            "The rule rejects a flow whose second seed asks the helper to wait " +
                "for the row it writes: " +
                "${SeedWaitTree.waitViolations("fixture", events).violations}",
        )
    }

    @Test
    fun `a wait for a row an earlier seed wrote does not cover the rewrite`() {
        // The second seed rewrote the row the first one wrote. A wait for that
        // row returns at once — the row is already on screen — so it says
        // nothing about the write the assertion is about.
        val events = SeedWaitTree.fixture("flow.yaml" to RESEEDED_ROW_WAITED_AGAIN)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule accepts a wait for a row an earlier seed of the same flow " +
                "wrote. That row is on screen before the second seed runs, so the " +
                "wait returns at once and is no evidence of the rewrite.",
        )
    }

    @Test
    fun `a wait for the new name of a renamed entity covers the rewrite`() {
        // The positive half of the rule above: the seed gave the entity a name
        // it did not have, and the name is the one thing on screen that tells
        // the rename from the row it renamed.
        val events = SeedWaitTree.fixture("flow.yaml" to RENAMED_ENTITY_WAITED_BY_NEW_NAME)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isEmpty(),
            "The rule rejects a wait for the name a renamed entity is called: " +
                "${SeedWaitTree.waitViolations("fixture", events).violations}",
        )
    }

    @Test
    fun `a seed in a conditional block is held to the rule`() {
        // The commands of a `runFlow` that carries its own run only when the
        // condition holds. The gate has to see them: a seed in such a block is
        // a seed, and the assertion next to it is an assertion.
        val events = SeedWaitTree.fixture("flow.yaml" to CONDITIONAL_SEED_UNCOVERED)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule ignores a seed inside a conditional block. The assertion " +
                "next to it is about the seeded entity, so something has to wait " +
                "for it.",
        )
    }

    @Test
    fun `a wait in a conditional block covers the assertion next to it`() {
        val events = SeedWaitTree.fixture("flow.yaml" to CONDITIONAL_SEED_COVERED)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isEmpty(),
            "The rule rejects a conditional block whose seed waited for its " +
                "entity: ${SeedWaitTree.waitViolations("fixture", events).violations}",
        )
    }

    @Test
    fun `a seed in a conditional block is not held to an assertion outside it`() {
        // The block runs only when its condition holds, so the seed may never
        // have run — and an assertion outside the block is not about it.
        val events = SeedWaitTree.fixture("flow.yaml" to CONDITIONAL_SEED_OUTSIDE_ASSERT)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isEmpty(),
            "The rule holds an assertion outside a conditional block to a seed " +
                "inside it: ${SeedWaitTree.waitViolations("fixture", events).violations}",
        )
    }

    @Test
    fun `the helper's wait for one entity does not cover another entity of the same seed`() {
        // One link, two entities: the seeder switches to the profile the link
        // names and writes the task it names. The helper waits for the row of
        // the task — and that wait says nothing about the profile, which is on
        // another screen and was there before the wait ran.
        val events = SeedWaitTree.fixture("flow.yaml" to PROFILE_AND_TASK_HELPER_WAIT)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule accepts a helper wait for the row of a task as the evidence " +
                "for an assertion on the profile the same link named. The wait is " +
                "evidence for the entity it names and for no other.",
        )
    }

    @Test
    fun `a wait for the profile covers the profile of the same seed`() {
        // The positive half of the rule above: the flow waits for the profile
        // itself, and that wait is the evidence the rule asks for.
        val events = SeedWaitTree.fixture("flow.yaml" to PROFILE_AND_TASK_WAITED)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isEmpty(),
            "The rule rejects a flow that waits for the profile a seed named: " +
                "${SeedWaitTree.waitViolations("fixture", events).violations}",
        )
    }

    @Test
    fun `a seed in a helper called from a conditional block is not held to an assertion outside it`() {
        // The seed is two files deep: the flow runs a block that runs a helper
        // that seeds. The block runs only when its condition holds, so the
        // seed may never have run — and an assertion outside the block is not
        // about it. The scope of the block has to reach the seed through the
        // helper that runs it.
        val events =
            SeedWaitTree.fixture(
                "flow.yaml" to CONDITIONAL_HELPER_SEED_OUTSIDE_ASSERT,
                "wrapper.yaml" to CONDITIONAL_HELPER_SEED,
            )

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isEmpty(),
            "The rule holds an assertion outside a conditional block to a seed " +
                "that runs inside it, through a helper: " +
                "${SeedWaitTree.waitViolations("fixture", events).violations}",
        )
    }

    @Test
    fun `a seed in a helper called from a conditional block is held to the rule inside the block`() {
        // The other half: the scope reaches the seed, so the assertion next to
        // it — inside the block, in the helper — is still an assertion about
        // the seeded entity, and something has to wait for it.
        val events =
            SeedWaitTree.fixture(
                "flow.yaml" to CONDITIONAL_HELPER_SEED_BLOCK,
                "wrapper.yaml" to CONDITIONAL_HELPER_SEED_ASSERTED,
            )

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule ignores a seed that runs inside a conditional block through " +
                "a helper: the assertion next to it is about the seeded entity, " +
                "so something has to wait for it.",
        )
    }

    @Test
    fun `a seed in a conditional block is held to an assertion in a block nested inside it`() {
        // The blocks are a hierarchy, not a list. The assertion sits in a block
        // nested in the block the seed is in: where the inner condition holds,
        // the outer one held too, so the seed did run — and the assertion is
        // about the entity it wrote.
        val events = SeedWaitTree.fixture("flow.yaml" to NESTED_BLOCK_SEED_AND_ASSERT)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule ignores a seed whose block holds the assertion in a block " +
                "nested inside it. That assertion is about the seeded entity, so " +
                "something has to wait for it.",
        )
    }

    @Test
    fun `a seed in a block nested in a conditional block is not held to an assertion in the outer block`() {
        // The other way round: the seed is in the inner block, and the inner
        // block runs only when its own condition holds — so the seed may never
        // have run, and an assertion in the outer block is not about it.
        val events = SeedWaitTree.fixture("flow.yaml" to NESTED_BLOCK_SEED_OUTER_ASSERT)

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isEmpty(),
            "The rule holds an assertion in an outer block to a seed in a block " +
                "nested inside it: " +
                "${SeedWaitTree.waitViolations("fixture", events).violations}",
        )
    }

    @Test
    fun `a seed in a file a conditional block runs is not held to an assertion outside it`() {
        // The condition is on the `runFlow` that pulls the file in, so the file
        // belongs to the block: the seed in it may never have run, and an
        // assertion outside the block is not about it.
        val events =
            SeedWaitTree.fixture(
                "flow.yaml" to CONDITIONAL_FILE_SEED_OUTSIDE_ASSERT,
                "wrapper.yaml" to CONDITIONAL_FILE_SEED,
            )

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isEmpty(),
            "The rule holds an assertion outside a conditional block to a seed " +
                "in the file the block runs: " +
                "${SeedWaitTree.waitViolations("fixture", events).violations}",
        )
    }

    @Test
    fun `a seed in a file a conditional block runs is held to the rule inside the block`() {
        // The other half: the scope reaches the seed through the file, so the
        // assertion next to it — inside the block, in the file — is still an
        // assertion about the seeded entity.
        val events =
            SeedWaitTree.fixture(
                "flow.yaml" to CONDITIONAL_FILE_SEED_BLOCK,
                "wrapper.yaml" to CONDITIONAL_FILE_SEED_ASSERTED,
            )

        assertTrue(
            SeedWaitTree.waitViolations("fixture", events).violations.isNotEmpty(),
            "The rule ignores a seed that runs inside a conditional block through " +
                "the file it runs: the assertion next to it is about the seeded " +
                "entity, so something has to wait for it.",
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
        val seeded = SeedEntityModel.seededEntity(SeedEntityModel.parseQuery("task=Fixture&due=today&note=Alpha"))

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
}

package com.singularity.todo.arch

// The flow fixtures `SeededEntityWaitTest` reads.
//
// They are fixtures of their own file because they are data, not tests: a
// class that holds its own flows and the rules about them is a class the size
// rule rightly refuses, and the flows read here are read by the test that
// names them.

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

/**
 * Two seeds in one section, and a wait for the section.
 *
 * The first seed brought the section in, so the wait for it returns at
 * once — it says nothing about the second seed's row.
 */
val SECTION_FROM_EARLIER_SEED =
    """
    appId: com.singularity.todo
    name: fixture
    ---
    - runFlow:
        file: seed.yaml
        env:
          QUERY: "task=First&due=today"
    - runFlow:
        file: seed.yaml
        env:
          QUERY: "task=Second&due=today"
    - extendedWaitUntil:
        visible:
          id: agenda_section_today
        timeout: 8000
    - assertVisible:
        id: task_item_second
    """.trimIndent()

/** The same flow, with the section named by the helper's own wait. */
val SECTION_FROM_EARLIER_SEED_HELPER =
    """
    appId: com.singularity.todo
    name: fixture
    ---
    - runFlow:
        file: seed.yaml
        env:
          QUERY: "task=First&due=today"
    - runFlow:
        file: seed.yaml
        env:
          QUERY: "task=Second&due=today"
          WAIT_FOR: "agenda_section_today"
    - assertVisible:
        id: task_item_second
    """.trimIndent()

/**
 * Two seeds of the same row, the second one rewriting it, and a wait
 * for that row after each seed.
 *
 * The row is on screen from the first seed on, so neither wait is the
 * evidence that the second seed's write arrived.
 */
val RESEEDED_ROW_WAITED_AGAIN =
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
          QUERY: "task=Fixture&due=tomorrow"
    - extendedWaitUntil:
        visible:
          id: task_item_fixture
        timeout: 8000
    - assertVisible:
        id: task_item_fixture
    """.trimIndent()

/**
 * A project that is renamed by a second seed of the same id.
 *
 * The row is the same row, but the name is new — and the name is what
 * tells the rename from the row it renamed.
 */
val RENAMED_ENTITY_WAITED_BY_NEW_NAME =
    """
    appId: com.singularity.todo
    name: fixture
    ---
    - runFlow:
        file: seed.yaml
        env:
          QUERY: "project=Roadmap&id=roadmap"
    - extendedWaitUntil:
        visible:
          id: menu_project_node_roadmap
        timeout: 8000
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
    - assertVisible:
        text: "Renamed"
    """.trimIndent()

/** A seed in a conditional block, and an assertion nothing waited for. */
val CONDITIONAL_SEED_UNCOVERED =
    """
    appId: com.singularity.todo
    name: fixture
    ---
    - runFlow:
        when:
          visible:
            id: nav_tab_today
        commands:
          - runFlow:
              file: seed.yaml
              env:
                QUERY: "task=Fixture&due=today"
          - assertVisible:
              id: task_item_fixture
    """.trimIndent()

/** The same block, with a wait for the entity the seed wrote. */
val CONDITIONAL_SEED_COVERED =
    """
    appId: com.singularity.todo
    name: fixture
    ---
    - runFlow:
        when:
          visible:
            id: nav_tab_today
        commands:
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

/** A seed in a conditional block, and an assertion outside the block. */
val CONDITIONAL_SEED_OUTSIDE_ASSERT =
    """
    appId: com.singularity.todo
    name: fixture
    ---
    - runFlow:
        when:
          visible:
            id: nav_tab_today
        commands:
          - runFlow:
              file: seed.yaml
              env:
                QUERY: "task=Fixture&due=today"
    - assertVisible:
        id: task_item_fixture
    """.trimIndent()

/** The same flow, with the helper waiting for the row the seed writes. */
val SECOND_SEED_HELPER_WAIT =
    """
    appId: com.singularity.todo
    name: fixture
    ---
    - runFlow:
        file: seed.yaml
        env:
          QUERY: "task=First&due=today"
    - runFlow:
        file: seed.yaml
        env:
          QUERY: "task=Second&due=today"
          WAIT_FOR: "task_item_second"
    - assertVisible:
        id: task_item_second
    """.trimIndent()

/**
 * One link that names a profile and a task, and a helper wait for the
 * row of the task.
 *
 * The seeder switches to the profile the link names and writes the
 * task it names — two entities, and the wait names one of them.
 */
val PROFILE_AND_TASK_HELPER_WAIT =
    """
    appId: com.singularity.todo
    name: fixture
    ---
    - runFlow:
        file: seed.yaml
        env:
          QUERY: "profile=Personal&task=Fixture&due=today"
          WAIT_FOR: "task_item_fixture"
    - assertVisible:
        id: profile_item_personal
    """.trimIndent()

/** The same flow, with a wait for the profile itself. */
val PROFILE_AND_TASK_WAITED =
    """
    appId: com.singularity.todo
    name: fixture
    ---
    - runFlow:
        file: seed.yaml
        env:
          QUERY: "profile=Personal&task=Fixture&due=today"
          WAIT_FOR: "task_item_fixture"
    - extendedWaitUntil:
        visible:
          id: profile_item_personal
        timeout: 8000
    - assertVisible:
        id: profile_item_personal
    """.trimIndent()

/**
 * A flow whose conditional block runs a helper that seeds.
 *
 * The assertion is outside the block: the seed may never have run, so
 * the assertion is not about it.
 */
val CONDITIONAL_HELPER_SEED_OUTSIDE_ASSERT =
    """
    appId: com.singularity.todo
    name: fixture
    ---
    - runFlow:
        when:
          visible:
            id: nav_tab_today
        commands:
          - runFlow:
              file: wrapper.yaml
    - assertVisible:
        id: task_item_fixture
    """.trimIndent()

/** The same flow, without the assertion outside the block. */
val CONDITIONAL_HELPER_SEED_BLOCK =
    """
    appId: com.singularity.todo
    name: fixture
    ---
    - runFlow:
        when:
          visible:
            id: nav_tab_today
        commands:
          - runFlow:
              file: wrapper.yaml
    """.trimIndent()

/** The helper the conditional block runs: it seeds. */
val CONDITIONAL_HELPER_SEED =
    """
    appId: com.singularity.todo
    name: wrapper
    ---
    - runFlow:
        file: seed.yaml
        env:
          QUERY: "task=Fixture&due=today"
    """.trimIndent()

/** The same helper, with an assertion on the entity it seeded. */
val CONDITIONAL_HELPER_SEED_ASSERTED =
    """
    appId: com.singularity.todo
    name: wrapper
    ---
    - runFlow:
        file: seed.yaml
        env:
          QUERY: "task=Fixture&due=today"
    - assertVisible:
        id: task_item_fixture
    """.trimIndent()

package com.singularity.todo.arch

// The flow fixtures `SeedHelperWaitTest` reads.
//
// They are fixtures of their own file because they are data, not tests: a
// class that holds its own flows and the rules about them is a class the size
// rule rightly refuses, and the flows read here are read by the test that
// names them.

/** A flow whose seed writes two rows and names one of them to wait for. */
val MULTI_ROW_SEED =
    """
    appId: com.singularity.todo
    name: fixture
    ---
    - runFlow:
        file: seed.yaml
        env:
          QUERY: "task=Fixture&count=2&due=today"
          WAIT_FOR: "task_item_fixture_1"
    """.trimIndent()

/**
 * A flow that waits for the section before the seed, and then names
 * that section to wait for.
 */
val SECTION_NAMED_BEFORE =
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
          QUERY: "task=Fixture&due=today"
          WAIT_FOR: "agenda_section_today"
    """.trimIndent()

/** A flow whose seed writes an overdue task and names its row to wait for. */
val OVERDUE_SEED =
    """
    appId: com.singularity.todo
    name: fixture
    ---
    - runFlow:
        file: seed.yaml
        env:
          QUERY: "task=Fixture&due=-1d"
          WAIT_FOR: "task_item_fixture"
    """.trimIndent()

/**
 * A flow whose first seed brought the section in, and whose second
 * seed names that section to wait for.
 *
 * The section was on screen before the second seed ran, so the wait
 * for it returns at once.
 */
val HELPER_SECTION_FROM_EARLIER_SEED =
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
    """.trimIndent()

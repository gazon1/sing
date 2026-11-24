package com.singularity.todo.arch

// The flow fixtures that speak about the scope of a command — the conditional
// block it runs in, and the blocks nested in that one.
//
// They are fixtures of their own file because they are data, not tests: the
// scope is a hierarchy, and the flows that show it are read by the test that
// names them.

/**
 * A flow whose conditional block runs a file that seeds.
 *
 * The condition is on the `runFlow` that pulls the file in, so the file
 * belongs to the block: the seed in it may never have run, and an assertion
 * outside the block is not about it.
 */
val CONDITIONAL_FILE_SEED_OUTSIDE_ASSERT =
    """
    appId: com.singularity.todo
    name: fixture
    ---
    - runFlow:
        when:
          visible:
            id: nav_tab_today
        file: wrapper.yaml
    - assertVisible:
        id: task_item_fixture
    """.trimIndent()

/** The same flow, without the assertion outside the block. */
val CONDITIONAL_FILE_SEED_BLOCK =
    """
    appId: com.singularity.todo
    name: fixture
    ---
    - runFlow:
        when:
          visible:
            id: nav_tab_today
        file: wrapper.yaml
    """.trimIndent()

/** The file the conditional block runs: it seeds. */
val CONDITIONAL_FILE_SEED =
    """
    appId: com.singularity.todo
    name: wrapper
    ---
    - runFlow:
        file: seed.yaml
        env:
          QUERY: "task=Fixture&due=today"
    """.trimIndent()

/** The same file, with an assertion on the entity it seeded. */
val CONDITIONAL_FILE_SEED_ASSERTED =
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

/**
 * A seed in a conditional block, and an assertion in a block nested in it.
 *
 * The outer block runs only when its condition holds, so the seed may never
 * have run — but the inner block is inside the outer one: where the inner
 * condition holds, the outer one held too, and the assertion is about the
 * seeded entity.
 */
val NESTED_BLOCK_SEED_AND_ASSERT =
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
          - runFlow:
              when:
                visible:
                  id: nav_tab_today
              commands:
                - assertVisible:
                    id: task_item_fixture
    """.trimIndent()

/**
 * A seed in a block nested in a conditional block, and an assertion in the
 * outer block.
 *
 * The inner block runs only when its own condition holds, so the seed may
 * never have run — and an assertion in the outer block is not about it.
 */
val NESTED_BLOCK_SEED_OUTER_ASSERT =
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

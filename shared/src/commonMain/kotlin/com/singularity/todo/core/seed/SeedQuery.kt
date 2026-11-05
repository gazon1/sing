package com.singularity.todo.core.seed

/**
 * The query of a seed deep link — the keys it carries and what the handler made of them.
 *
 * A seed link is written by hand, in a flow, and one of its keys (`blockedBy=`)
 * changes the *meaning* of the seed rather than the shape of it: a misspelling
 * there produces a task that looks right and carries no blocker. The keys whose
 * names explain themselves (`task=`, `note=`, `project=`) can be read off the
 * query, and a key that is merely ignored (`due=`) is visibly ignored — but the
 * one key that carries a rule is the one key with no feedback, which is why the
 * handler has to know its own keys.
 *
 * The set of known keys lives here rather than in the activity that parses the
 * query, so that the rule can be held by a test and so that the helper's
 * documented key table and the handler's own set can be compared against each
 * other.
 */
object SeedQuery {
    /**
     * Every key the seed link understands.
     *
     * The control keys (`cleanup`, `reset`, `session`) are part of it: they are
     * the handler's own, and a query that carries one is not carrying a
     * misspelling.
     */
    val KNOWN_KEYS: Set<String> =
        setOf(
            // The entity a link seeds.
            "task",
            "note",
            "project",
            "profile",
            // What the seeded entity carries.
            "desc",
            "due",
            "updatedAt",
            "id",
            // Another entity, named by title — a deep link cannot know an id it
            // has not created itself.
            "linkTo",
            "blockedBy",
            // The handler's own control keys.
            "cleanup",
            "reset",
            "session",
        )

    /**
     * The keys that name the entity a link seeds.
     *
     * A query that carries none of them and no control key asks for nothing the
     * handler can write.
     */
    val ENTITY_KEYS: Set<String> = setOf("task", "note", "project", "profile")

    /**
     * The handler's own control keys — they change the state instead of seeding
     * an entity.
     */
    val CONTROL_KEYS: Set<String> = setOf("cleanup", "reset", "session")

    /**
     * Reads what the handler made of a query.
     *
     * @param queryKeys every key the query carried, including a key whose value
     *   was empty — a known key with an empty value is not an unknown key, and
     *   the difference between the two is what a caller who misspelled a key
     *   cannot see.
     * @param usableKeys the keys the handler could act on: the ones whose value
     *   was not empty.
     */
    fun inspect(
        queryKeys: Collection<String>,
        usableKeys: Set<String>,
    ): SeedQueryReport {
        val unknown = queryKeys.filterNot { it in KNOWN_KEYS }.distinct()
        val seedsNothing = usableKeys.none { it in ENTITY_KEYS || it in CONTROL_KEYS }
        return SeedQueryReport(unknownKeys = unknown, seedsNothing = seedsNothing)
    }
}

/**
 * What the handler made of a seed query.
 *
 * @param unknownKeys the keys the query carried that the handler does not know.
 * @param seedsNothing whether the query asked for nothing the handler can write.
 */
data class SeedQueryReport(
    val unknownKeys: List<String>,
    val seedsNothing: Boolean,
) {
    /**
     * The lines the handler reports, in the order it reports them.
     *
     * Every line names the key it is about: a finding that does not name its key
     * leaves the caller to guess which of the query's keys was not understood.
     */
    fun messages(): List<String> =
        buildList {
            unknownKeys.forEach { add("Unknown seed query key '$it' — ignored") }
            if (seedsNothing) {
                add("The seed query named nothing the seed could write — nothing was seeded")
            }
        }
}

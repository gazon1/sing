package com.singularity.todo.core.seed

/**
 * The query-key vocabulary of the `todo-debug://seed` deep link.
 *
 * One owner for the keys the debug activity reads and the keys a flow may write.
 * An unknown key is a typo or a retired capability; the activity reports it by
 * name instead of ignoring it.
 */
object SeedLinkKeys {
    const val TASK = "task"
    const val DESC = "desc"
    const val DUE = "due"
    const val UPDATED_AT = "updatedAt"
    const val BLOCKED_BY = "blockedBy"
    const val ID = "id"
    const val NOTE = "note"
    const val LINK_TO = "linkTo"
    const val PROJECT = "project"

    /**
     * How many projects to seed at once.
     *
     * A profile with a hundred projects is a fixture — the menu's folded
     * section exists for it — and a hundred deep links are not a way to make
     * one.
     */
    const val PROJECTS = "projects"
    const val PROFILE = "profile"
    const val SESSION = "session"
    const val RESET = "reset"
    const val CLEANUP = "cleanup"

    /** What the key does to the profile. */
    enum class Kind {
        /** Creates or updates one entity. */
        Seed,

        /** Changes the session instead of the content. */
        Session,

        /** Deletes instead of seeding. */
        Delete,

        /** Changes how the seed is written. */
        Modifier,
    }

    val kinds: Map<String, Kind> =
        mapOf(
            TASK to Kind.Seed,
            NOTE to Kind.Seed,
            PROJECT to Kind.Seed,
            PROJECTS to Kind.Seed,
            PROFILE to Kind.Seed,
            DESC to Kind.Modifier,
            DUE to Kind.Modifier,
            UPDATED_AT to Kind.Modifier,
            BLOCKED_BY to Kind.Modifier,
            ID to Kind.Modifier,
            LINK_TO to Kind.Modifier,
            SESSION to Kind.Session,
            RESET to Kind.Delete,
            CLEANUP to Kind.Delete,
        )

    val all: Set<String> = kinds.keys

    val deleteKeys: Set<String> = kinds.filterValues { it == Kind.Delete }.keys

    /** The keys in [keys] that this vocabulary does not know, in input order. */
    fun unknown(keys: Iterable<String>): List<String> = keys.filterNot { it in all }
}

package com.singularity.todo.core.backup

/**
 * The kinds of row a payload carries.
 *
 * The kind is what a row's key is looked up under: the index of the previous
 * export is a map from the kind to the rows of that kind, and a row is "the same
 * row" when it has the same key **within the same kind**. A task and a note can
 * share an id without being each other.
 *
 * ## The removal phase
 *
 * Every kind declares the phase it is removed in, and the phases are ordered:
 * the children go before the parents, because the tables have no foreign key to
 * cascade. The phase is a constructor argument, not a default, so a kind added
 * without one does not compile.
 *
 * The kinds are declared in the order they are removed in, and the importer
 * reads that order instead of the reader counting lines.
 *
 * See `docs/decisions/2026-10-27-the-removal-order-is-a-declaration.md`.
 */
enum class BackupEntityKind(val removalPhase: BackupRemovalPhase) {

    /** The rows that need their parent alive: the links, and the rows that hang off one. */
    TASK_TAGS(BackupRemovalPhase.CHILDREN_FIRST),

    /** The link between a task and the task it depends on. */
    TASK_DEPENDENCIES(BackupRemovalPhase.CHILDREN_FIRST),

    /** The rows of a checklist, which hang off the task that holds it. */
    CHECKLIST_ITEMS(BackupRemovalPhase.CHILDREN_FIRST),

    /** The link between a project and the tag group it inherits. */
    PROJECT_TAG_GROUPS(BackupRemovalPhase.CHILDREN_FIRST),

    /** The annotations of an attachment, which hang off the attachment. */
    ATTACHMENT_ANNOTATIONS(BackupRemovalPhase.OWN_ROWS),

    /** The attachment rows. */
    ATTACHMENTS(BackupRemovalPhase.OWN_ROWS),

    /** The saved agenda views. */
    AGENDA_VIEWS(BackupRemovalPhase.OWN_ROWS),

    /** The saved searches. */
    SAVED_SEARCHES(BackupRemovalPhase.OWN_ROWS),

    /** The reminders of a task. */
    TASK_REMINDERS(BackupRemovalPhase.OWN_ROWS),

    /** The reminders of a project. */
    PROJECT_REMINDERS(BackupRemovalPhase.OWN_ROWS),

    /** The time entries of a task. */
    TIME_ENTRIES(BackupRemovalPhase.OWN_ROWS),

    /** The tags. */
    TAGS(BackupRemovalPhase.PARENTS_LAST),

    /** The tag groups the tags hang off. */
    TAG_GROUPS(BackupRemovalPhase.PARENTS_LAST),

    /** The tasks. */
    TASKS(BackupRemovalPhase.PARENTS_LAST),

    /** The notes. */
    NOTES(BackupRemovalPhase.PARENTS_LAST),

    /** The projects. */
    PROJECTS(BackupRemovalPhase.PARENTS_LAST),

    /**
     * The profiles.
     *
     * A profile belongs to the account it was made in, so the restore may only
     * take out the profile of the account it is restoring — the same scope every
     * other removal is under.
     */
    PROFILES(BackupRemovalPhase.PARENTS_LAST),
}

/**
 * The order the removals go in.
 *
 * ## Why the order is a fact about the tables
 *
 * The link tables carry no `user_id` of their own: a row of `task_tags` belongs
 * to whoever owns the task it joins, and the statement that removes it reaches
 * its row through that task. So a removal of a task and a removal of one of its
 * links cannot be taken out in any order but one — the link first, while the task
 * is still there. The other order leaves the link behind, pointing at a task that
 * is not there any more, and nothing ever comes to clean it up: the tables have
 * no foreign key to cascade.
 *
 * The order is therefore a decision about the tables, and it lives next to the
 * kinds it orders rather than spread through the importer.
 *
 * See `docs/decisions/2026-10-27-the-removal-order-is-a-declaration.md`.
 */
enum class BackupRemovalPhase {

    /** The rows that need their parent alive: the links, and the rows that hang off one. */
    CHILDREN_FIRST,

    /** The rows that hold no link of their own. */
    OWN_ROWS,

    /** The rows the children hang off: the parents, and last of all. */
    PARENTS_LAST,
    ;

    /**
     * The kinds this phase removes, in the order they are removed in.
     *
     * The order within a phase is the order the kinds are declared in, so the
     * whole order of the removals is one list, in one place.
     */
    val kinds: List<BackupEntityKind> get() = BackupEntityKind.entries.filter { it.removalPhase == this }
}

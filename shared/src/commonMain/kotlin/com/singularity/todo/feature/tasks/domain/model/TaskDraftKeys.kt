package com.singularity.todo.feature.tasks.domain.model

/**
 * The draft keys the create editor reads and writes.
 *
 * ## Why they are named here
 *
 * The draft is the only channel between the screen that starts a task and the
 * editor that finishes it. Two screens write into it — the agenda and the
 * calendar — and each of them used to build its own key string, in two places
 * each: once when writing the draft and once when naming the key to read it
 * back. A key that is built twice is a key that can drift, and the failure is
 * silent: the editor opens with an empty draft and the user has no idea why.
 *
 * So the keys are built here, once, and the screens pass the finished key to
 * the editor as [com.singularity.todo.feature.tasks.presentation.nav.TasksRoute.Create.draftKey].
 *
 * @see com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateDeps.DRAFT_KEY
 */
object TaskDraftKeys {

    /**
     * The generic create-editor draft.
     *
     * The autosave writes here, and the editor reads here when no other key was
     * given. A draft restored from elsewhere is still autosaved here — the
     * autosave has one key because it has one draft.
     */
    const val TASK_CREATE = "task_create_draft"

    /**
     * The draft the agenda writes when the user starts a task inside a section.
     *
     * @param sectionId the section the task is started in.
     */
    fun agendaSection(sectionId: String): String = "section_create_draft_$sectionId"

    /**
     * The draft the calendar writes when the user starts a task on several dates.
     *
     * One key, not one per combination of dates: the calendar writes the dates
     * it selected into the draft, and the draft is read back once.
     */
    const val CALENDAR_MULTI_DATE = "calendar_create_draft"

    /**
     * The draft the agenda writes when the user starts a task on several dates
     * from the date picker in the top bar.
     *
     * A key of its own, not the calendar's: the two screens write different
     * drafts, and a shared key would let one screen's draft be restored in the
     * other.
     */
    const val AGENDA_MULTI_DATE = "agenda_multi_date_draft"
}

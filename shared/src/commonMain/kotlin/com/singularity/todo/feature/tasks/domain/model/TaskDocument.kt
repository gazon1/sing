package com.singularity.todo.feature.tasks.domain.model

import com.singularity.todo.feature.tags.TagId

/**
 * A task document: a task, and the parts of the document that speak.
 *
 * ## The parts are nullable on purpose
 *
 * `null` means *the document says nothing about this part*. It does not mean
 * "the part is empty" — an empty list is a claim that there are no tags, and
 * a device that never sends the key says nothing at all.
 *
 * The distinction is not academic. The wire carries a document, and the
 * document carries the fields the sending device knew about: a device that
 * predates the tags sends no `tags` key, and a device that predates the
 * dependencies sends no `dependsOn` key. Read as an empty list, the first of
 * those would take the tags off every task this device pulls — and the tags
 * live in a cross-reference table, so an empty list is a deletion.
 *
 * ## What the pull does with a document
 *
 * It applies what the document says and leaves what it does not say alone.
 * The task itself is applied whole: the row of a task is replaced by the row
 * that arrived, and the row carries the fields the document spoke about.
 *
 * ## The task inside
 *
 * The task carries the same parts as the local device knows them. The
 * document's parts are what the *sender* said, and where they are not null
 * they win — the task inside is the row, and the parts are the claims about
 * the parts of the row that live elsewhere.
 */
data class TaskDocument(
    val task: Task,
    /** The tags the document carries; `null` when it says nothing about them. */
    val tags: List<TagId>?,
    /** The dependencies the document carries; `null` when it says nothing about them. */
    val blockedBy: Set<TaskId>?,
) {
    companion object {
        /**
         * A document that says everything the task carries.
         *
         * For the callers that hold the whole task: a local write, and a test
         * that puts a task on the wire.
         *
         * ## The task must be one that was read whole
         *
         * A task read through `get()` is not such a task: that read does not
         * load the tags or the dependencies — they live in the cross-reference
         * tables — so the task carries two empty collections that say nothing
         * about the work. `of()` turns that emptiness into a claim, and the
         * claim is a deletion: the tags and the blocking edges of the task go
         * away. Read the series (`getSeries`) or the task list for a task whose
         * parts were loaded.
         */
        fun of(task: Task): TaskDocument = TaskDocument(task, task.tags, task.blockedBy)
    }
}

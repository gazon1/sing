package com.singularity.todo.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Embedded

/**
 * A row of the index, and where the match is.
 *
 * The index answers two questions about a row: that it carries the word, and
 * where in it it carries it. The second answer comes from FTS4's `offsets()`
 * and is a string of integers; it travels with the row because the row and the
 * position are the answer to one question, and a reader that had to ask twice
 * could get two answers.
 *
 * The offsets are the raw answer. Turning them into a range in the characters
 * of the text is the reader's job — see [FtsOffsets].
 *
 * @param task the task the index found.
 * @param offsets the position of the match, as `offsets()` reported it.
 */
data class TaskMatchRow(
    @Embedded val task: TaskEntity,
    @ColumnInfo(name = "match_offsets") val offsets: String?,
)

/**
 * A note the index found, and where the match is. See [TaskMatchRow].
 *
 * @param note the note the index found.
 * @param offsets the position of the match, as `offsets()` reported it.
 */
data class NoteMatchRow(
    @Embedded val note: NoteEntity,
    @ColumnInfo(name = "match_offsets") val offsets: String?,
)

/**
 * A project the index found, and where the match is. See [TaskMatchRow].
 *
 * @param project the project the index found.
 * @param offsets the position of the match, as `offsets()` reported it.
 */
data class ProjectMatchRow(
    @Embedded val project: ProjectEntity,
    @ColumnInfo(name = "match_offsets") val offsets: String?,
)

/**
 * A tag the index found, and where the match is. See [TaskMatchRow].
 *
 * @param tag the tag the index found.
 * @param offsets the position of the match, as `offsets()` reported it.
 */
data class TagMatchRow(
    @Embedded val tag: TagEntity,
    @ColumnInfo(name = "match_offsets") val offsets: String?,
)

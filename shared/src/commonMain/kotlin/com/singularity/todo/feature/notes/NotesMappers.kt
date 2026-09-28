package com.singularity.todo.feature.notes

import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toEpochMillisOrNull
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.database.toInstantOrNull
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.Hlc

/**
 * Entity/domain mappers for notes.
 *
 * Split out of [NotesRepository] because the combined file carried 52 functions — twice the
 * detekt `TooManyFunctions` limit — and both the interface and the implementation were
 * baselined to hide it. Keeping the mappers separate lets the split actually clear the
 * suppression rather than move it.
 */

internal fun NoteEntity.toNote(): Note = Note(
    id = NoteId.fromString(id),
    userId = UserId.fromString(userId),
    title = title,
    bodyMarkdown = bodyMarkdown,
    bodyHtml = bodyHtml,
    isFolder = isFolder,
    kind = kind,
    parentNoteId = parentNoteId?.let { NoteId.fromString(it) },
    isPinned = isPinned,
    pinnedAt = pinnedAt.toInstantOrNull(),
    color = if (color != null && color != 0) NoteColor(color) else null,
    sortOrder = sortOrder,
    wordCount = wordCount,
    charCount = charCount,
    outgoingLinks = outgoingLinks.parseLinksJson(),
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
    deletedAt = deletedAt.toInstantOrNull(),
    archivedAt = archivedAt.toInstantOrNull(),
    serverVersion = sync.serverVersion,
    hlc = sync.hlc?.let { Hlc(it) },
)

fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id.value,
    userId = userId.value,
    title = title,
    bodyMarkdown = bodyMarkdown,
    bodyHtml = bodyHtml,
    isFolder = isFolder,
    kind = kind,
    parentNoteId = parentNoteId?.value,
    isPinned = isPinned,
    pinnedAt = pinnedAt?.toEpochMillis(),
    color = color?.value,
    sortOrder = sortOrder,
    wordCount = wordCount,
    charCount = charCount,
    outgoingLinks = outgoingLinks.toLinksJson(),
    createdAt = createdAt.toEpochMillis(),
    updatedAt = updatedAt.toEpochMillis(),
    deletedAt = deletedAt?.toEpochMillisOrNull(),
    archivedAt = archivedAt?.toEpochMillisOrNull(),
    sync = SyncColumns(serverVersion = serverVersion, hlc = hlc?.encoded),
)

private fun String.parseLinksJson(): List<String> {
    if (isBlank() || this == "[]") return emptyList()
    val result = mutableListOf<String>()
    val regex = Regex(""""([^"\\]+)"""")
    for (match in regex.findAll(this)) {
        result.add(match.groupValues[1])
    }
    return result
}

internal fun List<String>.toLinksJson(): String {
    if (isEmpty()) return "[]"
    // `items` is bound explicitly on purpose. Inside `buildString` the implicit
    // receiver is the StringBuilder, which is a CharSequence, so a bare
    // `forEachIndexed` resolves to CharSequence.forEachIndexed and iterates over
    // the builder's own characters *while appending to it* — an unbounded loop
    // that ends in OutOfMemoryError. Binding the list removes the ambiguity.
    // The identical bug lived in TaskOutgoingLinks.toLinksJson.
    val items = this
    return buildString {
        append('[')
        items.forEachIndexed { index, link ->
            if (index > 0) append(',')
            append('"').append(link).append('"')
        }
        append(']')
    }
}

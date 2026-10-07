package com.singularity.todo.core.attachments.annotation

import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Read and write access to the notes written against one text attachment.
 *
 * ## No `userId` parameter
 *
 * Every method takes the attachment or the annotation and nothing else. The owning profile
 * is resolved from [ProfileAwareCurrentUser] inside the implementation, on every call —
 * which is what makes it impossible for a caller to write a note on behalf of another
 * profile by passing a different id. This is the same shape as
 * [com.singularity.todo.core.attachments.AttachmentRepository.watchByTask]; see ADR
 * `2026-09-21-generic-user-scoped-repository`.
 *
 * ## No pass-through use case
 *
 * These methods are the contract the ViewModel injects directly. A use case that only
 * forwarded to this repository would add a name and a file and no behaviour, and the
 * `PassThroughUseCase` detekt rule exists to say so.
 */
interface AttachmentAnnotationRepository {

    /** Live annotations for [attachmentId], oldest first, soft-deleted rows excluded. */
    fun watchForAttachment(attachmentId: AttachmentId): Flow<List<AttachmentAnnotation>>

    /**
     * Writes a new note against [range].
     *
     * The quote is taken from the range rather than re-read from the document here: the
     * caller is the one that knows what the user selected, and the document may already
     * have changed by the time the write lands.
     */
    suspend fun create(range: TextRange, note: String): Result<AttachmentAnnotation>

    /** Replaces the text of an existing note. Does not move the range. */
    suspend fun update(id: AttachmentAnnotationId, note: String): Result<Unit>

    /** Soft-deletes an annotation; the row stays for sync. */
    suspend fun delete(id: AttachmentAnnotationId): Result<Unit>
}

class AttachmentAnnotationRepositoryImpl(
    private val dao: AttachmentAnnotationDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) : AttachmentAnnotationRepository {

    override fun watchForAttachment(attachmentId: AttachmentId): Flow<List<AttachmentAnnotation>> =
        currentUser.observeForCurrentUser { uid ->
            dao.watchByAttachmentForUser(attachmentId.value, uid.value)
                .map { entities -> entities.map { it.toAnnotation() } }
        }

    override suspend fun create(range: TextRange, note: String): Result<AttachmentAnnotation> =
        runCatchingCancellable {
            val uid: UserId = currentUser.scopedUserId.value
            val now: Instant = clock.now()
            val annotation = AttachmentAnnotation(
                id = AttachmentAnnotationId.generate(),
                range = range,
                note = note,
                userId = uid,
                createdAt = now,
                updatedAt = now,
            )
            dao.upsert(annotation.toEntity())
            annotation
        }

    override suspend fun update(id: AttachmentAnnotationId, note: String): Result<Unit> =
        runCatchingCancellable {
            val uid: UserId = currentUser.scopedUserId.value
            val rows = dao.updateNoteForUser(
                id = id.value,
                note = note,
                ts = clock.now().toEpochMilliseconds(),
                userId = uid.value,
            )
            require(rows > 0) {
                "Annotation $id not found, already deleted, or not owned by the current profile"
            }
        }

    override suspend fun delete(id: AttachmentAnnotationId): Result<Unit> =
        runCatchingCancellable {
            val uid: UserId = currentUser.scopedUserId.value
            val rows = dao.softDeleteForUser(id.value, clock.now().toEpochMilliseconds(), uid.value)
            require(rows > 0) {
                "Annotation $id not found, already deleted, or not owned by the current profile"
            }
        }
}

internal fun AttachmentAnnotationEntity.toAnnotation(): AttachmentAnnotation = AttachmentAnnotation(
    id = AttachmentAnnotationId.fromString(id),
    range = TextRange(
        attachmentId = AttachmentId.fromString(attachmentId),
        start = rangeStart,
        end = rangeEnd,
        quote = quote,
    ),
    note = note,
    userId = UserId.fromString(userId),
    createdAt = Instant.fromEpochMilliseconds(createdAt),
    updatedAt = Instant.fromEpochMilliseconds(updatedAt),
    deletedAt = deletedAt?.let { Instant.fromEpochMilliseconds(it) },
)

internal fun AttachmentAnnotation.toEntity(): AttachmentAnnotationEntity = AttachmentAnnotationEntity(
    id = id.value,
    attachmentId = range.attachmentId.value,
    userId = userId.value,
    rangeStart = range.start,
    rangeEnd = range.end,
    quote = range.quote,
    note = note,
    syncStatus = "Pending",
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    deletedAt = deletedAt?.toEpochMilliseconds(),
    serverVersion = 0L,
    hlc = null,
)

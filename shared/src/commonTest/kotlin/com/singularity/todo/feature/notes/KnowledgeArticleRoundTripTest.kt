package com.singularity.todo.feature.notes

import com.singularity.todo.core.backup.toDto
import com.singularity.todo.core.backup.toEntity
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.projects.domain.model.ProjectId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What an article is made of survives the trips the note takes.
 *
 * ## Why this is a test of its own
 *
 * An article is not a row of its own: it is a note, and what makes it an article is
 * the body it carries. So the question is not "does the new table survive the
 * backup" — there is no new table — but "does the thing that makes a note an
 * article come back out of the backup and out of the sync payload unchanged".
 *
 * If it did not, a user would restore a backup and find their articles gone: the
 * notes would still be there, but nothing would answer "which articles does this
 * project have".
 *
 * The contract these tests hold is deliberately narrow — the body, the attachment
 * and the kind. The kind matters because a `Daily` note is not an article even when
 * it carries the section.
 */
@Tag("fast")
class KnowledgeArticleRoundTripTest {
    private val user = UserId("u1")

    private val articleBody = "<h2>Sources</h2><ul><li>https://example.com</li></ul>"

    @Test
    fun `an article survives a backup export and import`() {
        val entity =
            NoteEntity(
                id = "n1",
                userId = user.value,
                title = "Sources on solar",
                bodyMarkdown = null,
                bodyHtml = articleBody,
                parentNoteId = null,
                projectId = "p1",
                createdAt = 1_000,
                updatedAt = 2_000,
                deletedAt = null,
                archivedAt = null,
            )

        val restored = entity.toDto().toEntity(user.value)

        assertEquals(articleBody, restored.bodyHtml, "the body is what the backup carries")
        assertEquals("p1", restored.projectId, "the attachment is what the backup carries")
        assertEquals(NoteKind.Plain, restored.kind, "the kind is what the backup carries")
        assertTrue(
            KnowledgeArticle.carriesSources(restored.bodyHtml, restored.bodyMarkdown),
            "the restored note is still an article",
        )
    }

    @Test
    fun `an article survives a sync round trip`() {
        val note =
            Note(
                id = NoteId("n1"),
                userId = user,
                title = "Sources on solar",
                bodyHtml = articleBody,
                projectId = ProjectId("p1"),
                createdAt = Instant.fromEpochMilliseconds(1_000),
                updatedAt = Instant.fromEpochMilliseconds(2_000),
            )

        val decoded = StableJson.decodeFromJsonElement(Note.serializer(), note.toJson())

        assertEquals(articleBody, decoded.bodyHtml, "the body is what the sync payload carries")
        assertEquals("p1", decoded.projectId?.value, "the attachment is what the sync payload carries")
        assertTrue(
            KnowledgeArticle.carriesSources(decoded.bodyHtml, decoded.bodyMarkdown),
            "the synced note is still an article",
        )
    }
}

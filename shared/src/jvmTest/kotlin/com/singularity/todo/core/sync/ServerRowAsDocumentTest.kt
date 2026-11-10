package com.singularity.todo.core.sync

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The server's row, read back as a document.
 *
 * The server keeps a row: the row's identity is the id the event names and the
 * owner the session has, and the fields it stores are the ones the server
 * writes. A row therefore arrives without the two fields every model cannot do
 * without, and a document decoded from it fails on the first missing one.
 *
 * These tests pin the two answers the completion gives: the id comes from the
 * event, the owner comes from the session — and a document that states either
 * of them keeps what it says.
 */
@Tag("fast")
class ServerRowAsDocumentTest {
    private fun writer(owner: UserId = UserId("owner-1")) =
        fakeSyncDocumentWriter(currentUser = FakeProfileAwareCurrentUser(initialUserId = owner))

    @Test
    fun `a row without an id and an owner becomes a document with both`() {
        val document =
            writer().documentFromRow(
                "task-1",
                buildJsonObject { put("title", JsonPrimitive("Buy milk")) },
            )

        assertEquals(
            "task-1",
            (document["id"] as JsonPrimitive).content,
            "the row's key is the id the event names — the server stores it as a column, " +
                "not as a field of the document",
        )
        assertEquals(
            "owner-1",
            (document["userId"] as JsonPrimitive).content,
            "the row's owner is the session's — the server serves only the rows of the " +
                "account that asks for them",
        )
    }

    @Test
    fun `a document that names an owner keeps it`() {
        val document =
            writer().documentFromRow(
                "task-1",
                buildJsonObject {
                    put("id", JsonPrimitive("task-1"))
                    put("userId", JsonPrimitive("someone-else"))
                },
            )

        assertEquals(
            "someone-else",
            (document["userId"] as JsonPrimitive).content,
            "the owner of a document is never overwritten from the session when the " +
                "document states one",
        )
    }
}

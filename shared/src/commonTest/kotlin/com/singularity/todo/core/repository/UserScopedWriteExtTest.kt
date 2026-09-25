package com.singularity.todo.core.repository

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlin.test.Test
import kotlin.test.assertFailsWith

class UserScopedWriteExtTest {

    @Test
    fun matchesCurrentUser_doesNotThrow() {
        val cu = FakeProfileAwareCurrentUser(initialUserId = UserId("u-1"))
        cu.assertCanWrite(entityId = "task-1", entityUserId = UserId("u-1"))
    }

    @Test
    fun anonymousUser_isAccepted() {
        val cu = FakeProfileAwareCurrentUser(initialUserId = UserId("u-1"))
        cu.assertCanWrite(entityId = "task-1", entityUserId = UserId.anonymous)
    }

    @Test
    fun differentUser_throws() {
        val cu = FakeProfileAwareCurrentUser(initialUserId = UserId("u-1"))
        assertFailsWith<CrossUserWriteException> {
            cu.assertCanWrite(entityId = "task-1", entityUserId = UserId("u-2"))
        }
    }
}

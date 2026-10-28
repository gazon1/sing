package com.singularity.todo.core.draft

import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Serializable
private data class Draft(val text: String)

@Tag("fast")
class UserScopedDraftStoreTest {
    @Test
    fun prefixIsolatesUsers() = runTest {
        val auth = FakeAuthRepository()
        val user = FakeProfileAwareCurrentUser(authRepository = auth)
        val delegate = FakeDraftStore()
        val store = UserScopedDraftStore(user, delegate)
        store.save("k", Draft("hello"), serializer())
        assertEquals("hello", store.load("k", serializer<Draft>())?.text)
        auth.setUserId(UserId.generate())
        // allow collectors to update
        kotlinx.coroutines.yield()
        assertNull(store.load("k", serializer<Draft>()))
    }

    @Test
    fun loadReturnsSaved() = runTest {
        val user = FakeProfileAwareCurrentUser()
        val delegate = FakeDraftStore()
        val store = UserScopedDraftStore(user, delegate)
        store.save("k", Draft("hello"), serializer())
        assertEquals("hello", store.load("k", serializer<Draft>())?.text)
    }
}
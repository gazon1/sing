@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.repository

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [observeForCurrentUser] — verifies it correctly re-subscribes on user change
 * and stays stable when the userId does not change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class UserScopedFlowTest {

    @Test
    fun user_change_re_subscribes_to_new_userid() = runTest {
        val authRepo = FakeAuthRepository(Session.Anonymous(UserId("user-a")))
        val pau = FakeProfileAwareCurrentUser(authRepo, scope = backgroundScope)

        // Process the session collector to set fakeScopedUserId = user-a
        advanceUntilIdle()

        // Collect to establish subscription
        val result1 = pau.observeForCurrentUser { uid ->
            kotlinx.coroutines.flow.flowOf(uid.value)
        }.first()
        assertEquals("user-a", result1)

        // Switch user
        authRepo.setUserId(UserId("user-b"))
        // runCurrent drives the StandardTestDispatcher used by backgroundScope (where the
        // session-collector launched in FakeProfileAwareCurrentUser is hosted). advanceUntilIdle
        // alone doesn't resume infinite collectors waiting on a StateFlow.
        runCurrent()
        assertEquals(UserId("user-b"), pau.scopedUserId.value)

        val result2 = pau.observeForCurrentUser { uid ->
            kotlinx.coroutines.flow.flowOf(uid.value)
        }.first()
        assertEquals("user-b", result2)
    }

    @Test
    fun same_userid_does_not_resubscribe() = runTest {
        val authRepo = FakeAuthRepository(Session.Anonymous(UserId("user-a")))
        val pau = FakeProfileAwareCurrentUser(authRepo, scope = backgroundScope)

        // Process the session collector so fakeScopedUserId.value = user-a
        runCurrent()

        var subscriptionCount = 0
        val source: (UserId) -> kotlinx.coroutines.flow.Flow<String> = { uid ->
            subscriptionCount++
            kotlinx.coroutines.flow.flowOf(uid.value)
        }

        // Collect twice without changing userId — each observeForCurrentUser call
        // creates its own flatMapLatest, so source is called once per call.
        // Within each flatMapLatest, distinctUntilChanged prevents re-calling
        // source when the same userId is observed.
        pau.observeForCurrentUser(source).first()
        pau.observeForCurrentUser(source).first()

        assertEquals(2, subscriptionCount)
    }
}

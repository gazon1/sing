@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai.tools

import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Locks in the profile-aware default userId contract for the three MCP
 * read tools: [ListTasksTool], [ListLinkedTasksTool], [SearchTasksTool].
 *
 * Regression history:
 *   - Each tool previously defaulted the userId to the literal string
 *     "local-user", which never matched the AI Agent's scoped userId format
 *     "{profileId}/{userId}". Queries always returned [] despite the DB
 *     being populated. Fixed 2026-09-08.
 *
 * If anyone changes the default to a hardcoded string again, these tests
 * fail with a clear message.
 */
@Tag("fast")
class ReadToolsProfileAwareTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    /**
     * The identity the tools will actually query with, taken from the same production
     * derivation ([ProfileAwareCurrentUser.liveScopedUserId]) the repository uses — not
     * a hand-rolled `combine`.
     *
     * The previous version combined `currentUser.userId` with `activeProfileId` itself,
     * which raced the async collector in [CurrentUser]: the seed read could land before
     * the collector and the tool's read after it, so the test seeded under one identity
     * and queried under another. It passed on the JVM and failed on the
     * Android/Robolectric source set, where the collector lands later. Deriving both
     * sides from the same flow removes the race instead of tolerating it.
     */
    private suspend fun resolveScopedUserId(
        currentUser: ProfileAwareCurrentUser,
        @Suppress("UNUSED_PARAMETER") profileRepository: FakeProfileRepository,
    ): UserId = currentUser.liveScopedUserId.first()

    private fun seedTask(
        repo: FakeTaskRepository,
        userId: UserId,
        title: String,
        projectId: com.singularity.todo.feature.projects.domain.model.ProjectId? = null,
    ) {
        val task = Task(
            id = TaskId.generate(),
            title = title,
            description = null,
            priority = TaskPriority.None,
            kind = TaskKind.Task,
            projectId = projectId,
            parentTaskId = null,
            tags = emptyList(),
            dueDate = null,
            dueTime = null,
            createdAt = now,
            updatedAt = now,
            userId = userId,
        )
        repo.add(task)
    }

    private fun buildProfileAware(
        authUserId: String,
    ): Triple<ProfileAwareCurrentUser, FakeAuthRepository, FakeProfileRepository> {
        val auth = FakeAuthRepository(initialSession = Session.Anonymous(UserId.fromString(authUserId)))
        val profiles = FakeProfileRepository()
        // commonTest has no TestScope, so the collectors run on Dispatchers.Default.
        // That is now safe for reactive reads: `liveUserId` / `liveScopedUserId` are
        // derived from the session, so a read never observes the pre-collector
        // "anonymous" seed regardless of when the collector happens to run.
        val currentUser = ProfileAwareCurrentUser(
            currentUser = CurrentUser(auth, scope = createBackgroundScope()),
            profileRepository = profiles,
            scope = createBackgroundScope(),
        )
        return Triple(currentUser, auth, profiles)
    }

    // ─── list_tasks ────────────────────────────────────────────────────────────

    @Test
    fun list_tasks_uses_profile_scoped_userId_when_userId_is_blank() = runTest {
        val (currentUser, auth, profiles) = buildProfileAware(
            authUserId = "u-1",
        )
        profiles.switchTo(ProfileId.fromString("ai-agent"))
        val repo = FakeTaskRepository(explicitCurrentUser = currentUser)

        // Seed a task under the scoped userId the ProfileAwareCurrentUser would
        // actually emit for the AI Agent profile.
        val scoped = resolveScopedUserId(currentUser, profiles)
        seedTask(repo, scoped, "AI-Agent task A")
        seedTask(repo, UserId("local-user"), "Personal-only task") // must NOT show up

        val tool = ListTasksTool(repo)
        val output = tool.execute(ListTasksInput(limit = 50))
        val parsed = Json.parseToJsonElement(output).jsonObject
        val tasks = parsed["tasks"]!!.jsonArray

        val titles = tasks.map { (it.jsonObject["title"] as kotlinx.serialization.json.JsonPrimitive).content }
        assertEquals(listOf("AI-Agent task A"), titles, "only the AI Agent task should be returned")
    }

    // ─── list_linked_tasks ─────────────────────────────────────────────────────

    @Test
    fun list_linked_tasks_uses_profile_scoped_userId_when_blank() = runTest {
        val (currentUser, auth, profiles) = buildProfileAware(
            authUserId = "u-1",
        )
        profiles.switchTo(ProfileId.fromString("ai-agent"))
        val repo = FakeTaskRepository(explicitCurrentUser = currentUser)
        val scoped = resolveScopedUserId(currentUser, profiles)
        val projectId = com.singularity.todo.feature.projects.domain.model.ProjectId("p1")
        seedTask(repo, scoped, "AI-Agent linked task", projectId = projectId)
        seedTask(repo, UserId("local-user"), "Personal linked task", projectId = projectId)

        val tool = ListLinkedTasksTool(repo)
        val output = tool.execute(ListLinkedTasksInput(projectId = "p1"))
        val parsed = Json.parseToJsonElement(output).jsonObject
        val tasks = parsed["tasks"]!!.jsonArray
        assertEquals(1, tasks.size)
        val title = (tasks[0].jsonObject["title"] as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals("AI-Agent linked task", title)
    }

    // ─── search_tasks ──────────────────────────────────────────────────────────

    @Test
    fun search_tasks_uses_profile_scoped_userId_when_blank() = runTest {
        val (currentUser, auth, profiles) = buildProfileAware(
            authUserId = "u-1",
        )
        profiles.switchTo(ProfileId.fromString("ai-agent"))
        val repo = FakeTaskRepository(explicitCurrentUser = currentUser)
        val scoped = resolveScopedUserId(currentUser, profiles)
        seedTask(repo, scoped, "Findable AI-Agent task")
        seedTask(repo, UserId("local-user"), "Findable personal task")

        val tool = SearchTasksTool(repo)
        val output = tool.execute(SearchTasksInput(query = "Findable", limit = 50))
        val parsed = Json.parseToJsonElement(output).jsonObject
        val tasks = parsed["tasks"]!!.jsonArray
        assertEquals(1, tasks.size)
        val title = (tasks[0].jsonObject["title"] as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals("Findable AI-Agent task", title)
    }

    @Test
    fun search_tasks_does_not_match_local_user_when_profile_is_agent() = runTest {
        // Regression guard for the historical bug where blank userId silently
        // resolved to "local-user" — leaking personal data into agent queries.
        val (currentUser, auth, profiles) = buildProfileAware(
            authUserId = "u-1",
        )
        profiles.switchTo(ProfileId.fromString("ai-agent"))
        val repo = FakeTaskRepository(explicitCurrentUser = currentUser)
        seedTask(repo, UserId("local-user"), "private personal task")
        seedTask(repo, resolveScopedUserId(currentUser, profiles), "agent task")

        val tool = SearchTasksTool(repo)
        val output = tool.execute(SearchTasksInput(query = "task", limit = 50))
        val parsed = Json.parseToJsonElement(output).jsonObject
        val tasks = parsed["tasks"]!!.jsonArray
        val titles = tasks.map { (it.jsonObject["title"] as kotlinx.serialization.json.JsonPrimitive).content }.toSet()
        assertTrue("agent task" in titles)
        assertTrue("private personal task" !in titles, "personal data must not leak into agent search")
    }
}

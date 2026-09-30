package com.singularity.todo.test.fakes

import com.singularity.todo.core.ids.UserId

/**
 * The user id every test double defaults to.
 *
 * ## Why this exists
 *
 * The fakes did not agree on who "the current user" is. `FakeSettingsRepository`
 * and `FakeProfileAwareCurrentUser` defaulted to `UserId("test-user")`, while
 * `testTask()`, `testNote()` and friends stamped fixtures with
 * `UserId.anonymous`. Nothing broke loudly: the fakes that scope by user either
 * are not exercised together or re-stamp the id on write, so a ViewModel test
 * could read its own fixture while a repository test could not, and the
 * divergence stayed invisible until a test needed a fixture to be visible
 * through a user-scoped query.
 *
 * That is the class of defect `FakeRepositoryFidelityTest` exists to catch — a
 * fake reproducing a *different* contract from production rather than the real
 * one — and the cheapest fix is for every default to name the same user.
 *
 * ## When to override
 *
 * Multi-user tests want two distinct ids and should pass both explicitly:
 * `FakeSettingsRepository(initialUserId = "bob")`. The point of [DEFAULT] is
 * that the *unspecified* case is unambiguous, not that every test must use it.
 */
object TestUsers {
    /** The user a test double owns when a test does not say otherwise. */
    val DEFAULT: UserId = UserId("test-user")
}

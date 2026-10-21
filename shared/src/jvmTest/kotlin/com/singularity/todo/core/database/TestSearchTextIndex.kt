package com.singularity.todo.core.database

import com.singularity.todo.core.search.SearchTextIndex
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.search.data.SearchTextIndexImpl

/**
 * The full-text index over a test database.
 *
 * The repositories read the index through the one port, so a test that builds
 * a repository has to hand it an index. The index is built over the same
 * database and the same current user the repository has — an index with a
 * different user would answer about a different profile.
 */
internal fun AppDatabase.textIndex(currentUser: ProfileAwareCurrentUser): SearchTextIndex =
    SearchTextIndexImpl(
        ftsDao = ftsDao(),
        taskDao = taskDao(),
        currentUser = currentUser,
    )

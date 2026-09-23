package com.singularity.todo.feature.search.query

import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.TagDao

/**
 * Wraps [TagDao.findByNameForUser] as a [TagLookup] for use in [DefaultSearchQueryResolver].
 */
class DaoTagLookup(private val tagDao: TagDao) : TagLookup {
    override suspend fun findByName(userId: String, name: String): TagLookupResult? =
        tagDao.findByNameForUser(userId, name)?.let {
            TagLookupResult(id = it.id, userId = it.userId, name = it.name)
        }
}

/**
 * Wraps [ProjectDao.findByNameForUser] as a [ProjectLookup] for use in [DefaultSearchQueryResolver].
 */
class DaoProjectLookup(private val projectDao: ProjectDao) : ProjectLookup {
    override suspend fun findByName(userId: String, name: String): ProjectLookupResult? =
        projectDao.findByNameForUser(userId, name)?.let {
            ProjectLookupResult(id = it.id, userId = it.userId, name = it.name)
        }
}

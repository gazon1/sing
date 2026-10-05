package com.singularity.todo.feature.search.query

// Provenance: REWRITTEN from Orgzly (GPL-3.0) — the earlier registry entry said PORTED,
//   which was an over-classification on the auditor's part: these two classes wrap this
//   project's own Room DAOs and had no counterpart in Orgzly. Reimplemented per option
//   A1 in docs/legal/PROVENANCE.md

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

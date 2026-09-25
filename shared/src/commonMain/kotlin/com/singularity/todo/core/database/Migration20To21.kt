package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v20 to v21 — adds `outgoing_links` column to tasks for wikilink backlinks.
 *
 * Tasks can now be linked from notes and other tasks using `task://<id>` URL scheme,
 * enabling a backlinks panel in [com.singularity.todo.feature.tasks.presentation.screen.TaskDetailViewScreen].
 *
 * Storage format mirrors [NoteEntity.outgoingLinks]: a hand-rolled JSON array of URL strings,
 * serialised via [com.singularity.todo.feature.tasks.data.TaskOutgoingLinks.toLinksJson].
 */
class Migration20To21 : AutoMigrationSpec

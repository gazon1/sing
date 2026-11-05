package com.singularity.todo.core.database

import androidx.room3.Entity
import androidx.room3.Fts4
import androidx.room3.FtsOptions

/**
 * The full-text indexes of the searchable kinds.
 *
 * ## The pattern: external content
 *
 * Each table below is a *virtual* FTS4 table whose content lives in the ordinary
 * table it names (`content=`). The index therefore stores only the token
 * postings — the dictionary of words and the list of rows each word occurs in —
 * and never a second copy of the text. The row itself is read from the content
 * table when a query returns it.
 *
 * The alternative — an FTS table holding its own copy of the text — would double
 * the storage of every task and note, and would leave two copies to keep in step.
 *
 * Room keeps the index in step by itself: it installs triggers
 * (`room_fts_content_sync_*`) on the content table, so an insert, an update or a
 * delete of a task updates `tasks_fts` in the same transaction. Nothing in the
 * application writes to an FTS table.
 *
 * ## The columns are the searchable text, and nothing more
 *
 * The columns of an index decide what a term can find. They mirror the
 * searchable text of the kind (F3 of the search grammar): a task is searched by
 * its title and description, a note by its title, a project and a tag by their
 * name. A note's body is deliberately **not** indexed — the grammar does not
 * search it, and an index of a field nothing searches is storage spent for
 * nothing.
 *
 * ## `docid` is the content table's `rowid`
 *
 * The id of a task or a note is a UUID, and an FTS `docid` is an integer. Room
 * therefore maps `docid` to the content table's implicit `rowid` — the integer
 * SQLite gives every ordinary table — and the queries join on it:
 *
 * ```sql
 * SELECT t.* FROM tasks t JOIN tasks_fts ON t.rowid = tasks_fts.docid
 * ```
 *
 * The alternative — an integer surrogate key on every searchable table — would
 * put a second identity on the data and a migration on every one of them.
 */
@Entity(tableName = "tasks_fts")
@Fts4(contentEntity = TaskEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
data class TaskFtsEntity(val title: String, val description: String?)

/**
 * The full-text index of the notes — their titles, which is what the search
 * grammar searches a note by (F3).
 */
@Entity(tableName = "notes_fts")
@Fts4(contentEntity = NoteEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
data class NoteFtsEntity(val title: String)

/**
 * The full-text index of the projects — their names (F3).
 */
@Entity(tableName = "projects_fts")
@Fts4(contentEntity = ProjectEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
data class ProjectFtsEntity(val name: String)

/**
 * The full-text index of the tags — their names (F3).
 */
@Entity(tableName = "tags_fts")
@Fts4(contentEntity = TagEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
data class TagFtsEntity(val name: String)

package com.singularity.todo.core.sync

/**
 * How many ids one `IN (…)` list binds.
 *
 * SQLite caps the parameters of a statement — 999 on the oldest Android SQLite this
 * app runs on — and a profile wipe has as many ids as it has rows. The lists are
 * therefore cut into pieces of this size: fifty statements of five hundred ids are
 * cheaper than one statement of fifty thousand ids that the engine refuses to run.
 */
internal const val SQL_BIND_CHUNK = 500

/**
 * The ids in pieces no statement can refuse.
 *
 * The ids keep their order and the pieces keep it too, so a caller that cares about
 * the order — the outbox's oldest-first — still sees it.
 */
internal fun List<String>.inBindableChunks(): List<List<String>> = chunked(SQL_BIND_CHUNK)

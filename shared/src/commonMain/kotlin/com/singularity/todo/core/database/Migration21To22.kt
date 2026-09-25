package com.singularity.todo.core.database

import androidx.room3.migration.AutoMigrationSpec

/**
 * Migration from v21 to v22 — adds `kind` column to notes for templates and daily notes.
 *
 * - PLAIN: regular user notes
 * - DAILY: one note per day (journal/daily-log style)
 * - TEMPLATE: note templates used to create other notes
 *
 * @see com.singularity.todo.feature.notes.NoteKind
 */
class Migration21To22 : AutoMigrationSpec

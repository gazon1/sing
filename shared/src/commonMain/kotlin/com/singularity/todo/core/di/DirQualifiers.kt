package com.singularity.todo.core.di

import org.koin.core.qualifier.named

// Qualifiers for the directories the platform modules publish.
//
// Both the backup directory and the log directory used to be published as a
// bare `single<String>`. Two definitions of the same type without a qualifier
// share one Koin key, and Koin resolves a duplicate by overriding the first
// definition with the second — silently, because `allowOverride` defaults to
// true. The backup directory therefore resolved to the log directory, which is
// how a log archive ended up listed as a backup.
//
// Qualifying the two makes each one addressable on its own, so a third
// directory can never silently take either of them over.

/** The directory that holds backup archives. */
val BackupDirQualifier = named("backup_dir")

/** The directory that holds the rolling log files. */
val LogDirQualifier = named("log_dir")

/**
 * The directory that holds the attachment files.
 *
 * This one has a qualifier for the same reason as the two above, and with a sharper
 * edge: `AttachmentStorage` took its directory as a plain `String`, so Koin resolved it
 * by type — and the only unqualified `String` in the graph was a directory that belonged
 * to something else. The attachments were written into the log directory.
 */
val AttachmentDirQualifier = named("attachment_dir")

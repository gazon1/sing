package com.singularity.todo.core.backup

/**
 * The failures the backup layer reports.
 *
 * ## Why there is a root and three families
 *
 * The failures used to be one flat list, and the flatness cost something: a
 * caller that wanted to know whether the **container** could not be opened, or
 * whether the **payload inside it** was not what it should be, had to know every
 * case in the list to tell the two apart. The people writing that code were
 * guessing, and the guess was wrong in the direction that matters — a payload
 * that does not match its checksum was reported as a broken archive.
 *
 * So the failures are three families under one root:
 *
 * | Family | What it is about |
 * |---|---|
 * | [ArchiveEnvelopeError] | the container: the bytes, the version, the password |
 * | [BackupPayloadError] | what the container carried: the manifest, the schema, the checksum |
 * | [BackupStorageError] | the device's own files: a backup that is not there |
 *
 * A caller that catches [BackupError] still catches everything; a caller that
 * wants to be precise says which family it means.
 *
 * Every case here is a thing a person can act on: the message says what
 * happened, and the case says what can be done about it.
 */
sealed class BackupError(message: String, cause: Throwable? = null) : Exception(message, cause)

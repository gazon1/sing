package com.singularity.todo.core.sync

/**
 * Whether the server can accept attachment payloads. `false` until it can.
 *
 * ## Why this is a constant rather than a comment
 *
 * The attachment sync setting is per-scope and persists, and until recently
 * nothing at all consumed it. A stored preference with no reader is the exact
 * shape of the defect this change set exists to remove, so the reader is
 * explicit: the settings row reads *this*, and refuses to present itself as a
 * working switch while it is `false`.
 *
 * The evidence for `false` is not a guess. As of this writing the word
 * "attachment" does not occur anywhere in `core/sync`; `DocType` carries only
 * `Task`, `Note`, `Project`, `Tag`, `TagGroup` and `TimeEntry`, and its KDoc
 * pins that set to the Flutter `sync_core` contract, so the type list is fixed
 * by a backend this repository does not own. `AttachmentUploadService` has a
 * stub that returns a *local* path as if it were a remote one. Metadata and
 * binary are different transports: documents travel as JSON through
 * `sync_apply_ops`, file bytes need storage that does not exist yet.
 *
 * Turning this to `true` is therefore the whole of "stage 1 is live", and it is
 * a one-line change only because everything it gates was built to survive it.
 */
const val ATTACHMENTS_SYNC_TRANSPORT_AVAILABLE: Boolean = false

/**
 * Why a user cannot flip [SyncStateRepository.setAttachmentsSyncEnabled] yet.
 *
 * Shown as the row's subtitle so the control explains itself instead of
 * appearing broken.
 */
const val ATTACHMENTS_SYNC_UNAVAILABLE_REASON: String =
    "Not yet supported: the server has no attachment transport."

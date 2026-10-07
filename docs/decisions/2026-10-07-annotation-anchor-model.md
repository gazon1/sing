---
title: "An annotation stores a quote and an offset, and is marked stale rather than dropped"
date: 2026-10-07
status: accepted
tags: [attachments, data-model, sync]
---

# An annotation stores a quote and an offset, and is marked stale rather than dropped

## Context

An annotation is a note the user writes against a span of an attachment's text. The
span has to be stored, and the file it points into can change — the user edits it, a sync
replaces it, the attachment is re-downloaded and differs.

Character offsets alone are not enough. They are a coordinate in a document that has no
version attached to it: insert a paragraph at the top and every annotation now points at
the wrong words, with nothing in the data to notice.

The alternatives were:

| Option | Behaviour after an edit |
|---|---|
| Offsets only | Annotations silently drift onto unrelated text |
| **Quote + offsets** | Offsets checked first, quote as the fallback; three-way result reported |
| Line/paragraph numbers | Same instability as offsets, with extra machinery |
| Embedding the file with the annotation | The attachment and its note are guaranteed consistent, and the attachment can no longer be edited at all |

## Decision

Store **both** the range and the quoted text, and resolve in this order:

1. the stored offsets still hold the stored quote → `Exact`;
2. otherwise the quote is searched for → `QuoteFound` — the user recognised *words*, and
   the words are still there even though the numbers are not;
3. otherwise → `Stale`.

**Stale is reported, never resolved by guessing and never deleted.** The annotation stays
visible, stays editable and stays deletable.

Quote-first ordering is the load-bearing part. Anchoring on offsets when they happen to
address the quote is cheap and exact; the fallback exists for the case offsets cannot
handle, and making it the fallback rather than the first choice means an edit above the
annotation moves the note to the right place instead of nowhere.

A boolean "is this still valid" would force one of two bad behaviours on case 3: either
drop the annotation (the user's note disappears and nothing says why) or move it to
wherever the offsets now land (the note ends up on text it was never about). Both are
worse than saying so.

### Supporting constraints

- **`TextRange` validates in its constructor**: `start >= 0`, `start < end`, and a quote
  of at most 1000 characters. A malformed range is impossible to construct, so no reader
  has to defend against one.
- **An empty quote matches nothing.** It would otherwise match at offset 0 in any
  document, turning every such annotation into a falsely-exact one. It resolves to
  `Stale`, which is the honest answer for a note with nothing to anchor to.
- **Matching is exact** — no case folding, no whitespace tolerance. A fuzzy match would
  anchor a note to text the user did not select.
- **`update` does not move the range.** Editing a note and re-anchoring it are different
  operations; letting `update` shift offsets would silently re-point notes.

## Consequences

- **`resolveAnchor` is a pure function**, so the ordering is testable without a database
  and without a device. `AnnotationAnchorResolutionTest` pins all three outcomes and
  asserts all three are reachable.
- **Annotations are not a `SyncableEntity`.** A syncable entity must name a `DocType`,
  and the set of doc types is fixed server-side by `sync_core` — the `Attachment` member
  that does not exist yet is the blocker the attachment-sync stage 0 is waiting on. A
  separate doc type would also let an attachment arrive without the notes written
  against it, which reads exactly like data loss. So **an annotation follows its
  attachment**: when attachment sync lands, these rows ride in the same document.
- **Backups carry annotations.** They were absent, and that absence is silent — a
  restored archive imports cleanly, the attachment comes back, and the notes are gone
  with nothing in the log. The exporter, the payload, the manifest counts and the
  importer all carry them now, and the round trip is a test.
- **The restore path also now writes attachment rows.** The importer read attachment rows
  out of the archive and used them only to find the file in the zip; the row itself was
  never inserted. A restored install therefore had the file on disk and nothing pointing
  at it — and annotations restored against a missing parent would have been orphans.
- **The table has no foreign key to `attachments.id`.** A soft-deleted attachment keeps
  its row, so a cascade would not fire on the delete that actually happens, and a hard
  key would block restoring a file whose annotations still exist.

## The create form takes offsets, not a selection

Compose Multiplatform 1.12 exposes no supported way to read a text selection back to
the caller. `SelectionController` is present in
`androidx.compose.foundation.text.modifiers` but is not public API, and
`SelectionContainer` reports no observable range — so the "select some text, write a note
about it" gesture the feature is named after cannot be built on it today.

Rather than fake a selection, the panel asks for the two offsets and prefills the quote
from the file between them. The note is therefore always anchored against text the user
can see, and it is resolved by the same `resolveAnchor` every other path uses — a faked
selection range would produce a note whose anchor nobody chose, which is the exact
failure this ADR exists to prevent.

The cost is honest and worth stating: the user types `12` and `40` instead of dragging
across a sentence. When a selection API ships, the form is where it replaces those two
fields; nothing else in the model has to change, because the form has always produced a
`TextRange`.

## Links

- `core/attachments/annotation/TextRange.kt` — `TextRange`, `AnchorResolution`, `resolveAnchor`
- `core/attachments/annotation/AttachmentAnnotation.kt`
- `core/attachments/annotation/AttachmentAnnotationRepository.kt`
- `feature/attachments/annotation/AttachmentAnnotationViewModel.kt` — the panel's state, and the offsets form
- `feature/attachments/annotation/AttachmentAnnotationPanel.kt`
- `core/backup/BackupExporter.kt`, `BackupImporter.kt`, `BackupPayload.kt`
- ADR `2026-09-21-generic-user-scoped-repository` — why the repository takes no `userId`
- ADR `2026-10-07-attachment-viewer-routing`

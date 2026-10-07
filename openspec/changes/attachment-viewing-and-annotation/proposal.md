# attachment-viewing-and-annotation

## What

Close the attachment feature end to end: attach files to a task, open them from the task
detail, view images and text inside the app, hand anything else to the system, select a
fragment of a text attachment and keep a note about it, and show a short first-run tour of
the two agenda actions nobody can guess.

## Why

Attachments could be attached and then nothing could be done with them. The storage layer
existed and was exercised by tests; there was no route out of the task detail, no viewer,
and no way to say anything about a file's contents. That is the same defect this change
set starts from, one layer up from inert buttons: implemented, bound, and unreachable.

Three requirements force the shape:

- **A format with no built-in viewer must reach a system app**, and when there is none the
  user is told and offered a share sheet. Returning silently would look like the app had
  opened the file.
- **The attachment list must not truncate.** It showed the first five rows and no more,
  which reads as "these are all your files".
- **An annotation must survive the file changing.** Offsets alone are not stable, so an
  annotation carries the quote it was made from as well as its position.

## How

- `core/attachments/AttachmentViewerRoute.kt` — `ViewerTarget` / `ViewerRoute` /
  `routeFor`, the single place a MIME classification becomes a destination.
- `core/files/FileOpener.kt` — `FileOpener` port with an exhaustive `OpenOutcome`
  (`Opened` / `NoHandler`), Android `ACTION_VIEW` via `FileProvider` and JVM
  `java.awt.Desktop`. Both rethrow `CancellationException`.
- `feature/attachments/viewer/` — `ImageViewerScreen` (pinch zoom, saveable transform),
  `TextViewerScreen` (4 MiB cap checked *before* the read; markdown only for `.md`),
  `AttachmentViewerHost` (the only `when` over `ViewerTarget`), and a `NoHandler` screen
  offering Share.
- `AppDestination.AttachmentViewer` — a leaf of the single sealed root. `AppNavKey` is not
  touched; ADR `2026-09-29-single-sealed-navkey-root`.
- `core/attachments/annotation/` — `AttachmentAnnotationId`, `TextRange`,
  `AttachmentAnnotationRepository` (self-scoped, no `userId`), Room 41 → 42.
- Backup export/import round-trips annotations.
- `core/ui/onboarding/` — the spotlight tour: anchor registry, pure hole geometry, a state
  machine, and a version key in the existing settings store.

## Known limitations (documented, not fixed here)

- The tour's placement estimate assumes a capped card height; it may choose the roomier
  side when either would have fitted.
- The tour has not been run on a device. Its geometry and state machine are tested; its
  appearance is not verified.
- Compose Multiplatform's `SelectionContainer` does not report a selected range, so the
  annotation editor takes explicit offsets and a prefilled quote rather than pretending to
  capture a selection.

## References

- `docs/decisions/2026-10-07-attachment-viewer-routing.md`
- `docs/decisions/2026-10-07-annotation-anchor-model.md`
- `docs/decisions/2026-10-07-spotlight-onboarding-measures-its-targets.md`
- `docs/decisions/2026-10-07-the-empty-handler-gate-keyed-on-a-list-of-names.md`
# Tasks — attachment-viewing-and-annotation

**status:** proposed

---

## Phase 1 — OpenSpec artifacts

- [x] `proposal.md`
- [x] `specs/attachments-storage-and-linking/spec.md`
- [x] `specs/core-onboarding/spec.md`
- [x] `design.md`
- [x] `tasks.md`

## Phase 2 — Attaching

- [x] `FilePickPurpose.Attachment` + a title and a MIME filter derived from one table
- [x] `MimeTypes.supportedExtensions` as the single source, with no empty catch-all
- [x] `TaskDetailIntent.Domain.AddFileAttachment` → `TaskChildrenSlot.saveFileAttachment`
- [x] Attachment rows in `TaskDetailExtraSections`, with the silent `take(5)` removed
- [x] Add, delete and open
- [x] The create screen offers no attach control — a task has no id until it is saved

## Phase 3 — Routing and external open

- [x] `AttachmentViewerRoute.routeFor`; `ViewerTarget` / `ViewerRoute`
- [x] `FileCategory` and `classify*`, with SVG deliberately external and
      `application/octet-stream` falling back to the extension
- [x] `FileOpener` port with an exhaustive `OpenOutcome`
- [x] Android `ACTION_VIEW` via `FileProvider`; JVM `Desktop`
- [x] Registered in **four** places: both platform modules, the desktop test graph, and the
      test platform module — plus `PlatformParityTest` and `platform-seams.tsv`

## Phase 4 — In-app viewing

- [x] `ImageViewerScreen` with `ZoomTransform`, clamped 1..5, saveable
- [x] `TextViewerScreen`, 4 MiB cap checked **before** the read; markdown only for `.md`
- [x] `AttachmentViewerHost` — the only `when` over `ViewerTarget`
- [x] A `NoHandler` screen offering Share
- [x] `AppDestination.AttachmentViewer` on both platforms; `AppNavKey` untouched

## Phase 5 — Annotations

- [x] `AttachmentAnnotationId`, `TextRange` with its validation
- [x] `AttachmentAnnotationRepository` — self-scoped, no `userId`, no pass-through use case
- [x] Room 41 → 42 with `AutoMigration`
- [x] `AttachmentAnnotationViewModel : MviViewModel` — scope last, `addCloseable` in `init`,
      mandatory `crashReporter`, KDoc
- [x] Annotation UI reachable from the text viewer
- [x] Export and import round-trip, with a test

## Phase 6 — Onboarding

- [x] `SpotlightAnchorRegistry` + `Modifier.spotlightAnchor`
- [x] Pure `SpotlightHole` geometry — no `Path` outside the composable
- [x] `SpotlightTourStateMachine` — start, skip-missing, finish
- [x] Version key in the existing settings store, monotonic
- [x] Entry point on the agenda screen; replay from Settings → Interface → Help
- [x] Tests for the geometry, the placement and the machine

## Phase 7 — Verification

- [ ] The UI has not been run. No emulator or desktop session was used.

---
title: "In-app for what the app can render, out to the platform for everything else"
date: 2026-10-07
status: accepted
tags: [ui, attachments, navigation]
---

# In-app for what the app can render, out to the platform for everything else

## Context

The attachment store, the copy, the checksum and the URL path all existed. Nothing
rendered an attachment: the row in task details was a `Text` showing
`attachment.displayTitle`, and tapping it did nothing.

So there was a decision to make about *how* an attachment opens, and the obvious answer
— a WebView, or a "viewer" component per format — was the wrong one for reasons worth
recording before the code hard-codes them.

Considered:

| Option | What it gets right | What it costs |
|---|---|---|
| **In-app per format** | The common formats open instantly, offline, with the app's own chrome | Every format is a renderer the project must maintain forever |
| **Always out to the platform** | Zero rendering code; formats the app never heard of still work | A PDF opens in whatever the user last used, with no app chrome, and the app cannot annotate what it does not render |
| **WebView** | One renderer for a lot of text-ish formats | Runs third-party JS in the app's process on a file the user opened; no annotations; a class of bugs that belong to a browser |

## Decision

A **pure classifier** decides, and it is exhaustive:

```kotlin
enum class ViewerTarget { InAppImage, InAppText, External }
```

- images the project can decode → in-app, with pinch-zoom and pan;
- text and markdown → in-app, selectable, markdown formatted for `.md`;
- **everything else → the platform**, including SVG.

The classifier lives in `core/attachments/AttachmentViewerRoute.kt`, is a pure function
with no platform types, and is the *only* place the decision is made. `MimeTypes`
supplies the facts; the storage records the type; the viewer routes on it. Two
classifiers would be two chances to disagree about the same file.

The platform hand-off is a **port returning a sealed `OpenOutcome`**, not a `Boolean`:
`Opened` or `NoHandler`. "No installed app can open this" is an ordinary situation — a
phone with no PDF reader — and it gets its own screen with a share action. A `Boolean`
makes the two cases indistinguishable, and the caller's likely response to `false` is to
do nothing, which is how the user ends up on a button that appeared to work.

## Consequences

- **SVG is the interesting case.** `image/svg+xml` starts with `image/`, and routing it
  in-app would promise a renderer that does not exist — the user gets an error where a
  working file handler could have opened it. It goes out with everything else, and
  `MimeTypesClassificationTest` pins it so the `startsWith("image/")` shortcut cannot
  quietly swallow it later.
- **A missing MIME type falls back to the extension.** Attachments restored from a backup
  can carry a file name and no MIME type, and `application/octet-stream` is what a file
  picked on Android usually arrives as. Half of those are images.
- **The size limit is enforced before the read.** `FileSystem` returns whole files and
  nothing else, so "read then check the length" has already put the file on the heap.
  The test asserts `readBytes` was never called, because the failure otherwise is an
  out-of-memory kill with nothing to show the user.
- **The viewer is reached as `AppDestination.AttachmentViewer`**, not as its own route
  interface. It is pushed onto the app-level stack, whose entry provider is typed to
  `AppDestination`; a separate sealed route would have needed a wider stack type, and a
  route nothing renders is the exact defect this change set exists to remove. It is
  still a leaf of the single `AppNavKey` root per ADR
  `2026-09-29-single-sealed-navkey-root`, and `familyOf` classifies it — the compiler
  enforced that, which is the whole reason that function is exhaustive.
- **Pinch-zoom is the first gesture surface here.** The zoom arithmetic is extracted into
  `ZoomTransform` so the clamping and the focal-point maths are testable without a
  device. The first version multiplied by the new scale instead of by the ratio, which
  is correct only while scale == 1 — so the first pinch looked right and every pinch
  after it slid the image out from under the fingers. That reads as "the gesture is
  imprecise" rather than as a bug, which is why it is now pinned by a repeated-pinch
  test.

## Links

- `core/attachments/AttachmentViewerRoute.kt`
- `core/files/MimeTypes.kt` (`FileCategory`, `classifyMime`)
- `core/files/FileOpener.kt`, `AndroidFileOpener.kt`, `JvmFileOpener.kt`
- `feature/attachments/viewer/` — host, image viewer, text viewer
- `core/database/AppDatabase.kt` — `SCHEMA_VERSION`
- ADR `2026-09-29-single-sealed-navkey-root`
- Gander (MIT), `WebViewFloor.kt` / `FileKind.kt` — prior art for the pure-classifier
  split between in-app and platform. No code copied; see
  `config/legal/provenance-registry.tsv`.

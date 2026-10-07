# Design — attachment-viewing-and-annotation

## The shape of the problem

Three features, one storage layer, and a rule that runs through all of them: **a control
must not look operable when it is not.** The temptation at every step was to add the
plumbing and let the UI catch up. Every time, the plumbing alone was the defect.

## Routing

One table decides everything: `MimeTypes.classify` maps an extension or a MIME type to a
`FileCategory`, and `AttachmentViewerRoute.routeFor` turns that into a `ViewerTarget`.
Two decisions there are worth stating:

- **SVG is `External`, not `Image`.** The image viewer decodes bitmaps; handing it a
  vector would produce an empty screen rather than an error the user could read.
- **`application/octet-stream` and an unrecognised MIME both fall back to the extension.**
  Pickers report `octet-stream` routinely for files whose type they simply do not know,
  and routing on the reported type alone would send every one of them to a system app that
  cannot preview it.

`OpenOutcome` is a sealed interface with `Opened` and `NoHandler`, not a `Boolean`. A
boolean cannot be rendered: the caller would have to invent a third answer for "it did
nothing", which is what a silent `return` is. Making `NoHandler` a value the caller must
handle is what forces the explanation and the Share action to exist.

`AppDestination.AttachmentViewer` rather than a second sealed interface for routes. Entry
providers are typed `AppDestination`, so a parallel hierarchy would have meant widening the
back-stack type — and, more to the point, a route that renders nothing is the exact defect
this change set exists to remove.

## The size limit

`MAX_IN_APP_TEXT_BYTES` is checked against the file's metadata **before** the read, and
there is a test asserting `readBytes` was not called. `FileSystem` can only read whole
files, so "check first" is the only place the limit can be enforced. A limit checked after
the read is a limit that has already spent the memory it was meant to protect.

## Annotation anchoring

Offsets are unstable; a quote is not. Each annotation stores the range *and* the quote, and
resolution is: exact quote, then offsets, then stale. Stale does not mean deleted — the
annotation stays listed and editable, because an automatic deletion is indistinguishable
from data loss. See ADR `2026-10-07-annotation-anchor-model.md`.

Annotations are deliberately **not** a `SyncableEntity`: the document-type set is fixed by
the server, and a separate type could deliver an attachment without its notes. They ride
along with the attachment instead.

## Onboarding

The geometry is numbers, not a `Path`. A `Path` cannot be constructed outside a
composition without loading Skia, so a `Path`-returning helper would have made every
decision it made — does the circle cover a wide button, does the radius stay valid, does
the morph pass through a degenerate frame — untestable. The composable builds the path
from the numbers.

The first `spotlightHolePath` did exactly that and could not be tested. That is recorded in
ADR `2026-10-07-spotlight-onboarding-measures-its-targets.md` rather than quietly fixed,
because "why is this a `Rect` and not a `Path`" is the question the next person will ask.

A step whose target is not laid out is **skipped**, not waited on: a tour blocked on a
missing button is a scrim over the app with no way out.

## What is not verified

The UI has not been run. No emulator and no desktop session was used at any point. The
geometry, the state machine, the routing, the classification and the zoom are covered by
tests; the composables' appearance on a device is not.

That constraint is the reason the `FileOpener` port, the classification and the state
machines were built as separate, testable pieces rather than inside the screens. It is not
a substitute for having looked at them.
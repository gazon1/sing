# a-save-that-wrote-the-note-but-not-its-links-is-not-a-save

Backlog entry: `note-editor-drops-outgoing-links`. Found by the dropped-`Result`
audit of 2026-10-07; no issue number yet.

## What

`NoteEditor.persist` unwraps the two content writes — `createWithContent(...).getOrThrow()`
and `updateContent(...).getOrThrow()` — and then writes the note's outgoing links
without unwrapping anything. `NotesRepository.setOutgoingLinks` returns
`Result<Unit>`; the failure goes nowhere.

The fix is `.getOrThrow()`, matching the two lines above it.

## Why

The line directly above the dropped one says what that line is for:

> Extract outgoing links from the rendered HTML and persist them.
> This is the write path for the backlinks feature: without this, `outgoing_links`
> is never written and `[[note://...]]` / `[[task://...]]` links are dead.

So a save could report success, the user saw their note saved, and every link in it
was dead — with a comment two lines up asserting that this call is what keeps them
alive, and two sibling calls on the same path already unwrapped. The file was
internally inconsistent, which is the strongest form of the claim: the convention was
present in the same function and simply not applied to the third write.

**Not a sync divergence, and the audit got this wrong.** The dropped-`Result` audit
filed it as a sync finding. `SyncedWriteEnqueuesTest` records outgoing links as *not
synced* — no `DocType` describes them and the server has no table for them. The loss
is local and total for that column, not a divergence between devices. That makes it
cheaper to fix than the audit implied, and it changes what the test should assert: a
broken backlinks feature, not an outbox that never filled.

## How

One `.getOrThrow()`. The existing `catch (e: Exception)` in the same function already
turns it into `Either.Left(AppError.Persistence(...))`, which is the shape the user
already sees from the two writes above, and the base `save()` already renders as a
visible error.

No new error channel, no new state, no output-DTO change.

**Invariant:** the control asserts through `save()`, not through `persist`. `persist`
is `protected` and `save()` is deliberately not `open` — `2026-09-30-draft-save-failure-and-testtag-honesty.md`
put the failure handling in the base body precisely so a subclass could not bypass it.
A control reaching into `persist` would test the one path the design forbids.

A second test covers the success path, so an unwrap on the wrong call — or one that
fired on every save — cannot pass.
# Tasks — the-editor-and-the-rewrite-from-outside

**status:** accepted

---

## Phase 1 — The revision token

- [x] `EditorState.Editing` carries `revision: Long = 0`
- [x] `NoteEditor` watches the note; a rewrite re-seeds the title and the body
      and bumps the revision
- [x] The re-seed happens only when the content changed **and** the draft is
      clean — the user's unsaved edits win
- [x] The editor's own save echo is not a rewrite (content comparison)
- [x] The watcher is cancelled when the editor closes
- [x] `EditorSession.replaceContent(html)` moves the baseline, so the next save
      compares against what is on screen
- [x] The title bridge receives the revision, so the field knows a replacement
      is not a keystroke

## Phase 2 — The rewrite contract

- [x] The registry of bridged field sites and the answer to "who may rewrite
      this field?" for each one
- [x] The convention test that guards the field shapes guards the rewrite
      answer too
- [x] The skill documents the contract next to the shape rules, with the
      registry and the trap

## Phase 3 — The dead link map

- [x] `insertedLinks`, `recordLink` and `findLinkAt` removed from
      `EditorSession` — the map had no callers and its own documentation says
      the map is the wrong mechanism
- [x] The two `session?.recordLink(url)` call sites removed

## Phase 4 — The flows

- [x] The notes flows carry the `smoke` tag, so the e2e job's smoke selection
      includes them

## Phase 5 — The tests

- [x] A note rewritten from outside replaces the editor's content
- [x] The user's unsaved edits are not replaced by a rewrite from outside
- [x] The editor's own save is not counted as a rewrite from outside
- [x] The rewrite contract test lists every bridged field site and its answer

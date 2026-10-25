# Tasks — the-note-body-has-the-same-contract-as-the-title

**status:** accepted

---

## Phase 1 — The value

- [x] `EditorState.Editing.html` is a `Revisioned<String>`
- [x] `bodySource` is its `OwnedText`, the same shape as `titleSource`
- [x] `withBody` types the body, the way `withTitle` types the title

## Phase 2 — The writes

- [x] A keystroke is a `typed` — the count does not move
- [x] A rewrite is a `replacedIfChangedBy` — the count moves only when the value changed
- [x] A rewrite moves the count of the field that changed, and only that field's
- [x] `Revisioned.replacedIfChangedBy` says it in one place

## Phase 3 — The field reads it

- [x] The editor session re-seeds the rich editor when the *body's* count moves
- [x] The session no longer re-seeds the body when the title alone was rewritten

## Phase 4 — The contract and the documentation follow

- [x] The rewrite table gains a row for the body
- [x] The skill says that the body has the same contract as the title
- [x] The decision is recorded

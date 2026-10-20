# Tasks — the-count-travels-with-the-value

**status:** accepted

---

## Phase 1 — The type

- [x] `Revisioned<T>` lives in `core.ui.textfields`, next to the source types
- [x] The constructor is private: the only ways to make one are `initial`, `typed` and
      `replacedBy`
- [x] `typed` changes the value and leaves the count where it was
- [x] `replacedBy` changes the value and moves the count
- [x] `equals` and `hashCode` are written by hand — the drafts that hold it are data
      classes, and their equality is part of what the tests assert

## Phase 2 — The owners

- [x] The task draft's title and description hold a `Revisioned<String>`
- [x] The project draft's name and description hold a `Revisioned<String>`
- [x] The note editor's title holds a `Revisioned<String>`
- [x] The drafts' `revision` field is gone
- [x] A keystroke is a `typed`; a replacement from outside is a `replacedBy`
- [x] The echo of the owner's own save changes nothing

## Phase 3 — The contract follows

- [x] The counter is declared by a `Revisioned` property, or by a plain count of the
      upstream's writes
- [x] The count advances through a call to `replacedBy`
- [x] The skill explains the two kinds of counter

## Phase 4 — The evidence

- [x] `RevisionedTest` pins the two operations and the equality
- [x] The draft tests read the value and the count where they live
- [x] The rewrite contract test reads the counter's new shape

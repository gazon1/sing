# Tasks — the-genui-context-hands-out-the-source

**status:** accepted

---

## Phase 1 — The context hands out the source

- [x] `DefaultDataContext.textSource(path, fallback)` returns a `BoundTextSource` — the
      source now and the stream of what it becomes
- [x] The context reads the value at the path and the server's count of writes
- [x] The source is built once per path and kept, so a recomposition does not restart the
      observation
- [x] `SurfaceController.currentServerRevision` is the reading the flow cannot give

## Phase 2 — The renderer stops building the source

- [x] The renderer reads the context's source and hands it to the bridge
- [x] The renderer no longer constructs `OwnedText`
- [x] The value and the count arrive together, in one source

## Phase 3 — The exception is gone

- [x] The rewrite table names the data context as the owner of the GenUI field
- [x] The contract test loses its `buildsSource` flag
- [x] The owner check accepts an owner that hands the source out from a function
- [x] The skill no longer names an exception

## Phase 4 — The behaviour is pinned

- [x] A server rewrite that lands on a value the field had already sent replaces the
      field's text
- [x] The test fails when the count is frozen — a rewrite is then an echo, and the field
      keeps the text the user typed

## Phase 5 — The documentation follows

- [x] The decision is recorded

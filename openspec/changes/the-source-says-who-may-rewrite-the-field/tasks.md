# Tasks — the-source-says-who-may-rewrite-the-field

**status:** accepted

---

## Phase 1 — The types

- [x] `TextSource` is sealed, with `StaticText` and `OwnedText` as its two
      permitted shapes — a value is one kind or the other, never both
- [x] The hierarchy is `@Immutable`, so the state that holds a source stays
      stable for Compose
- [x] The count of replacements is a property of the owned source, not a
      parameter

## Phase 2 — The bridge

- [x] `rememberBridgedTextFieldState` takes a source and has no count argument
- [x] It has one overload per kind of source
- [x] It has an internal overload for the base type, whose dispatch is
      exhaustive — a third shape cannot be added without the bridge deciding
      what it means

## Phase 3 — The owners build the sources

- [x] The eight static sites: the chat input, the annotation draft, the notes
      query, the agenda name, the project editor's name and description, the
      projects query, the search query, the AI provider's settings
- [x] The task detail's title and description — an owned source carrying the
      draft's count
- [x] The task create screen's title and description — a static source, because
      the create draft is seeded once and afterwards holds what the user typed
- [x] The project detail's name and description — an owned source carrying the
      draft's count
- [x] The note editor's title — an owned source carrying the editor's count
- [x] The GenUI renderer builds its source at the boundary with the server; the
      count still comes from the server's side

## Phase 4 — The fields pass them through

- [x] No call site passes a text and a count any more
- [x] No field builds a source for itself
- [x] The task editor, which serves two upstreams, takes the base type

## Phase 5 — The contract is checked

- [x] The registry names the owner of each site's source
- [x] The registry names the file that declares the count and the file that
      advances it
- [x] The check fails when the owner declares another kind of source
- [x] The check fails when the count is not declared, or never advances
- [x] The check fails when a field site builds its own source
- [x] The check fails when the bridge's public API takes a count
- [x] The check fails when an owned row names no counter, or no file that
      advances it
- [x] The skill's rewrite table carries the kind of source, the counter and
      the file that advances it, and the check compares all three with the
      registry

## Phase 6 — The behaviour is pinned

- [x] The desktop bridge test drives an owned upstream and shows the rewrite
      landing on a value the field produced
- [x] The static upstream's counterpart — the same rewrite, dropped — stays
      pinned

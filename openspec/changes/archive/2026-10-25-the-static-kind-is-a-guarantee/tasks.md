# Tasks — the-static-kind-is-a-guarantee

**status:** accepted

---

## Phase 1 — The type

- [x] `FieldOwnedText` lives in `core.ui.textfields`, next to the source types
- [x] The constructor is private: the only ways to obtain a value are `seeded`,
      `echoed`, `cleared` and `empty`
- [x] `seeded` is the upstream's load, `echoed` the field's own text, `cleared` the
      upstream's emptying
- [x] `equals` and `hashCode` are written by hand

## Phase 2 — The owners

- [x] The search query holds a `FieldOwnedText`
- [x] The notes' search query holds one
- [x] The projects' search query holds one
- [x] The chat input holds one
- [x] The saved agenda's name holds one
- [x] The annotation draft holds one
- [x] The project editor's name and description hold one
- [x] The AI provider settings hold one
- [x] The create form's title and description hold one
- [x] Every source is built from the typed value: `StaticText(value.text)`

## Phase 3 — The contract checks it

- [x] A static row's owner must declare a `FieldOwnedText`
- [x] The check reads the owner's declarations, not its text

## Phase 4 — The documentation follows

- [x] The skill says where the static kind's promise lives
- [x] The decision is recorded

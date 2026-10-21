# Tasks — the-genui-count-travels-with-the-value

**status:** accepted

---

## Phase 1 — The count moves into the data

- [x] The data model holds the document and the counts in one state
- [x] The count is per path
- [x] The field's own write leaves the count where it is
- [x] The write that comes from outside moves it

## Phase 2 — The controller holds no count

- [x] The counter beside the surfaces is gone
- [x] A closed surface leaves no count behind
- [x] A reset leaves no count behind

## Phase 3 — The context reads the pair

- [x] The context reads the value and the count together
- [x] The source is built from the pair, not from two places

## Phase 4 — The proof

- [x] `shared/`: the count moves only for the path that was rewritten
- [x] `shared/`: the field's own write does not move it
- [x] `shared/`: a surface that comes back carries no count
- [x] The proof fails without the fix
- [x] The decision is recorded

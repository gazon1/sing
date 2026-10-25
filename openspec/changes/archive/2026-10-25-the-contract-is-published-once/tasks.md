# Tasks — the-contract-is-published-once

**status:** accepted

---

## Phase 1 — The published table carries everything the check needs

- [x] The rewrite table gains an **Owner** column — the file that builds the source
- [x] Every row names its owner; the GenUI renderer names itself, because its owner is a
      server the renderer does not own
- [x] The skill explains the new column next to the table

## Phase 2 — The tables are read by their header

- [x] A reader finds a table by its header cells, not by "the first table that looks
      like it"
- [x] A table whose header changed fails loudly instead of reading nothing
- [x] A row of another table is never read as a row of these

## Phase 3 — The shape convention test holds no registry

- [x] The classification *is* the skill's inventory table
- [x] A file that owns a field and is missing from the table fails
- [x] A row whose shape does not match the helper the file imports fails
- [x] A row whose file no longer owns a field fails as a stale row

## Phase 4 — The rewrite contract test holds no registry

- [x] The rows *are* the skill's rewrite table
- [x] A file that owns a bridged field and is missing from the table fails
- [x] A row naming a file that no longer owns a bridged field fails as a stale row
- [x] The owner, the counter and the advancing file are read from the table
- [x] The "the site builds its own source" exception is read off the row — the site that
      names itself as its owner — instead of a flag in the test

## Phase 5 — The documentation follows

- [x] The skill says that its tables *are* the registry
- [x] The decision is recorded

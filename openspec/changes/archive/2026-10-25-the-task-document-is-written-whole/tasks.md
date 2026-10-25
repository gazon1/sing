# Tasks — the task document is written whole

## Phase 0 — decisions before code

- [x] **shared/**: the decision as an ADR — a task document is written whole,
      by one writer.
      Test: the repository's ADR status gate passes.
- [x] **shared/**: the OpenSpec change that states the behaviour in scenarios.
      Test: `openspec validate --all --json --strict` passes.

## Phase 1 — the writer

- [x] **shared/**: the pull path applies the whole document — the row, the
      tags, the links the description implies, and the blocking dependencies —
      in one unit of work.
      Test: `TaskDocumentWriteTest` — the pull path writes the links, the tags
      and the dependencies of the row that arrived.

- [x] **shared/**: the row mapper takes the links as a parameter: it cannot
      derive them, and a mapper that left the column out would let `@Upsert`
      reset it.
      Test: `TaskRowWriterTest` — every column of the row has a writer.

- [x] **shared/**: the pull path applies the document and nothing else; a row
      of another profile is stored as it is, with the links it carried.
      Test: `TaskDocumentWriteTest` — another profile's row keeps its links.

## Phase 2 — the dependencies

- [x] **shared/**: the blocking edges of the document replace the local ones;
      the edges of other kinds are left alone.
      Test: `TaskDocumentWriteTest` — the dependency the document dropped is
      gone; the edge the document never spoke about survives.

- [x] **shared/**: an edge that would close a cycle is refused on its own, and
      the rest of the document still applies.
      Test: the local write path's cycle guard is unchanged; the pull path
      uses the same guard per edge.

## Phase 3 — the parts that are not documents

- [x] **shared/**: `update()` does not write the tags, and says why in the
      place where it could go wrong.
      Test: `TaskDocumentWriteTest` — an edit leaves the tags of the task
      alone.

- [x] **shared/**: the gate that says every column of the task row has a
      writer.
      Test: `TaskRowWriterTest` — a column nobody writes has to be declared.

## Phase 4 — the record

- [x] **shared/**: the issue record for the change, and the review verdict
      file the gate asks for.
      Test: `check-issue-registry.py` and the review-verdict gate pass.

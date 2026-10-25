# the-project-is-read-against-the-row

## 1. The claim travels with the write

- [x] 1.1 `ProjectDocument`: the project and the claim about the inheritance —
      `null` when the document says nothing about it, a set when the document
      claims it.
- [x] 1.2 `ProjectsRepository.upsert` takes the document, and the repository
      replaces the table of inherited groups only when there is a claim.
- [x] 1.3 The fake says what production says: a document that makes no claim
      leaves the groups the project has.

## 2. The project is read against the row that is already here

- [x] 2.1 The writer reads the project row and merges it, exactly as it does for
      every other type.
- [x] 2.2 The key of the inheritance is filtered out of the row that is read: the
      row cannot see the table, so its empty set is an artefact of the reading
      and not a claim.
- [x] 2.3 The writer decides the claim from the document: the key is there, or it
      is not.

## 3. The tests hold it

- [x] 3.1 `ProjectDocumentWriteTest`: the row keeps its description, a document
      that omits the name is a partial write, the groups stay, a claim of an
      empty set takes them away, a claim of another set replaces them, and the
      row of another profile is not filled from this profile's row.
- [x] 3.2 Each defect test fails when the mechanism it covers is removed: the
      two row tests fail without the merge, and the inheritance test fails
      without the claim. The rest are guards.

## 4. The documents say what was decided

- [x] 4.1 ADR `2026-10-29-the-project-is-read-against-the-row`.
- [x] 4.2 The proposal and the spec of this change.
- [x] 4.3 The requirement of the change before this one is modified by this
      change's delta: the project is no longer excluded from the rule, and the
      requirement of the inheritance is added beside it. The two are archived in
      that order — a requirement has to be in the spec before it can be
      modified.

# the-row-keeps-what-the-document-does-not-say-for-every-type

## 1. The merge speaks JSON

- [x] 1.1 `DocumentMerge.kt`: the merge takes the row as the JSON the model
      writes and compares it with the document key by key. The document wins
      wherever it speaks; where it says nothing, the row keeps saying what it
      says.
- [x] 1.2 The merge no longer knows about types: it does not filter the parts
      that live outside the row, because which keys are parts is per-type
      knowledge and the caller's.

## 2. Every type reads the row that is already here

- [x] 2.1 `SyncDocumentWriter` has one path: it reads the row the document is
      about, merges it, and decodes the result. The path is per type only in
      what it reads and in how it decodes.
- [x] 2.2 The note, the tag, the tag group and the time entry are read against
      the row that is already here, exactly as the task is.
- [x] 2.3 The time tracking port grows the read it did not have, scoped to its
      owner — that port's other verbs are caller-trust, and a read that reached
      across profiles would fill a document with another profile's values.
- [x] 2.4 The writer's DI binding hands the writer the profile this device is
      signed in to, and the check "is this document of this profile" is one
      answer for every type.

## 3. The doubles say what production says

- [x] 3.1 The tag group fake scopes its read the way production does. It did
      not, and a double that reads across profiles would let a test pass
      against the fake and fail against production.
- [x] 3.2 The fake in-memory time entry DAO answers the new read the way the
      real DAO does.

## 4. The tests hold it

- [x] 4.1 `DocumentMergeTest`: the rule itself — the absences keep the row's
      values, a claim wins, a document about an unknown row is the document,
      and a row that writes a field under another name does not fill it.
- [x] 4.2 `DocumentMergeForEveryTypeTest`: the path that applies a document,
      per type — a note keeps its body and its color, a tag keeps its group, a
      time entry keeps its note, a tag group document that omits the name is
      read against the row that is here, and the document of another profile is
      not filled from this profile's row.
- [x] 4.3 The tests that carry a missing key fail without the merge: the
      default is written over the row, and that is the defect.

## 5. The documents say what was decided

- [x] 5.1 ADR `2026-10-29-the-row-keeps-what-the-document-does-not-say-for-every-type`.
- [x] 5.2 The proposal and the spec of this change.
- [x] 5.3 The links of the sibling decision are updated: the merge and its test
      were renamed when the merge was generalised.

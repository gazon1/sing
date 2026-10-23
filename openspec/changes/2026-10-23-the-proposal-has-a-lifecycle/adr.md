# ADR Review Manifest — the proposal has a lifecycle, and the vocabulary is typed

The sequence numbers below are the ordinal of the ADR inside this change; the
corpus in `docs/decisions/` is not numbered.

## ADRs Created

| Sequence | Slug | Title | Status | Notes |
|----------|------|-------|--------|-------|
| 1 | the-value-is-the-field | The value is the field, and the kind carries its own behaviour | accepted | The variant is the field; one reader per entity; the kind owns its summary, its fingerprint and its editable surface |
| 2 | the-proposal-can-be-changed-before-it-is-applied | A proposal can be changed before it is applied, and a refusal shows its reason | accepted | The edit is a compare-and-set on `Pending`; a failed apply keeps its reason and stays pending |
| 3 | the-review-scenarios-live-in-the-chat | The review scenarios live in the chat, and the prompt stops promising otherwise | accepted | The prompt stops claiming the app is built around the four situations; moving them into the app is separate work |
| 4 | the-invariant-is-a-test-not-a-file | The invariant is a test, not a file | accepted | The file registry and its gate are gone; the invariant is a test that reads the behaviour and the source |
| 5 | a-cancelled-write-takes-back-what-it-wrote | A cancelled write takes back what it wrote | accepted | The compensation lives in a `finally` under `NonCancellable`, so it runs on the cancellation and on the failure; the rows that could not be taken back are reported to the crash reporter |
| 6 | a-value-that-does-not-read-keeps-the-change | A value that does not read keeps the change | accepted | The unreadable value is a variant of the dictionary that carries the reason; the plan refuses it before the claim, so the change is neither decided nor lost |
| 7 | the-difference-between-two-surfaces-is-a-value | The difference between two surfaces is a value, not a branch | accepted | One coordinator for the five operations; the wording, the failure store and the error sink are handed to it as values |

## ADRs Reviewed (No Action)

| Sequence | Slug | Title | Status | Notes |
|----------|------|-------|--------|-------|
| — | 2026-10-22-the-write-path-asks-first | The write path asks first | accepted | The shape of the write path is unchanged; this change finishes the last writer and gives the proposal a lifecycle |
| — | 2026-10-22-the-write-path-has-one-shape | The write path has one shape | accepted | The tools still build proposals the same way; only `create_tag` moved onto it |

## Decisions That Did Not Meet the Bar

- **Moving the four review situations into the app.** The situations are the
  model's, and the app has no screen for any of them. Building one is a product
  decision with its own spec, not a consequence of this change.
- **Editing a collection.** `SetTaskTags`, `AddTags`, `AddChecklistItems`,
  `AddSubtasks` and `AddTimeEntries` carry lists of text; a single field the
  user types cannot honestly express them, so they are not editable.
- **Editing a project.** `TaskValue.Project` holds an id. An id is not something
  a person types, and pretending otherwise would put a wrong value into the task.
- **Changing the stored format.** The kind could have been stored as the sealed
  serializer sees it. That is a migration of every row for a gain nobody asked
  for; the codec keeps the flat `field`/`value` payload and the round-trip test
  holds it.

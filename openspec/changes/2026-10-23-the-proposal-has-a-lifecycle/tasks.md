# Tasks — the proposal has a lifecycle

**status:** accepted

---

## Phase 1 — The last writer

- [x] `create_tag` proposes: the proposal names the tag that will exist, and
      the answer says `proposalCreated`
- [x] The tag kinds enter the vocabulary: `CreateTag`, `SetTagField`
- [x] The file registry `config/docs/ai-direct-writes.txt` is gone
- [x] The gate `scripts/check-ai-write-tools.py` is gone
- [x] The invariant is a test: the behaviour (nothing is written, a proposal is
      stored) and the source (no tool reaches a write repository)
- [x] The surface contract version is 3 — `create_tag` lost the `tagId` field

## Phase 2 — The typed vocabulary

- [x] `TaskValue`, `NoteValue`, `ProjectValue`, `TagValue` — the variant is the
      field
- [x] One reader per entity: `TaskValue.read(field, text)` and its siblings
- [x] The stored format does not change: the codec still writes `field` and
      `value`, so old rows keep reading
- [x] The summaries carry the value, so the user reads what will change
- [x] The operation vocabulary reads through the same reader, so an operation
      cannot accept a value the proposal would later refuse

## Phase 3 — The kind carries its behaviour

- [x] The summary, the fingerprint and the editable surface live in the kind
- [x] `ProposalItemKind.editableProposal()` — what the user may change, and
      how the changed value is read again
- [x] The values moved to `ProposalValues.kt`, so the kind file is the kind

## Phase 4 — The scenarios stay in the chat

- [x] The prompt no longer says the app is built around the four situations
- [x] The decision is recorded in an ADR

## Phase 5 — The lifecycle

- [x] The proposal can be changed before it is applied
- [x] The change is stored with the summary and the fingerprint that belong to
      the value
- [x] The user's own refusal stays on the card, with the reason
- [x] A change that could not be applied keeps its reason next to it
- [x] The proposals screen reads the error events it was never reading
- [x] A batch failure names the change it belongs to
- [x] The card is one card, so the change works the same on all three surfaces:
      the proposals screen, the note editor and the task detail

## Phase 6 — The checks

- [x] The round-trip test covers every kind and every value
- [x] The samples are checked against the sealed serializer's list of variants,
      so a kind without a sample fails loudly
- [x] `ProposalOperationTest` — the operation vocabulary had no coverage at all
- [x] The codec is in its own file — the row mappers and the codec are two
      things, and only one of them is the thing the compiler cannot help with
- [x] `scripts/lint/normalize-when-blank-lines.py` — the blank-line rule for
      `when`, applied by a script instead of re-derived from the bytecode
- [x] `docs/architecture/proposals.md` — where the vocabulary lives and what the
      compiler does not protect
- [x] `docs/architecture/quality.md` — the thresholds and the rules that are
      not obvious
- [x] `AGENTS.md` — the pointers, and the background-task rule with a
      completion criterion

## Phase 7 — What the review found

The first review of this change returned `fail`. These are its findings and what
answered them.

- [x] A dispatch that fails leaves the claim behind: the item was recorded as
      decided although nothing was written. The claim is released now, and the
      change goes back to waiting with its reason.
- [x] An empty value could not clear a date — the tool's own description said it
      could, so the documented behaviour was an error. The date and the time hold
      the absence of a value, and an empty string reads as it.
- [x] The note editor emitted its errors and nobody read them; the note and the
      task detail now carry the reason per change, next to the change it belongs to.
- [x] The plan was built from an earlier read, not from the row the claim read
      back — an edit that landed in between would have been overwritten.
- [x] Losing the race returned a pending item as if it had succeeded.
- [x] The ADR claimed the fingerprint never changes with the value; true for a
      field, false for a creation, and the ADR now says so.

The second review returned `fail` as well, and it was right twice:

- [x] The rebuild of a stale plan sat outside the guarded region, so a failure
      there left the item confirmed although nothing was written. Everything that
      can fail after the claim is inside one block now.
- [x] A change that writes several rows could leave the first rows behind when a
      later row failed — and the change goes back to waiting, so the next attempt
      would have written them twice. The dispatch takes back what it wrote.
- [x] The release that matched no row was reported as a success.
- [x] A batch where some changes failed threw, and a normal outcome reached the
      crash reporter.
- [x] The tool's comment said `null` clears the date; only an empty string does.
- [x] A branch that nothing could reach was left in the proposals screen.

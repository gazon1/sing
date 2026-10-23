# The proposal loses nothing

Issue: — · Related: `docs/decisions/2026-10-23-the-id-is-known-before-the-row-is-written.md`,
`docs/decisions/2026-10-23-a-change-this-build-cannot-read-stays-on-the-card.md`,
`docs/decisions/2026-10-23-the-lifecycle-belongs-to-the-domain.md`,
`docs/decisions/2026-10-23-the-stored-change-is-the-models-own-shape.md`,
`docs/decisions/2026-10-23-the-harness-gives-the-scope.md`

Status: **accepted**
Tags: **ai write-path proposals contract dispatch codec tests**

## What

The lifecycle work left eight follow-ups, and they are one shape: **a change the
user cannot see is a change nobody decided about.** Each of them is a place where
the app can lose a change, or leave the user with a question it cannot answer.

1. **A write that committed and then lost its result left its row behind.** The
   id of a row was the *result* of the write, so the write that was cut short —
   the one the take-back exists for — was exactly the write whose id never
   arrived. The take-back had nothing to address.

2. **A kind this build does not know was dropped.** The codec decoded a kind it
   was not taught to read to *nothing*, the row was dropped, and the proposal
   lost a change. The vocabulary grew from 15 kinds to 19 while this was being
   worked on, so it was not a hypothetical.

3. **Three surfaces decided what a proposal's state meant.** The card said
   "Turned down", the review screen filtered by `Pending`, the note editor built
   a `Pending` item — three places to keep in step, and nothing to keep them in
   step.

4. **The dispatcher knew what each kind was going to write.** It had a `when`
   over the plans, and a second `when` over the *values* of a note — so the
   dispatcher knew both which field a note sets and what to write into it.

5. **The reasons a change failed lived three ways.** Two surfaces kept them in
   their own state — where a rebuild lost them — and the third kept them in a
   flow of its own. One reason, three implementations.

6. **The stored shape was written by hand.** The `kind_json` column was a flat
   payload the codec wrote field by field, and a field the model renamed was a
   field the codec kept writing under the old name — or stopped writing, and the
   value read back as something else.

7. **The test harness made the test choose the scope.** The harness handed over
   two scopes and let the test pick the one the ViewModel would live in. The
   right choice depends on what the ViewModel collects, and a test that picks
   the wrong one does not fail where the mistake is.

8. **The take-back's travel was assumed.** A taken-back subtask is archived, and
   an archive travels — but nothing said so, and nothing tested it.

## The change in behaviour

- **The id of a row is named before the row is written.** The write path knows
  the id of every row it is about to create, so a write that is cut short after
  the database has committed still leaves a row the take-back can address.
- **The take-back tolerates a row that is not there.** A write that failed
  before it created the row leaves nothing to take back, and "the row is not
  there" is what the take-back wanted.
- **A kind this build cannot read stays on the card.** It becomes a change that
  carries the reason it cannot be applied, and the user can still turn it down.
- **The lifecycle belongs to the domain.** `ProposalItem.outcomeLabel` says what
  a decided change reads as, `ProposalItem.pending(...)` says what a change that
  was proposed is, `AiProposal.pendingItems` says what is still waiting — and a
  surface may not name a status at all.
- **The wording of a decision lives in one place.** The three surfaces' styles
  are values that belong together, and they are together now.
- **The reasons live in one store.** One implementation over a flow, and the
  surfaces mirror it.
- **The stored change is the model's own shape.** The sealed format is written
  by the model's own serializer; the flat format the builds before this one
  wrote is still read.
- **The harness gives the scope.** The test says what kind of ViewModel it is
  testing, and the harness builds the scope that kind needs.

## Impact

- `ProposalDispatch` writes through one table of rows, and every row carries
  the id it will have.
- `ChecklistRepository.addItem` and `TimeTrackingRepository.createManualEntry`
  take the id, so a caller that has to know it before the write can name it.
- `ProposalItemKind.Unknown` joins the vocabulary, and the codec always answers
  with a kind.
- The `kind_json` column holds the sealed format for new rows; old rows are read
  by the flat reader until the oldest build in the user's hands is this one.
- One arch gate says that a surface does not name a proposal's status, and one
  says that the wording of a decision is declared in one place.

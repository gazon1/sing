# Proposals

> **Reference, not a procedure.** This describes the current shape of the proposal
> vocabulary and the files that hold it. It goes stale whenever the code changes, so a
> change to the vocabulary updates this file in the same commit.

## What a proposal is

A **proposal** is a change to the user's data that nobody has agreed to yet. The model
proposes it, the user reads it, and the user applies it or turns it down. Every tool
that writes goes through this path — there is no second way to write.

A proposal holds **items**, and each item is one change with its own decision. A
proposal that changes five things is five items, so the user can keep two of the five
and turn down the other three, and each refusal carries its own reason.

```
model ──proposes──► item (Pending) ──user applies──► item (Confirmed) ──► the write
                          │
                          └──user turns it down──► item (Rejected, with a reason)
```

## Where the vocabulary lives

| File | What it holds |
|---|---|
| `domain/model/ProposalItemKind.kt` | **The vocabulary.** Every kind of change, and each kind's behaviour: the summary the user reads, the fingerprint identity, and whether the user may change the value before applying it. |
| `domain/model/ProposalValues.kt` | **The typed values** — `TaskValue`, `NoteValue`, `ProjectValue`, `TagValue` — and the one reader that turns text into a value (`TaskValue.read(field, text)` and its siblings). All four share one interface, `ProposalValue`, and one generic `editableOf`. |
| `domain/model/ProposalItem.kt` | The item: its status, its reason, its fingerprint. |
| `domain/model/AiProposal.kt` | The proposal and its items; the pending/decided split. |
| `domain/logic/ProposalFingerprint.kt` | The rejection fingerprint — the identity that stops the model re-proposing something already turned down. |
| `domain/logic/ProposalOperation.kt` | The outcome of reading a change: proposed, unknown, missing value, unreadable value, empty change. |
| `domain/logic/ProposalBuilder.kt` | Builds a proposal from a tool's arguments. |
| `domain/usecase/ProposalPlan.kt` | The plan — what applying an item would write. |
| `domain/usecase/ProposalPlanner.kt` | Turns a stored item into a plan. Pure: no I/O. |
| `domain/usecase/ProposalDispatch.kt` | Executes a plan. No routing, no validation. |
| `domain/usecase/ApplyProposalItemUseCase.kt` | Apply or turn down one item. |
| `domain/usecase/EditProposalItemUseCase.kt` | Change the value of an item before it is applied. |
| `data/ProposalKindCodec.kt` | **The codec** — the kind as it is stored in `kind_json`. |
| `data/ProposalMapper.kt` | The rows as the domain: a proposal row, an item row. |
| `presentation/ProposalsViewModel.kt` | The screen's state: the proposals waiting, and the reason a change failed. |
| `presentation/ProposalDecisions.kt` | **The five operations** every proposal surface performs: apply one change, turn one down, change its value, apply them all, turn the proposal down. The wording, the place the reasons live and the way a failure is reported are handed to it as values. |
| `presentation/components/ProposalCard.kt` | The card, shared by the proposals screen, the task detail and the note editor. |

## The value is the field

A kind of change does not name a field and carry a string. It carries a **value**, and
the value knows what field it is:

```kotlin
ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High))
```

Before, the field and the value were two separate things (`SetTaskField(Title,
"42")`), so the shape of the value was whatever the model happened to type, and the
code reading it had to guess. A priority was the string `"high"` in one place and the
enum `High` in another, and the two spellings did not always agree. Now the field and
the value cannot disagree, and there is **one** reader per entity
(`TaskValue.read(field, text)`) — the tools read a value before they store the
proposal, and the user reads a value again when they change it.

## The place the compiler does not protect

**The codec.** `kind_json` stores the kind as a flat JSON object, and
`ProposalKindCodec.decodeKind` decodes it **by string** — the type name of the kind. So
a kind the codec was not taught to read does not fail to compile and does not fail
loudly: it decodes to nothing, the item is dropped, and the proposal silently loses a
change. The exhaustive `when`s in the domain do not help — the codec is not a `when`
over the type.

A **value** is the other half of the same story, and it is handled the other way
round: a value the reader cannot read is not dropped — it becomes an `Unreadable`
value that carries the reason, the change stays on the card, and the planner refuses
it before the claim. A change the user cannot see is a change nobody decided about.
A **kind** the codec does not know is still a dropped row: the codec has no way to
name what it does not know.

The protection is mechanical rather than structural:
`shared/src/jvmTest/.../feature/proposals/data/ProposalMapperTest.kt` round-trips
every kind and every value, and — this is the part that matters — checks the samples
against the **sealed serializer's** list of variants. A kind that arrives without a
sample fails the coverage test instead of disappearing in the app.

Adding a kind therefore means: the kind itself (one file), a sample in the test (one
file), and the codec's `when` (one file). The first two are compiler- or test-caught;
the third is what the round-trip test exists for.

The codec is in its own file because it outgrew the row mappers — and because it is
the one file in the feature that the compiler cannot help with.

### The structural fix, and why it is not done yet

The fix is to stop decoding by string: store `kind_json` in the shape kotlinx
serialization produces for the sealed hierarchy, and let the serializer do the
dispatch. Then `decodeKind` is not a `when` over type names at all, and a kind that
arrives without a codec branch cannot be dropped — there is no branch to forget.

It is not done, and the reason is the data: the rows in the wild carry the flat
shape, and a new shape is a new shape. It needs

1. the new shape written by the encoder,
2. the old shape still readable — a fallback path in the decoder, which is the
   flat `when` this change would eventually delete,
3. a decision about when the fallback is dropped.

Step 2 is why it is a separate piece of work and not a detail: until the fallback
is gone, the codec still holds the `when` the change was meant to remove, so the
work would not have made anything safer — it would have made it safer *and* left
the old path in place. The mechanical protection (the round-trip test) is what
holds the line in the meantime, and it holds it today.

## The lifecycle of an item

| State | What it means |
|---|---|
| `Pending` | Waiting for the user. The item can be applied, turned down, or **changed**. |
| `Confirmed` | Applied. The write happened. |
| `Rejected` | Turned down, by the user or by a batch action. The reason is kept and shown. |
| `Retracted` | Withdrawn with the proposal. |

Two things the user can do to a `Pending` item:

- **Turn it down**, with a reason. The reason is stored, shown on the card, and fed
  back to the model — it is how the model learns what the user did not want.
- **Change it**, before applying. The value the user types is read by the same reader
  that read the model's value, so a value that does not read is refused with the same
  reason — and the refusal is shown next to the change it belongs to.

A change the app cannot apply keeps its reason next to it as well, instead of failing
silently: the screen reads the ViewModel's error events (which it did not do at all
before, so every failure was invisible) and the card keeps the reason per item.

A change that **fails while it is being applied** goes back to `Pending`. The claim is
the decision and the dispatch is the work; when the work fails, the decision must not
stand on its own — nothing was changed, so the item is released back to `Pending`, the
reason is shown next to it, and the user can try again.

And a change that **writes several rows writes all of them or none of them**: a change
that adds three checklist items and fails on the second takes the first back, because
the change goes back to waiting and the user will try again — the row that stayed would
be there twice.

## Where the values come from

The **collection** kinds (`SetTaskTags`, `AddTags`, `RemoveTags`, `AddChecklistItems`,
`AddSubtasks`, `AddTimeEntries`) carry plain strings, not domain ids: the model
proposes *text* ("milk", "chore"), and resolving that text to a real id is a
precondition check at apply time, not something the model can be trusted to have done
correctly.

`TaskValue.Project` is the exception that proves the rule — it carries a project id,
because the id is what the app resolves a name to, and the id is not something a user
can retype. It is therefore not editable either.

## The values that are allowed to be absent

`TaskValue.DueDate` and `TaskValue.DueTime` hold **nothing** as well as a date: an
empty value means "no due date" — which is how the tools that set the date have always
said it. A task that can be given a date must be able to lose it again, so the value is
nullable and the reader reads an empty string as the absence of a date, not as a
mistake.

The stored shape is unchanged: a cleared date is written as an empty `value`, which is
the shape the rows written before the typed dictionary already have — so those rows keep
reading, and the round-trip test covers the cleared date as well.

# The proposal has a lifecycle, and the vocabulary is typed

Issue: — · Related: `docs/decisions/2026-10-23-the-value-is-the-field.md`,
`docs/decisions/2026-10-23-the-proposal-can-be-changed-before-it-is-applied.md`,
`docs/decisions/2026-10-23-the-review-scenarios-live-in-the-chat.md`,
`docs/decisions/2026-10-23-the-invariant-is-a-test-not-a-file.md`

Status: **accepted**
Tags: **ai write-path proposals contract vocabulary ui**

## What

The review that asked for this found five things, and they are one shape: **a
proposal was a thing the user could only accept, and the vocabulary was a thing
only the code could see.**

1. **`create_tag` was the last tool that wrote.** Eight tools proposed; one
   wrote. The registry that said which tools wrote was a *file*, and the check
   that it was honoured was a *gate* — two places to keep in step, and the gate
   could only say that the file was right, never that the code was.

2. **The field and the value were two things.** `SetTaskField(Priority, "42")`
   — the shape of the value was whatever the model happened to type, and the
   code that read it had to guess. A priority was `"high"` in one place and
   `TaskPriority.High` in another, and the two spellings did not always agree.

3. **The behaviour of a kind was spread over five files.** What the user reads
   on the card, the identity the rejection guard compares, and the way a kind is
   applied were each somewhere else — so adding a kind was a five-file edit,
   and forgetting one of the five was not a compile error.

4. **The prompt promised something the app is not.** It said the four review
   situations were "the four the app is built around". They are the model's
   situations, and the app has no screen for any of them.

5. **A proposal had no life after the proposal.** The user could accept it or
   refuse it; they could not change it, and when a change could not be applied
   the reason went nowhere — the screen did not even read the error events, so
   every failure on the proposals screen was silent.

## The change in behaviour

- **Every write tool proposes.** `create_tag` proposes like the rest, and the
  file registry and its gate are gone: the invariant is now a test that reads
  the behaviour and the source.
- **The value is the field.** `SetTaskField(TaskValue.Priority(TaskPriority.High))`
  — the variant *is* the field, and there is one reader per entity. A value the
  task cannot hold cannot be written down.
- **The kind carries its behaviour.** The summary, the fingerprint and the
  editable surface live in the kind, so a new kind is a new kind.
- **The prompt stops promising.** The four situations stay in the chat, and the
  claim that the app is built around them is gone.
- **A proposal can be changed before it is applied.** The value the user types
  is read by the same reader that read the model's value, and a value that does
  not read is refused with the reason.
- **The reason is visible.** The user's own refusal stays on the card, and a
  change that could not be applied keeps its reason next to it — the screen
  reads the error events it was never reading.
- **The vocabulary survives the database.** A round-trip test covers every kind
  and every value, and checks the samples against the sealed serializer's list
  of variants, so a kind that arrives without a sample fails loudly.

## What is not in this change

- **The storage format does not change.** The kind is still stored as a flat
  JSON payload with a `field` and a `value`; the domain is typed, the codec is
  not. Old rows keep reading.
- **The review scenarios do not come to the app.** They stay in the chat; the
  prompt stops claiming otherwise.
- **A collection is not editable.** `SetTaskTags`, `AddTags`,
  `AddChecklistItems`, `AddSubtasks` and `AddTimeEntries` carry lists of text;
  a field the user types cannot honestly express them.
- **A project id is not editable.** `TaskValue.Project` holds an id, and an id
  is not something a person types.

## Why now

Because the vocabulary grew from 15 kinds to 19 while this was being worked on,
and every one of the five findings is a consequence of the same thing: the
vocabulary has no single place where it is written down, so each new kind was
five decisions in five files, and one of them could be missed silently.

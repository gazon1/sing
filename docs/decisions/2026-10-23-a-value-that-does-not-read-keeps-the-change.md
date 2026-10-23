---
title: "A value that does not read keeps the change"
date: 2026-10-23
status: accepted
tags: [proposals, vocabulary, codec, domain]
---

# A value that does not read keeps the change

## Context

The typed vocabulary moved the refusal of a bad value from "building the
proposal" to "reading the row". That is where it belongs — the reader is the one
place that knows whether a value reads — but the refusal had nowhere to go on
the way in:

```kotlin
// ProposalKindCodec.kt
private fun <T> readValue(obj: JsonObject, read: (String, String) -> ReadResult<T>): T? {
    val field = obj.stringOrNull("field") ?: return null
    return when (val result = read(field, obj.stringOrNull("value").orEmpty())) {
        is ReadResult.Ok -> result.value
        is ReadResult.Rejected -> null          // ← the change disappears
    }
}
```

`null` was the old "this row is not for this build" answer, and the caller
dropped the item:

```kotlin
// ProposalMapper.kt
"SetTaskField" -> readValue(obj, TaskValue::read)?.let { ProposalItemKind.SetTaskField(it) }
```

So the chain was: a row written by a build that knew the field — an enum that
lost a variant, a spelling that changed — reads as `null`, the item is filtered
out, and the proposal **silently loses the change**. The user sees a card with
one change missing and no reason: the change was not refused, it was deleted.

That is the same defect the first review round closed on the other side of the
same value — "the reason for a refusal must be visible" — but on the side where
the value comes back from the database rather than from the model. On the
model's side a bad value was refused with a reason and the model could fix it.
On the database's side there is no model to fix it, and the user was left with
a change that had quietly gone missing.

## Idea

A value that does not read is not a missing change. It is a change that cannot
be applied, and it carries the reason.

## Decision

**The unreadable value is a variant of the dictionary, not a `null`.** Each of
the four dictionaries holds one:

```kotlin
@Serializable
@SerialName("Unreadable")
data class Unreadable(val field: String, val raw: String) : TaskValue {
    override val fieldName: String = field
    override val summary: String = "Set the $field to «${raw.asCardValue()}»"
    override val text: String = raw
    override val readFailure: String?
        get() = (TaskValue::read(field, raw) as? ReadResult.Rejected)?.reason
    override fun editableValue(): EditableValue<TaskValue> = editableOf(field, TaskValue::read)
}
```

The kind stays "set a field", so the card, the fingerprint and the editing
surface keep working. The reason does not live in the value: it belongs to the
reader, and the reader is the one place that knows why a value does not read. So
it is asked of the reader again on the way in — and the stored shape stays what
it has always been: the field and the text.

**The reason travels on its own channel.** `ProposalItemKind.readFailure` and
`ProposalItem.readFailure` carry it; it is not part of `humanSummary`, because
the summary is prose about the change and the reason is prose about the app.

**The plan refuses it before the claim.** The planner builds the plan first, and
a value that does not read cannot become a plan:

```kotlin
is TaskValue.Unreadable -> error(value.readFailure ?: "This value cannot be read.")
```

The change therefore stays `Pending`, with the reason on the card. It was
never decided and it was never lost.

**The four dictionaries share one interface.** `ProposalValue` declares what
every value has in common — `fieldName`, `summary`, `text`, `readFailure` — and
one generic `editableOf` serves the four of them. A new dictionary now writes
its own values and inherits the rest.

## Rationale

**A `null` is the wrong shape for this.** `null` says "there is nothing here".
The truth is "there is something here and it cannot be applied". A change that
the user cannot see is a change nobody decided about — the user cannot turn
down a change they cannot see, and they cannot know that the app had something
else in mind.

**The reason comes from the reader, not from the row.** The row holds a field
and a text. Whether that text reads is a property of the entity — "is a
priority" is the task's rule, not the row's. Storing the reason in the row
would mean storing a sentence the reader could contradict later; asking the
reader again keeps one source of truth, and the reason is the same sentence the
user would get if they typed the value themselves.

**The refusal happens before the claim.** Claiming first and failing afterwards
would record a decision for a change that did not happen — the defect the
lifecycle work closed one round earlier. The plan is built first, and the plan
cannot be built.

**The card can show it.** The change stays on the card, with the reason next to
it, and the user can type a value that does read — the same editing surface
every other change has.

## Consequences

- **A row written by an older build keeps its change.** It reads as "this change
  cannot be applied", with the reason, instead of disappearing.
- **The codec no longer returns `null` for a value it cannot read.** It returns
  the value that carries the reason. The `null` it still returns — a payload it
  cannot make sense of at all — means something different and is still a dropped
  row.
- **The kind stays the same kind.** `SetTaskField` is still "set a field", so
  the fingerprint, the summary and the editing surface need no special case.
- **The stored format does not change.** `Unreadable` is produced on the way in,
  not written to the database; the row keeps its flat `field`/`value` payload.
- **The dictionaries grew a variant each.** Four more variants is four more
  branches in the exhaustive `when`s — and the compiler points at every one of
  them, which is the point of the typed vocabulary.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/model/ProposalValues.kt`
  — `ProposalValue`, the four dictionaries and their `Unreadable` variants.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/data/ProposalKindCodec.kt`
  — `readValue`, and what it returns now.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/usecase/ProposalPlanner.kt`
  — the refusal, before the claim.
- `docs/architecture/proposals.md` — where the vocabulary lives, and why the
  codec is the one place the compiler does not reach.

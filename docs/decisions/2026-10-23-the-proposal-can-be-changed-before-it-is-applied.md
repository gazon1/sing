---
title: "A proposal can be changed before it is applied, and a refusal shows its reason"
date: 2026-10-23
status: accepted
tags: [proposals, ui, ai, lifecycle]
---

# A proposal can be changed before it is applied, and a refusal shows its reason

## Context

A proposal is a change nobody has agreed to yet. Until now the user could do
exactly two things with it: apply it, or turn it down.

**There was no third thing.** The model named a value — "Buy milk" as the task's
title, `high` as its priority — and if the model named it wrong, the user's
options were to apply it or to refuse the whole change and start the
conversation again. The user's own knowledge of what they meant was not
available at the point where it was needed.

**The reasons went nowhere.** Three places, three silences:

1. The user could turn a change down *with a reason*, and the reason was stored
   — and then never shown again. The card drew only the changes that were still
   waiting, so the item the user had just turned down disappeared, taking the
   reason with it. The user could not tell whether their reason had been
   recorded at all.
2. A change that could not be applied — the task was deleted, the value did not
   read — failed silently. The ViewModel emitted an error event, and **nothing
   collected it**: the proposals screen had no snackbar and no event handler.
   Every failure on that screen was invisible. The user pressed "apply", nothing
   happened, and the change sat on the screen looking perfectly fine.
3. The card showed only the action — "Update title" — and never the value. So
   even the change the user *was* looking at did not say what it would do.

## Idea

Give the proposal the two things it is missing: a way to change the value
before it is applied, and a reason that stays visible.

## Decision

**The user may change the value before applying it.** A change whose value a
person would type carries a field; what the user types is read by **the same
reader that read the model's value**, so a value that does not read is refused
with the same reason — and the refusal is shown next to the change. The changed
value is stored with the summary and the fingerprint that belong to it, so the
card and the rejection guard both talk about the new value.

**The editable surface lives in the kind.** `ProposalItemKind.editableProposal()`
says what may be changed and how the new value is read; a kind that carries a
list of texts or an id says that there is nothing to type, because a field that
cannot be filled in correctly is worse than no field.

**The card keeps the decided changes.** A turned-down change stays on the
card, muted, with the reason. A proposal that is half decided still tells the
whole story, and a refusal the user cannot see is a refusal they cannot learn
from.

**A failed change keeps its reason next to it.** The ViewModel records the
reason per item, and the card shows it under the change it belongs to. The
screen also reads the error events it was never reading, so the failure is
announced as well as recorded.

**The reason stays where the user's attention is, and the item stays
actionable.** A change that could not be applied is *not* silently decided: it
stays waiting, with its reason, so the user can fix the cause and try again —
or turn it down.

## Rationale

The edit is the obvious missing piece, and the typed vocabulary is what makes
it honest: without one reader, "change the value" would need a second
interpretation of the text, and the two would disagree. With one reader, the
user's value and the model's value are refused by the same rule, for the same
reasons.

The reason is kept next to the change rather than only in a message for three
reasons. A message is transient and the failure is not; the user's attention
is on the change, not on a notification; and the reason of *which* change
failed is information a message about a batch does not carry — which is why a
batch failure now names the change it belongs to.

Keeping a failed change waiting is the choice that keeps the promise honest: a
proposal is a decision the user has not made yet, and a change the app could
not apply is not a decision the app has made either.

## Consequences

- **The card shows the value.** The summaries carry it — "Set the title to
  «Buy milk»" — because a proposal whose value cannot be read is one the user
  approves blind.
- **The screen can fail loudly.** The error events it emits are now read, so a
  failure on the proposals screen is visible for the first time.
- **The stored item can be rewritten — but only while it is pending.** The
  rewrite carries the same guard the claim uses: a decided item is a decision,
  and rewriting it would rewrite what the user decided.
- **The fingerprint does not change when the value does.** The fingerprint
  identifies the *change* (this field of this task), not the value in it, so
  the rejection guard keeps protecting the slot rather than one spelling of it.
- **Not everything is editable.** Lists and ids are not, and saying so is part
  of the decision rather than an omission.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/usecase/EditProposalItemUseCase.kt`
  — the change, and the reason a bad value is refused.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/components/ProposalCard.kt`
  — the card: the field, the decided changes, the reason.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/ProposalsViewModel.kt`
  — the reasons per item, and the events.
- `docs/decisions/2026-10-23-the-value-is-the-field.md` — the reader that makes
  the edit honest.

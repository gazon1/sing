# text-field-state-migration — Design

Required: a cross-cutting change to every text field in the application, and a new static check
that constrains how the next one may be written.

The architectural reasoning — why the state-based API, why the view model keeps a string
rather than the field's state object, and why the renderer for server-driven surfaces stays
value-based — is in `docs/decisions/2026-10-19-text-field-state-migration.md`. It is not
restated here. This document describes the shapes the change introduces and the mechanics of
the reconciliation.

## Three field shapes

Every migrated field is one of these three. The shape is a property of what the screen does
with the text, not of the field's appearance.

### Local state

The field owns its text and nothing else needs it. The screen reads the field's state at the
moment it needs the value — on submit, on a click, when composing a request.

This is the shape for: a search box whose value is only needed when the search runs, a filter
field whose value is read when the filters are applied, a confirmation dialog's single input.

### Bridged

The view model must have the value: it validates it, persists it, compares it, or reports it
back to the user as part of a state object.

The field still owns the text while the user types. The view model holds a plain string. A
helper reconciles the two in both directions.

This is the shape for: a task title that the view model validates before accepting, a password
the view model submits, a form field whose value determines which other fields are shown, an
API key the view model writes to secure storage.

### Anchored

The field displays a value that belongs to something else — a title resolved from a record, a
stored value, a value computed elsewhere. The field is not an input at all; it is a rendering
of a value the screen already has.

The state-based API serves this shape too: the field holds the value it was given, and the
screen gives it a new value when the underlying thing changes. What it does not do is push
anything back — an anchored field is not a source of edits.

### Choosing between them

The question is one question: **does anything outside the field need this text?**

- No → local state.
- Yes, and it is a plain value the screen can hand to the view model → bridged.
- Yes, and the value comes from somewhere else and the field does not edit it → anchored.

The failure mode to avoid is a bridged field where local state would do: the bridge is a
running reconciliation, and a screen that never reads the value elsewhere is paying for it.

## Why the field owns the buffer and the view model owns a string

The field owns the buffer because that is where the text actually is while the user types, and
because that is the only place where input can be rejected. The state-based API's input
transformation runs inside the edit itself; a rule that lives in a change callback runs after
the value has already been accepted and has to be undone.

The view model keeps a string because the view model's contract in this application is a value:
an immutable state object, published through a state flow, compared and restored as one. A
string is that. The field's state object is not — it is a mutable holder backed by a snapshot,
and putting one inside an immutable state object would make the same text readable from two
places with two different lifetimes. The view model would have a value that changes without its
state changing, and every equality check, save and restore would have to know about it.

So the two holders have different jobs, and the migration is the work of keeping them
consistent.

## The reconciliation

The bridge is one composable helper. It does two things and guards each of them.

### Field → view model

The field's text is pushed upstream whenever it changes. The push is skipped when the upstream
already holds that exact value.

Without the guard, the field's *initial* text is pushed on the first emission. The initial text
is seeded from the upstream, so the first push writes the upstream's own value back to it — a
harmless round trip that marks the field dirty for no reason, and one that would repeat on every
recomposition that recreated the field's state.

### View model → field

When the upstream value changes, the new value is written into the field. The write is skipped
when the upstream value is the one the field last *held*.

Without that guard, the two can fight. The upstream may lag behind the user: the user types a
character, the field holds `abc`, the upstream still holds `ab` until its debounce elapses. The
naive rule — "the upstream differs from the field, so write it" — passes, and the character the
user just typed is reverted. The user types again, and it happens again: the field is unusable
for anything typed faster than the upstream.

Guarding on the last value the field held distinguishes two cases that both look like "the
upstream differs from the field":

- the upstream is **behind**, still holding what we pushed → ignore it, it is catching up;
- the upstream was **rewritten** to something else → apply it, the change came from outside the
  field.

### The remaining failure mode

There is a third case, and it is worth stating plainly because the guards do not cover it: if
the upstream rewrites the value to something that is neither the last value the field held nor
the current field text, the field is overwritten, and a character the user typed is lost.

The guard compares against the last value the field *held*, not the last value it *pushed*. The
seed counts: a field that was seeded with a loaded value and never edited has held that value,
so an upstream that later clears its own copy is a change from outside the field and is applied.
A guard on the last pushed value would compare the cleared value against the empty string the
field never pushed, call it an echo, and leave the stale text on screen.

That is the intended outcome — an explicit rewrite from outside the field should win — but it
means a rewrite that lands mid-typing costs a keystroke. The guards remove the echo case and
the lag case, which are the ones that happen on every keystroke; the rewrite case is deliberate
and rare.

The two guards are pure predicates over strings, kept out of the composable so they can be
tested without a snapshot or a coroutine.

## Filtering, validation and formatting

Three different things, three different homes.

**Filtering — what the field accepts.** A field that accepts only digits, or only a bounded
length, states it as an input transformation. The invalid edit is rejected inside the edit, so
the field never holds the rejected text and the value the screen holds never contains it.

**Validation — whether the value is good enough.** A rule about the *meaning* of the value —
a recurrence interval must be a whole number of at least one, a day of the month must fall in a
range — is applied where it is used, by whoever consumes the value. Some of it is applied in
the transformation as well, so the user is told at the point of entry rather than at submit.

**Formatting — how the value is shown.** A value that is stored differently from how it is
displayed needs a display-only transformation. No field in the tree needed one during this
migration; the distinction is recorded so the next field that does not push a formatting rule
into the input path.

### Why the rules are plain functions

The transformation receives a text buffer that cannot be constructed from a test, so a rule
written directly inside the transformation is a rule that cannot be tested. The rules therefore
live as plain functions over strings — filter the digits, is the length within the limit, is
this a valid interval, is this a valid day — and the transformation is a one-line adapter that
applies one of them.

This is why the helpers are `internal` and separate from the fields: the rule is the decision,
the transformation is the delivery, and only the decision needs to be reachable from a test.

## The server-driven surface

The renderer for server-driven surfaces used to stay value-based, and the static check
exempted that one path explicitly rather than by pattern. It is state-based now, and the
exemption is gone.

The reason it could stay value-based was a conflict rule that did not exist yet. The value in
that renderer is not a local string: it is a value in the surface's shared data model, and an
incoming message can rewrite that model while the user is typing. The state-based API needs to
say who wins — the server or the keyboard — and the surface protocol does not say. It would
also need a registry mapping each bound path to a field state, so the initial value is applied
once per path instead of on every recomposition.

Both turned out to be unnecessary. The bridge already carries the conflict rule, and the
registry is not needed because the field state is remembered by the composable rather than
looked up by path. The renderer uses `rememberBridgedTextFieldState` with the model as the
external value: the field owns the text while the user types, and a server value that is not
an echo of what the field holds replaces it — the same outcome the value-based field had,
because the model always won. The one difference is that a model value the field itself pushed —
the last one, and the recent values before it — is not written back into the field, and that
difference is benign, because the field already displays that text.

The decision and the reason are in the ADR, so the change is visible rather than an
oversight.

## The static check

A migration is one commit away from being undone by the next feature, so the shape is policed.

The check fails the build when a text field is written with the value-based pair. It names the
shape in the message and points at the two alternatives: a locally owned field state, or the
bridge when the view model needs the value. It exempts test sources — a test that pins the
shape of a component is not shipping a screen — and nothing else.

A check that only bans a shape nobody writes any more is a check nobody runs. The rule is
therefore registered in the shared rule set, enabled in the lint configuration, and its table
entry is regenerated from the rule's own source, so the documentation of the rule cannot drift
away from the rule.

## Rollback risk

**Rollback is mechanical but not free.** Reverting the migration means putting the value-based
pair back on 51 call sites, restoring the filtering guards that lived in the change callbacks,
and restoring the form-state classes that carried the text. The form-state classes were deleted
as dead code, so a revert has to reconstruct them, not just restore the files.

**The two shapes that are hard to revert are the ones nobody notices.** A field that was
migrated and then had its filtering moved into a transformation will, after a partial revert,
accept text it used to reject — the guard is gone and the transformation is not there to replace
it. The failure is silent and shows up as a user typing a letter into a digits-only field.

**The check is part of the change, so a partial revert also has to remove the check** — or the
build stops. That is the intended behaviour and it is also the reason the check and the
migration should be reverted together rather than separately.

**The safe order for a partial revert is per-feature, not per-file**: a screen is either fully
value-based or fully state-based, and never a mixture. A field whose value is held by a view
model that has been reverted while the field is still state-based is a field whose text no
longer reaches the view model — the user types and nothing is recorded.

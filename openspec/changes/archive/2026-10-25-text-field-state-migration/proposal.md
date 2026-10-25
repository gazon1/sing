# text-field-state-migration

**Status:** implemented — the code change is in the working tree; the ADR is still `open`
**Tags:** ui, compose, viewmodel
**ADR:** `docs/decisions/2026-10-19-text-field-state-migration.md`

## What

Move every Compose text field in the application from the value-based API — a string handed in
plus a change callback — to the state-based API, in which the field owns its own text.

The field keeps its own buffer. A screen that needs the value elsewhere keeps a plain string in
its state, and a small helper reconciles the two: the field pushes what the user types, and
changes made outside the field are pulled back in.

The filtering that used to live in each call site's change callback moves into the field's own
input transformation, so "digits only" or "at most two characters" is stated once, next to the
field that has it, and is rejected at the point of entry.

A static check is added so the old shape does not come back with the next feature.

## Why

The value-based shape puts two obligations on every caller, and every caller in the app was
paying both:

- **The caller owns the text.** The field renders exactly the string it is handed, so text that
  did not arrive through the field — a paste, an autofill, an automated input action — has no
  path into it. The caller's copy wins.
- **The caller owns the filtering.** A field that accepts only digits or only a bounded length
  has to say so in its change callback. That guard was written at every call site that needed
  one, in the shape where writing it wrong is invisible until a user types one character too
  many.

The state-based API removes both obligations: the field can reject a character as it is entered,
and the filtering rule belongs to the field rather than to its caller.

The state-based API also wants the field's state to be created where the field is. This
application's screens render from an immutable state object owned by a view model, and the text
of a field is part of that state. The reconciliation between the two is the subject of the
design, not something the migration can avoid.

## Scope

### In scope

- Every text field in the shared application code: the field owns its text.
- Fields whose value the view model must validate, persist or report: the view model keeps a
  plain string, and the two are kept in agreement.
- Filtering and validation of typed input, applied at the point of entry.
- Secrets: masked by default, revealed only on an explicit request.
- Removal of the form-state and helper classes that existed only to carry text for a
  value-based field.
- A static check that fails the build when a value-based text field is written again.
- The renderer for server-driven surfaces. Its value lives in a data model the server can
  rewrite while the user types, so it uses the same bridge with that model as the external
  value. The conflict rule it needed — a server rewrite wins over the keyboard — is the
  bridge's rule, and the reason is recorded in the ADR.

### Out of scope

- Text fields in the generated surfaces' other renderers, and any change to the surface
  protocol.
- The value of a field after it is submitted — what a screen does with the text it collected is
  unchanged.
- Any change to what a screen shows for a field's content, beyond the masking behaviour of
  secret fields.
- The app's own text (labels, messages, documentation) — this is about fields the user types
  into.

## References

- `docs/decisions/2026-10-19-text-field-state-migration.md` — the decision and the reasoning
- https://developer.android.com/develop/ui/compose/text/migrate-state-based — the platform
  guide the migration follows
- `openspec/changes/archive/2026-10-25-text-field-state-migration/specs/text-input/spec.md` — the observable
  behaviour
- `openspec/changes/archive/2026-10-25-text-field-state-migration/design.md` — the three field shapes and the
  reconciliation between them

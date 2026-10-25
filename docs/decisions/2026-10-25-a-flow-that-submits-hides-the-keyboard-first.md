---
title: "A flow that submits a form hides the keyboard first, and answers the choice the form asks"
date: 2026-10-25
status: accepted
tags: [testing, maestro, auth]
---

# A flow that submits a form hides the keyboard first, and answers the choice the form asks

## Context

The two attach-account flows (`07-account-attach`, `10-attach-account-validation-error`)
were failing for reasons that had nothing to do with each other, and both failures were
silent — the flow said "tapped the button", and the button had not been tapped.

**The keyboard was over the button.** The attach screen puts the submit button under the
password field, and the phone's keyboard covers it. The screenshot at the point of failure
shows the button half-hidden behind the keyboard. `tapOn` found the element — Maestro
taps by coordinates, and the coordinates were under the keyboard — so the command
reported COMPLETED and the tap went to the keyboard. The submit never ran: no error, no
navigation, nothing.

**The button was disabled anyway.** The attach screen asks what happens to the offline
data (REQ-UA-005 — neither outcome is a default), and the submit button stays disabled
until that question is answered. The flows never answered it, so the button was disabled
whether or not the keyboard was in the way.

## Idea

A flow that submits a form has to do what a person does: answer the question the form
asks, and get the keyboard out of the way of the button.

## Decision

**The attach flows answer the disposition and hide the keyboard.**

- The flow taps `auth_attach_keep_button` — the button the screen tags `keep`, and whose
  label is "Move to account" — before it fills the form. Without it the submit button
  is disabled for a second reason, and the flow would be testing the wrong guard.
- The flow hides the keyboard after each text input (`hideKeyboard`), so the tap on the
  submit button lands on the button.

The `hideKeyboard` is the pattern the rest of the tree already uses, and the gate
`scripts/check-maestro-keyboard-commands.py` enforces its shape: one `hideKeyboard` per
text input, and none without something to hide.

## Rationale

Both failures look the same from the flow's side — "I tapped it and nothing happened" —
and neither is visible in the flow's own output. The screenshot is what settled it: the
button was there, under the keyboard, and the disposition had never been chosen.

A flow that cannot see the button it is tapping is not testing the app; it is testing
the geometry of a phone.

## Consequences

- Both attach flows pass on a device (RMX3938, API 35).
- The crash the attach screen had — the one that also took the "Change password" screen
  down — is fixed separately: ADR
  `2026-10-25-a-screen-that-leaves-a-graph-cannot-read-its-local`.
- The cause `auth_attach_email_input is visible` in `config/docs/maestro-debt.yaml` is
  marked fixed: the screen is reachable, the form is answered, and the submit runs.

## Links

- `Maestro/flows/auth/07-account-attach.yaml` and
  `Maestro/flows/auth/10-attach-account-validation-error.yaml` — the flows.
- `scripts/check-maestro-keyboard-commands.py` — the shape of a `hideKeyboard`.
- `docs/decisions/2026-10-25-maestro-drops-the-selector-from-inputtext.md` — the other
  silent no-op in the same flows.

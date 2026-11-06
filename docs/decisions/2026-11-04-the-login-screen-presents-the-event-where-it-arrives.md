---
title: The login screen presents the event where it arrives
date: 2026-11-04
status: accepted
tags: [auth, ui-event, state-hoisting, compose]
---

# The login screen presents the event where it arrives

## Context

`LoginScreen` collected the auth event bus and copied each event into a local
flag: `errorMessage` and `message`, both `remember { mutableStateOf }`. The
flags then had to be cleared by hand wherever the screen moved on — four
places, each one a chance to leave a stale error on the form. The audit of
`remember` flags in composables (#1411, `2026-10-29-state-hoisting-remember-audit.md`)
classified the pair as UI state and kept them.

Two things were wrong with that. The first is that the flags were not state at
all: an `AuthUiEvent` is a one-shot signal, and the screen was keeping a copy
of a signal in order to be able to show it later. The second is that the
classification was made without noticing that the collector sat **after** an
early return: when no project is configured the screen shows the
server-configuration form and returns, so the bus was never collected on that
screen at all — and the error that form produces ("Both the project URL and the
anon key are required") had nowhere to go. The user pressed Save and was told
nothing.

`BackupScreen` had already made the other move: its `BackupUiEvent` carries the
error, and the screen presents it where it arrives, with no copy kept.

## Idea

- Keep the flags and clear them more carefully — the flags would then be the
  screen's own state, and the audit's classification would hold.
- Move the message into `AuthUiState`, so it is state and lives in the view
  model, and the screen reads it like any other state.
- Present the events where they arrive, the way `BackupScreen` does, and keep
  no copy.

## Decision

`LoginScreen` keeps no copy of an event. The bus is collected once, in
`LoginScreenEffects`, and each event is presented there:

- `AuthUiEvent.Error` → the shared error dialog (`Notification.Error`);
- `AuthUiEvent.Message` → a snackbar (`Notification.Toast`);
- `AuthUiEvent.NavigateToHome` → the screen's own navigation.

The collector is installed **before** the server-configuration branch returns,
so the error that branch produces reaches the user.

The mapping from an event to a notification is `AuthUiEvent.toNotification()`,
and it is the whole of what the screen does with an event.

## Rationale

The flags were a mirror of the bus: two pieces of state whose only content was
what an event had said, and whose lifetime was maintained by hand. Every manual
clear is a place where a stale message can survive — the mode toggle, the
resend, the back button — and a stale error on a sign-in form is worse than a
missing one, because it describes a failure that is not the one that happened.

A one-shot event and a piece of state answer different questions. "Something
went wrong" is a fact about what just happened, and it is on screen until the
user closes it; "your account was created" is a notice, and it goes away on its
own. Presenting the first as a dialog and the second as a snackbar keeps those
apart — which is the distinction `AuthUiEvent.Message` was written to protect,
and which the inline form slot could only express by colour.

Putting the message into `AuthUiState` was the other serious option, and it
was not taken because it changes the contract of the event bus: an event that
must be re-readable later is not an event, and `AuthUiEvent` is documented as
one. The state route would also have made the two sibling screens
(`AttachAccountScreen`, `MagicLinkConfirmScreen`) inconsistent with this one
for no gain.

The cost accepted: the login form no longer carries an inline error line, so a
flow that asserted `auth_error_text` on this screen had to move to the dialog
that replaced it.

## Consequences

- An error on the login screen is a dialog, and a neutral notice is a snackbar.
  A screen that presents an auth event must say which it is; it does not get
  both as the same shape.
- The login screen keeps no `remember` flag for a message. Anything the user
  must still see after the event has passed belongs in `AuthUiState`, not in a
  copy of the event.
- The collector of the auth event bus is installed before any early return on
  the screen. A branch that returns first must not return past the collector.
- The shared error dialog's button carries a tag (`result_dialog_confirm`),
  because the dialog can be raised by an event and a flow has to be able to
  close it without matching the translator's "OK".

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/auth/LoginScreen.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/auth/AuthUiEvent.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/ResultDialog.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/auth/AuthUiEventPresentationTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/auth/LoginScreenEffectsTest.kt`
- `docs/decisions/2026-10-29-state-hoisting-remember-audit.md`
- #1411

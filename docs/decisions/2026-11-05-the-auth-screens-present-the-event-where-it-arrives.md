---
title: The auth screens present the event where it arrives
date: 2026-11-05
status: accepted
tags: [auth, ui-event, state-hoisting, compose]
---

# The auth screens present the event where it arrives

## Context

`LoginScreen` was changed to present the auth event bus where it arrives — an
`AuthUiEvent.Error` as the shared error dialog, an `AuthUiEvent.Message` as a
snackbar, `AuthUiEvent.NavigateToHome` as navigation — and to keep no copy of
an event. See `2026-11-04-the-login-screen-presents-the-event-where-it-arrives.md`.

The two sibling screens of the same feature were left as they were:
`AttachAccountScreen` and `MagicLinkConfirmScreen` collected the bus with
`CollectEvents` and copied each event into a local `remember` flag — an
`errorMessage` and a `message` — which then had to be cleared by hand wherever
the screen moved on: the mode toggle, the resend, the back button, the submit
path. Each manual clear was a place where a stale message could survive on the
form.

## Idea

- Leave the two screens as they are: they work, and the login screen's shape is
  one of several.
- Give each of the two screens its own copy of the login screen's shape.
- Put the shape in one place and let the three screens use it.

## Decision

All three auth screens present the events where they arrive, and none of them
keeps a flag for a message.

The implementation is `AuthScreenEffects`: it collects the bus once, maps each
event through `AuthUiEvent.toNotification()`, and hands the notifications to
`NotificationHost` over a channel of their own. Each screen names its own
contract over that implementation — `LoginScreenEffects`,
`AttachAccountScreenEffects`, `MagicLinkConfirmScreenEffects` — so a screen's
reaction can be mounted and tested on its own.

The attach screen raises one notice of its own — the refusal when the user has
not answered what happens to their offline data, which is not an event and so
does not travel on the bus. It travels on a channel of its own and joins the
event notifications at the same host, so the screen has one queue of notices
and one dialog.

## Rationale

The flags were a mirror of the bus: two pieces of state whose only content was
what an event had already said, and whose lifetime was maintained by hand. The
two screens were inconsistent with the login screen for no gain — the login
screen's own decision note already said that the state route would have made
the siblings inconsistent with it for nothing, and the same is true of the
copy-the-event route.

A third copy of the login screen's plumbing would have been the third place to
change when the presentation changes, so the implementation went into one
component and the screens kept only their own names.

The per-screen names are not decoration. A screen's reaction is a contract, and
a contract that cannot be mounted on its own cannot be tested on its own: the
desktop tests mount the screen's reaction, hand it an event, and assert that
the user is told.

## Consequences

- An error on any auth screen is a dialog, and a neutral notice is a snackbar.
  A screen that presents an auth event must say which it is.
- No auth screen keeps a `remember` flag for a message, and none clears one by
  hand. Anything the user must still see after the event has passed belongs in
  `AuthUiState`, not in a copy of the event.
- The bus has exactly one collector per screen. The bus is a channel, and a
  second collector takes its share of the events away from the first.
- The inline error and message slots of `AuthFormFields` and
  `ConfirmationPendingContent` are fed by nobody now. Their removal is the
  follow-up, not this change.
- The Maestro flows that asserted an inline auth error had to move to the
  surface that replaced it.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/auth/AuthScreenEffects.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/auth/LoginScreen.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/auth/presentation/AttachAccountScreen.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/auth/presentation/MagicLinkConfirmScreen.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/auth/presentation/AttachAccountScreenEffectsTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/auth/presentation/MagicLinkConfirmScreenEffectsTest.kt`
- `docs/decisions/2026-11-04-the-login-screen-presents-the-event-where-it-arrives.md`
- #1450
- #2085

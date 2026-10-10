---
title: Spotlight onboarding measures its targets through a registry, and the geometry stays out of Compose
date: 2026-10-07
status: accepted
tags: [onboarding, ui, compose, settings]
---

# Spotlight onboarding measures its targets through a registry, and the geometry stays out of Compose

## Context

PR-5 asks for a first-run spotlight tour: dim the screen, cut a hole around a real
element, explain it, move on. Two things in the plan needed deciding before any of it
could be written.

**The anchors are not at a fixed position.** The plan says to anchor through
`Modifier.onGloballyPositioned`. The overlay draws in the window's coordinate space while
the elements it points at live deep inside a screen. Threading a coordinate down to each
element and back up again couples the screen to the onboarding feature.

**The hole has to be cut with a `Path`, and a `Path` cannot be built in a unit test.**
`androidx.compose.ui.graphics.Path` is backed by the graphics stack; constructing one
outside a running composition needs Skia loaded. The first attempt at `spotlightHolePath`
returned a `Path` and therefore could not be tested on the JVM at all — every decision it
made (does the circle cover a wide button, does the radius stay valid, does the morph pass
through a degenerate frame) was untestable, which is most of the risk.

Neither `FocoraRenderer` nor any `onGloballyPositioned` usage exists in this repository,
so the lerp technique the plan refers to had no local exemplar to copy; it is implemented
directly and pinned by tests instead.

## Idea

1. A `SpotlightAnchorRegistry` maps an anchor id to its current root-space bounds.
   `Modifier.spotlightAnchor(id, registry)` is one modifier at the element. The overlay
   reads the same registry. Screens that do not run a tour pass `null` and the modifier
   collapses to itself.
2. `SpotlightHole` is a `Rect` plus a radius — plain numbers. The `Path` is built inside
   the composable from those numbers, and everything testable is tested.
3. `SpotlightTourStateMachine` holds the decisions (may I start, which step, what do I do
   with a target that is not there). The composable only feeds it observations.

## Decision

Implemented as above. Three specific calls:

- **Skip a step whose target is absent rather than wait for it.** A tour that blocks
  because one button is missing puts a scrim over the app with no way out, which is worse
  than a tour one step shorter. The overlay draws nothing while the current target has no
  bounds, rather than drawing a scrim with a hole around nothing.
- **Store one number, the tour version, not a set of seen step ids.** "The content
  changed" means the version changed. A set of seen ids would re-run v1's steps when v2
  adds a step, which is not what that phrase means to the person looking at it. The write
  is monotonic, so two devices disagreeing about the current version cannot make a user
  who has already finished re-watch it.
- **Replay does not clear the stored value.** The value records what the user has been
  *shown*; showing it again does not un-show it. The settings action passes `force` to the
  state machine instead, which is why the repository needs no `reset` at all.

## Rationale

The registry is the difference between one modifier at a button and a coordinate threaded
through three composable layers. It also survives the element moving: the tour does not
know or care which screen the anchor lives on, which matters because the same affordance
is reachable from more than one place.

Keeping geometry as numbers is not a purity argument. A `Path` in commonMain means the
test suite silently grows a graphics dependency, and the alternative was to leave the
arithmetic — which is where the bugs are — untested. `boundsInRoot` rather than
`positionInRoot` for the same reason: the latter gives only a corner, and a hole computed
from a corner is a hole that does not quite cover its button.

Re-registering on every layout pass is deliberate. Bounds change on resize, on keyboard
open, and on scroll; a hole computed once and cached drifts off its target in all three
cases, which reads as a tutorial pointing at the wrong button.

## Consequences

- `AgendaContent` grew a `spotlightRegistry` parameter, nullable and defaulting to `null`,
  so previews and tests are unaffected.
- `SettingsRepository` gained an `onboarding` sub-repository and a namespace, reusing the
  existing DataStore rather than introducing a second "seen it" mechanism.
- `InterfaceSettingsScreen` gained a nullable `onboarding` parameter for the same reason:
  a `koinInject()` default would run during previews, which have no Koin graph, and take
  the preview down instead of degrading.
- The tour's first content points at the two agenda top-bar actions. They are the least
  discoverable things in the app — no label, no menu, no other mention — which is the only
  justification for spending the one moment a new user is most willing to listen.
- `SPOTLIGHT_CARD`'s height is capped so the placement estimate is an upper bound. The
  estimate may choose the roomier side when the card would have fitted either way; it
  never puts the card off-screen.
- The UI has not been run. The geometry and the state machine are tested; the composable's
  appearance on a device is not verified.

## Links

- Plan: `core/onboarding` capability under the `attachment-viewing-and-annotation` change.
- Related: `2026-10-07-the-empty-handler-gate-keyed-on-a-list-of-names`, for the same
  reasoning applied to a control that cannot act.

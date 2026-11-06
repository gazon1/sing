---
title: "The fifth tab slot stays empty: Search is an action, not a place"
date: 2026-11-04
status: accepted
tags: [navigation, bottom-bar, ux, destination-kind]
---

## Context

Promoting `Calendar` to the bottom bar (commit `f73aca39c`) left the bar with four tabs —
Inbox, Today, Upcoming, Calendar — and the Menu button, so five items on a 360 dp phone.
The follow-up issue (#1535, the "fifth slot" in `docs/plans/2026-10-31-ui-ux-audit.md`)
asked whether `Search` should take the fifth tab slot or whether the bar should keep four.

What the base commit says today:

- `DestinationKind.tabs` is four destinations; `DestinationKind.menuEntries` holds
  `Search` among ten others.
- `Search` is already reachable three ways: the shell's top-bar search button, `Ctrl+F`
  on desktop, and the menu sheet.
- The shell renders the bar from `DestinationKind.tabs` plus a `Menu` button, so any tab
  added there lands on both platforms. The desktop drawer lists the same two lists.

The kinds of destination the bar holds are all *places*: a list you return to, whose back
stack persists across a tab switch and whose reselect emits `Nav3State.reselectEvents`.
`Search` is not that shape — it is a question you ask and then leave, and the screen it
opens carries no state worth restoring on reselect.

## Idea

Two shapes were on the table:

1. **Promote `Search` into the bar.** The fifth slot gets used and search is one tap from
   every screen — but only if `Search` grows the persisted stack and the reselect
   semantics that make a tab a place, which nothing else on that screen wants.
2. **Keep four and leave the slot empty.** The bar stays at its ceiling, `Search` keeps
   its three entry points, and the slot stays available for a destination that is a
   place rather than a question.

The second was chosen.

## Decision

**Keep four tabs. `Search` stays a `DestinationKind.menuEntry` and does not become a tab.**

The bar's contract is therefore: four tabs plus the Menu button — five items — and the
fifth tab slot is used by nothing.

## Rationale

*The bar has no room, and spending the last slot is irreversible in practice.* Five items
is the ceiling a `NavigationBar` fits at 360 dp with a legible label and a 48 dp touch
target. A fifth tab makes six. The bar is also the only place a future *place-shaped*
destination can go — `Notes` is the obvious candidate — and a tab that a user asks a
question of would have taken that slot from one that they live in.

*Search already has three entry points.* Adding a fourth and removing none would not make
search easier to reach; it would make the bar's items unequal, one of them behaving
differently from its neighbours (no persisted stack, no reselect semantics).

*The classification is a product decision, not a tidy-up.* `DestinationKind` is the single
place the two platforms read, so moving `Search` there would be a one-line change that
silently ships on Android and desktop both. The KDoc on `DestinationKind.tabs` and the test
`theFifthSlotStaysEmpty` now name this record, so promoting `Search` later is a deliberate
act that has to face the decision rather than a diff that looks like a list edit.

## Consequences

- `docs/navigation.md`'s top-level table is corrected to the layout the code has: four
  tabs, `Plans`/`Pomodoro` as menu entries, `Calendar` on the bar.
- The `DestinationKind` KDoc no longer claims five tabs over a four-item list.
- `DestinationKindTest.theFifthSlotStaysEmpty` pins `tabs.size == 4` and pins `Search` to
  the menu, so a promotion fails a test that names this decision.
- If `Notes` is later promoted into the bar, the fifth slot is available and this record
  is the one to supersede.
- This record does not restate the shell's whole tab-and-menu layout: that statement, in
  the docs and in the code's comments, remains #2150's job.

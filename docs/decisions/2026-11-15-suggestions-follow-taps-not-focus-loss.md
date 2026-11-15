---
title: "The suggestion row follows taps, not focus loss"
date: 2026-11-15
tags: [search, saved-search, ui, focus]
status: accepted
---

# The suggestion row follows taps, not focus loss

## Context

Issue #2944 asks the search screen to "dismiss suggestions on outside tap or focus
loss without clearing or changing the query". The two triggers were read as one
rule: the field losing its focus is the person leaving the field, so the row goes.

The first implementation did exactly that — `onFocusChanged(false)` hid the row.
It made the row's own controls unusable. On the desktop the field loses its focus
**on the way down** of any press outside it, so the chip under the finger was
removed from the tree before the press was lifted; its `onClick` never ran, and a
long press could never reach its timeout. Tapping a saved search did nothing on
desktop. The same screen was fine on Android, where a press outside a field does
not take its focus at all — which is the second half of the problem: the trigger is
not one rule, it is two platform behaviours, and on one of them it is unusable.

Three ways out were considered.

**A — keep focus-loss, but defer it past the press.** Hold the dismissal until the
pointer is lifted. That needs a hand-written observer of the whole pointer
lifecycle (down, movement, lift, cancellation, several pointers) feeding a second
piece of state, so that a dismissal can wait for a press it did not start. The
screen would then depend on gesture bookkeeping for a visibility rule.

**B — dismiss on the window losing focus instead of the field.** `WindowInfo` has
`isWindowFocused`, and it is what "the person left" would mean at the top level.
But its own documentation says it is the *window hosting this hierarchy*, and that
"if a popup or dialog is visible, this property can be used to determine if the
current window is in focus" — i.e. it flips when **this screen's own** context menu
or dialog opens. The row would collapse the instant a chip's menu appeared, which
is the one transition the issue says must keep it: "opening the context menu does
not hide the suggestions".

**C — dismiss on a tap, with the framework's tap detector.** `detectTapGestures`
on the results area: it cancels on a drag (a scroll is not a tap), handles
cancellation and several pointers itself, and lets every control under the finger
take the press first — a tap on a result card never reaches it, so the card's own
click is what runs. Tapping a result dismisses through that card's handler, in the
same click that navigates. Focus *gaining* still shows the row.

## Decision

**C.** The row is dismissed by taps and by the screen's own explicit actions
(selection, mode change, the filter sheet, the save and rename dialogs, a tap on a
result), and it is brought back by the field being emptied and by the field being
focused again. Focus **loss** is not a trigger.

The contract, as implemented:

| Event                            | Suggestions                                     |
| -------------------------------- | ----------------------------------------------- |
| The screen opens                 | shown                                           |
| The field gains focus            | shown                                           |
| The field loses focus            | unchanged — the tap that did it decides         |
| A saved search is selected       | hidden; the stored query is loaded unchanged    |
| The field is cleared             | shown                                           |
| The search mode changes          | hidden; the query is unchanged                  |
| The filter sheet opens           | hidden                                          |
| The save dialog opens            | hidden                                          |
| The rename dialog opens          | hidden; the query is unchanged                  |
| A chip's context menu opens      | stays                                           |
| A chip's context menu closes     | stays; the next tap decides                     |
| The chip whose menu is open goes | the menu closes with it                         |
| A result is tapped               | hidden, and the result opens                    |
| A result is dragged (scrolled)   | stays                                           |
| A control inside a result (pin)  | stays, and the control runs                     |

One distinction survives from the original requirement and is not a detail: an
empty field can mean "the person cleared it" (show the row) or "a saved search
with an empty query was loaded" (stay dismissed). The screen tells them apart by
the loaded search — `activeSavedSearchId` — not by the emptiness alone, and it
reads emptiness from the field's own text, which is what the person sees.

## Rationale

The tap is the event that carries the intent. It is also the event the framework
already recognises, including the parts that are easy to get wrong by hand —
cancellation, several pointers, a press that became a drag. The focus signal
carries neither the intent nor the right timing: on desktop it fires before the
press it belongs to has done anything, and on Android it does not fire at all.
Choosing it forced either a broken row or a gesture tracker that existed only to
undo the choice.

The cost of the decision is one behaviour the issue's wording implies and this
screen does not implement: leaving the window (another app, another desktop
window) does not hide the row. There is no user-visible defect behind it — the row
is still the correct answer when the person comes back — and the alternative (B)
collapses the row on this screen's own menus.

## Consequences

- The row's visibility is one piece of state, set by the actions that dismiss it
  and by the two ways the field brings it back. Nothing waits for a press to end.
- A control inside the row or inside a result keeps its own click: the dismissal
  is either the control's own callback or a tap the control did not claim.
- A scroll does not hide the row, because the framework's tap detector cancels on
  a drag rather than reporting one.
- Window-level focus loss is deliberately not handled. If a product decision
  later wants it, it has to be told apart from this screen's own popups and
  dialogs, which needs state the screen does not keep today.
- `activeSavedSearchId` stays the only way the screen tells a load from a clear.
  A second way to remember the query would be a second source of truth for what
  the field says.

## Links

- Issue #2944 (the acceptance criteria this amends), parent #2940.
- `2026-11-14-the-saved-searches-row-is-the-suggestion-surface.md` — why the row
  is the suggestion surface and not a second list.
- `2026-11-12-a-saved-search-carries-the-query-that-runs.md` — what the loaded
  query is.

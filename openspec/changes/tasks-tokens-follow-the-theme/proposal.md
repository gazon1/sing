# tasks-tokens-follows-the-theme

**Status:** proposed · **Issue:** #198 (applied, not archived — awaiting visual review)

## What

The task list and task detail surfaces SHALL take their colours from the app's
active theme, in both the light and the dark case, and SHALL stop carrying a palette
of their own.

Twenty-seven fixed colour values across two token collections drive seventeen
production files, and neither collection is capable of responding to the theme —
they are fixed values, not anything the theme can reach.

## Why

Three consequences, all pre-existing and all invisible in review:

1. **A light-theme user gets a dark task screen.** Dark mode is a user setting that
   defaults to off, so this is the shipped default, on the app's primary screen.
2. **The accent picker does nothing on these surfaces.** The accent colour is written
   into the token collection as a literal blue, so a user who picks pink sees a blue
   task editor. This is the same defect the seed-palette change fixed at the app's
   root, still standing in the feature.
3. **The documentation states the opposite of the truth.** The task-list token
   collection is documented as giving "one source of truth when the theme changes" —
   it is a single hardcoded truth that cannot change with the theme. A future agent
   reading that comment will trust a guarantee that does not exist.

Point 2 is the one that makes this urgent rather than merely untidy. The accent
picker became genuinely functional app-wide in the seed-palette change; on the task
screens it is now a control that visibly does nothing, which is worse than a picker
that was never there.

## Scope

**In scope:** the two task token collections and the seventeen files that read
them; converting both to theme-derived values; and the review across every accent
and mode.

*As applied, the conversion took a different shape than first planned: no
`CompositionLocalProvider` and no token data class. Every one of the 27 values is
a role the theme already provides, so a token class would have re-published
`MaterialTheme.colorScheme` in a narrower shape, with a missing-provider failure
mode attached. Composables read the scheme directly; the four values that need
arithmetic live in one small file. `CalendarPalette` keeps its data class because
most of its sixteen fields are not scheme roles — this one did not need one.*

**Out of scope, deliberately:**

- **The priority hues.** Four fixed values on task chips answer "how urgent is this",
  which is a semantic scale, not a theme role. Converting them would tie priority
  legibility to an arbitrary accent. REQ-THEME-006 exists to hold this line.
- **The calendar palette, which has the same shape of defect at a quarter of the
  size.** #197. Same principle, separate change: the task surfaces are the larger
  review and mixing them would make neither reviewable.
- **Converting the other four screens that carry the same defect.** #199. The
  colour gate landed *with* this change and allowlists those files, so it could
  ship without blocking the very build that performs the conversion; the
  allowlist is meant to shrink toward one permanent entry.

## Risks

**Rollback is a revert, and that is fine here** — the tokens are presentation-only
with no persistence or protocol surface. The real risk is not rollback but
**invisibility**: there is no screenshot baseline and no snapshot test in this
repository, so a bad contrast regression cannot be caught by any gate. It will be
found by a person, or not at all. That is the reason the review tasks are blocking
rather than advisory.

The second risk is **scope drift into the calendar**, which is the obvious adjacent
cleanup and the one most likely to be bundled silently. It is excluded above on
purpose.


---

## What applying this found

Two things the proposal did not anticipate, both recorded in the deferred backlog
entry for #198 and now in the spec:

- **Priority was three scales, not two.** The two the project documents differ on
  purpose. The third — a component's own four values, never registered in the
  named palette — was an accident, and it lived in a function with the same name
  as the canonical one in a sibling package, carrying different values. Nothing
  flagged it. REQ-THEME-007 now requires exactly one resolver, with screen
  identity as a named argument.
- **The two documented palettes were near-duplicates that had drifted.** Their
  surfaces were identical; their backgrounds and text primaries were not. Two
  hand-maintained copies of one palette. There was no need to unify them by hand
  — resolving both from the same scheme makes them identical by construction.

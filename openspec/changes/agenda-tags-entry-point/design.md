# agenda-tags-entry-point — Design

Required: new user-facing route to an existing capability, and a product
decision that changes the shape of the result.

## Where the entry point goes

Three candidates were considered:

1. **Tags screen overflow menu, single tag.** A tag's menu offering "show as
   agenda". This is one tag only, so it does not reach the multi-tag case that
   the backlog entry is actually about. It also duplicates the existing route
   from a search result's tag chip.

2. **Tags screen, multi-select mode.** Selecting N tags then acting on them.
   Requires a selection model on the Tags screen that does not exist today, and
   the screen has a long-press affordance already used for something else.

3. **Search results, "view these as an agenda".** The search screen already
   produces a filtered set and already has a tag facet. The action applies to
   *whatever* produced the set, not only to tags, so it generalises: the same
   action serves a project filter, a priority filter, and a date range.

**Recommendation: option 3, with the tag case as its instance.** The
multi-select problem does not need solving, because the search screen already
solved the equivalent of it, and a tag-only entry point is a special case of it
that would have to be built twice.

**Recommendation: option 1 as well, as a one-line shortcut into the same
destination.** A single tag is a legitimate case and a five-step path to it is
not defensible — but it should be a shortcut to the same place option 3 lands,
not a separate implementation. Two implementations of the same screen will
diverge, and the single-tag path is the one most likely to be forgotten when the
multi-tag behaviour changes.

## Transient or persisted

This is the open decision, and it has a cost either way:

- **Transient.** Nothing to name, nothing to save, no clutter. But a multi-tag
  view is precisely the thing a user builds once and reuses; a transient agenda
  they must rebuild each time is a worse experience than the five-step path,
  because it looks like it should have been saved and was not.
- **Persisted.** The view is kept and reusable. But every "open the tags
  screen" action now risks creating a saved view the user did not ask for, and
  the saved-views list fills with near-duplicates.

**Recommendation: offer the choice at the point of action, defaulting to
transient.** The action opens the agenda immediately — no decision required for
the common case — and offers "save this view" from the resulting screen, where
the user has just seen what they are saving. This also makes the transient case
the one that is built first and cannot be a trap.

## Single-tag must not fork

The existing single-tag route already exists and already works. Whatever this
change adds, a single tag must reach the same screen with the same behaviour as
a multi-tag selection. If they are two paths, the single-tag path is the one
that stops being maintained.

## Reference

The configurator's own architecture is recorded in
`docs/decisions/2026-10-04-multi-select-sheet.md` and is not restated here. This
change reuses the multi-select primitive it defines; it does not modify it.

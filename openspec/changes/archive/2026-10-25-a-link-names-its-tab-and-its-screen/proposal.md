# A link names its tab and its screen

Issue: — · Related: `docs/decisions/2026-10-21-deep-link-registry.md`

## What

Make a deep link say where it goes, and make one registry decide what a URI
opens.

The app understood three links, and each of them was understood somewhere else:

1. `singularity://task/{id}` — a calendar event description. The format was
   written in three places at once: the producer, a constant nobody read, and a
   second parser that read the link out of free text by hand.
2. `singularity://verify?email=&token=` — a magic link. It was read by pulling
   two query parameters out of *any* intent data, without checking the scheme
   or the host.
3. `singularity://view/{viewId}` — a reminder notification. The payload was a
   private extra on the launch intent, and nothing on the receiving side knew
   the link's shape, because it was not a link.

The receiving side was one Activity that parsed four `String?` values and
handed them to `App(...)`, which opened a screen with `navigator.open(...)`.
That pushed the target onto the stack of whichever tab the app happened to be
on, so "back" returned to an unrelated screen.

Concretely:

1. A link resolves to **two** keys — a tab and a screen — and the tab's stack
   becomes exactly `[tab, screen]`. "Back" from a link returns to the tab the
   link named.
2. The feature that owns a link declares **both** its URI grammar and its
   target. The producer and the matcher read the same spec, so the format has
   one home instead of three.
3. One registry answers "what does this URI open?", from an **explicit ordered
   list** of the app's links.
4. The Activity stops parsing. It builds a request and asks the registry; the
   registry's matcher owns the grammar.
5. An unknown link is a quiet no-op: the app stays on its start tab.

## What is not in this change

- The links themselves do not change their spelling. `singularity://task/{id}`
  still means the same task.
- The app does not gain a web-facing link scheme. These are custom-scheme links,
  owned by the app, not by a domain.
- The magic link's *redirect* is configured in the Supabase dashboard, outside
  this repository. This change gives the app the ability to recognise the link;
  it cannot make the backend send it.
- The wikilink dialect (`task://`, `note://`) is a separate task — two
  extractors and an untested click path, closed separately.

## The change in behaviour

A deep link now opens a screen **on a named tab**, and "back" returns to that
tab's own screen. Before, it opened on whatever tab the app was on, and "back"
returned to an unrelated screen.

## Why now

The links are the app's only public surface. A format written in three places
and parsed by hand is one change away from a link that stops opening, and
nothing tested any of it.

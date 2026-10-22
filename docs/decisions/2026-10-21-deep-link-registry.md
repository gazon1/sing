---
title: A deep link names its tab and its screen, and the registry owns the grammar
date: 2026-10-21
adr-number: 2026-10-21-deep-link-registry
status: accepted
tags: [navigation, deep-links, architecture]
---

# A deep link names its tab and its screen, and the registry owns the grammar

## Context

The app understood three links, and each of them was understood somewhere else:

- `singularity://task/{id}` — a calendar event description. The format was written
  in three places at once: `CalendarSyncEvent.deepLink` (the producer),
  `GoogleEventJson.DEEP_LINK_PREFIX` (a constant nobody read), and
  `AndroidCalendarProvider.extractTaskId` (a second parser, reading the link out
  of free text by hand).
- `singularity://verify?email=&token=` — a magic link. It was read in
  `MainActivity` by pulling `email` and `token` out of *any* intent data, without
  checking the scheme or the host. A link with the right query parameters but the
  wrong authority was a magic link.
- `singularity://view/{viewId}` — a reminder notification. The payload was a
  private extra on the launch intent, and nothing on the receiving side knew the
  link's shape, because it was not a link.

The receiving side was one Activity that parsed four `String?` values and passed
them to `App(...)`, which opened a screen with `navigator.open(...)`. That pushed
the target onto the stack of whichever tab the app happened to be on, so "back"
returned to an unrelated screen.

There was no test for any of it: the intent parsing, the reverse parser, the
desktop CLI flags — none were covered.

## Idea

Give the deep link a shape that says where it goes, let the feature that owns a
link declare both its grammar and its target, and let one registry answer "what
does this URI open?".

## Decision

1. **A deep link resolves to two keys: a tab and a screen.**
   `DeepLinkTarget(tab, screen)`. Not a list — the app-level keys are always
   graph wrappers, so the synthetic back stack is exactly two levels deep. A list
   would admit states the app cannot produce (a stack with no root, a root that is
   not a tab) without adding a single capability.

2. **The feature owns its link.** `TaskLinkSpec`, `AgendaLinkSpec` and
   `AuthLinkSpec` hold the URI template and the reverse parsing. The producer
   (calendar sync) and the consumer (the matcher) read the same spec, so the
   format has exactly one home. `CalendarSyncEvent.deepLink`,
   `GoogleEventJson.DEEP_LINK_PREFIX` and `AndroidCalendarProvider.extractTaskId`
   are gone.

3. **The registry is an explicit ordered list.** `DeepLinkRegistry(listOf(...))`,
   built once in `feature/nav/deeplink/DeepLinkDiModule.kt`. Not `getAll()`:
   the order of the checks must be visible in the code, and `koin-compiler-plugin`
   flags ambiguous multi-bindings.

4. **Matching uses the official API.** `UriDeepLinkMatcher` over
   `androidx.navigation3.runtime.deeplink`, with a `@Serializable` key whose
   property names are the URI template's placeholders. The key holds `String`
   fields; the typed identifier is created at the boundary.

5. **A link sets the tab and the stack.** `Nav3State.openDeepLink` clears the
   tab's stack and writes `[tab, screen]`, then switches to that tab. The link
   bypasses `NavigationPolicy` on purpose: the policy describes transitions
   *inside* the app, and a deep link is an entry from outside with an explicit
   parent.

6. **`App` takes one object.** `App(deepLinkTarget: DeepLinkTarget?)` instead of
   four `String?`. The four strings were four answers to one question, and the
   Activity had to know how to spell a link to answer it.

7. **Unknown links are a quiet no-op.** A URI that matches nothing leaves the app
   on its start tab. A malformed URI does not throw. The app opens links from
   outside; it does not fail on them.

## Rationale

- **The owner of a link is the feature that opens it.** A task link is written by
  calendar sync and read by the tasks feature — two features that do not know
  about each other except through the format. Putting the format in one of them
  would make the other depend on the wrong module.
- **Two keys, not a list.** The synthetic stack has to have a root that is the
  tab, or "back" has nothing sensible to return to.
- **The order is a decision, not an accident.** Two templates can accept the same
  URI; "the best match" is undefined in nav3's own comparison rules, so the
  registry must not delegate the choice to a container's iteration order.
- **One parser.** A format written in three places is three formats waiting for
  the first change that touches one of them.

## Consequences

- **Always** add a link in three places: the `*LinkSpec` of the owning feature,
  the `AppDeepLink` implementation beside it, and the ordered list in
  `deepLinkModule()`. The link's screen is reached only through that list — a
  matcher that is not in the list is unreachable, and the registry test in
  `DeepLinkRegistryTest` is what proves the list still works.
- **Never** hand-parse a URI in an entry point. The grammar belongs to the
  feature; the entry point builds a request and asks the registry.
- **Never** read a query parameter without checking the scheme and the host.
  The magic-link reader did, and would have accepted any intent carrying
  `?email=`.
- **Always** keep a link's producer and its matcher on the same spec. A link
  written by one build and read by another is a silent dead end.
- A deep link now changes the tab. That is a behaviour change, and it is the
  point: "back" from a link now returns to the tab the link named instead of to
  wherever the app happened to be.
- The magic link only works once the Supabase dashboard's redirect is configured
  for the `singularity://verify` host. That configuration is outside this
  repository; the ADR records the boundary rather than pretending the link is
  reachable from the code alone.
- The notification link still carries the old extra as well as the new URI, for
  the migration window: a notification posted by an older build carries only the
  extra. Filling `Reminder.viewId` in production is a separate task, tracked in
  the issue registry.

## Links

- ADR `2026-10-21-nav3-1-2-0-upgrade` — the runtime that brought the deep-link
  API.
- OpenSpec change `a-link-names-its-tab-and-its-screen` — the requirement added
  to `navigation-open-policy`.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/deeplink/` — the
  registry.

## 1. The registry

- [x] `DeepLinkTarget` names a tab and a screen — two keys, not a list
- [x] `AppDeepLink` is one link declared by its feature
- [x] `DeepLinkRegistry` answers from an explicit ordered list
- [x] The registry is bound once, in `feature/nav/deeplink/DeepLinkDiModule.kt`

## 2. The links own their grammar

- [x] `TaskLinkSpec` — the task link, in both directions
- [x] `AgendaLinkSpec` — the saved-view link
- [x] `AuthLinkSpec` — the magic link
- [x] The calendar sync writes the task link from the spec
- [x] The old producer, the dead constant and the hand-written parser are gone

## 3. The matcher

- [x] `TaskDeepLink`, `AgendaDeepLink`, `AuthDeepLink` over the official matcher
- [x] The key holds strings; the typed identifier is created at the boundary

## 4. The link opens a tab and a stack

- [x] `Nav3State.openDeepLink` writes `[tab, screen]` and switches to the tab
- [x] `App` takes one `DeepLinkTarget` instead of four strings
- [x] The link bypasses the navigation policy on purpose

## 5. The entry points

- [x] The Activity builds a request and asks the registry; it parses nothing
- [x] The manifest filters `task`, `view` and `verify`
- [x] The notification carries a URI, not a private extra
- [x] The desktop CLI takes `--deeplink=<uri>` and resolves it through the registry

## 6. The tests

- [x] The link table: task, view, verify
- [x] Unknown host, other scheme, missing argument, extra path segment — no match
- [x] The order of the checks: the first matching link wins
- [x] The spec round-trips, in a description and on its own
- [x] The link sets the tab and the stack; back does not teleport
- [x] The registry resolves from the real DI graph

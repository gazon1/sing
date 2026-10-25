## 1. The measurement

- [x] `NestedEntryViewModelScopeTest` — does a nested entry share the outer ViewModel store?
- [x] The answer on Desktop: it does. The decorator is mandatory on both platforms.

## 2. The seams

- [x] `rememberAppNavBackStack` — saved state on Android, in memory on Desktop
- [x] `SystemBackHandler` — the system back gesture on Android, a no-op on Desktop
- [x] `rememberAppEntryDecorators` — the ViewModel-store decorator, on both platforms
- [x] The two `expect`/`actual` `*BackHandler` triples are gone

## 3. The graphs

- [x] `TasksNavGraph`, `ProjectsNavGraph`, `NotesNavGraph`, `CalendarNavGraph`,
      `SearchNavGraph`, `SettingsNavGraph`, `AgendaNavGraph` — one common implementation each
- [x] The fourteen platform copies are gone

## 4. The agenda's menu

- [x] `AgendaContextMenuHost` — the gesture is a parameter
- [x] The twelve agenda entries live in common code
- [x] Android gains blocked, stagnant and overdue
- [x] `AgendaContextMenuParityTest` holds the list

## 5. The entries

- [x] One contributor per feature under `feature/nav/entries/`
- [x] The four `expect`/`actual` `*EntryProvider` pairs — dead code — are gone
- [x] The platform files are assemblers
- [x] `AppEntryParityTest` holds the set of registered destinations

## 6. The documents

- [x] `docs/navigation.md` — the new layout, the seams, where a graph is registered
- [x] `docs/PLATFORM-REFERENCE.md` — the rule is "your own feature's contributor"
- [x] ADR addendum to `2026-10-02-nav-entries-dedup-deferred` — the premise was wrong
- [x] ADR `2026-10-21-agenda-context-menu-parity`

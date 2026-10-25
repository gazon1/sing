# One nav graph per feature

Issue: — · Related: `docs/decisions/2026-10-02-nav-entries-dedup-deferred.md` (addendum 2026-10-21),
`docs/decisions/2026-10-21-agenda-context-menu-parity.md`

## What

The seven nested navigation graphs were written twice — once for Android, once for
Desktop — and the two copies differed in four ways: the stack's storage, the system
back gesture, the ViewModel-store decorator, and whether a hoisted stack was honoured.

Three of those are now seams, and the fourth turned out not to be a difference at
all but a bug. The result is one implementation per graph, in common code.

1. **One graph per feature, in `commonMain`.** `TasksNavGraph`, `ProjectsNavGraph`,
   `NotesNavGraph`, `CalendarNavGraph`, `SearchNavGraph`, `SettingsNavGraph` and
   `AgendaNavGraph` are one file each, in common code.
2. **Three seams carry the platform differences.** `rememberAppNavBackStack` (saved
   state on Android, in memory on Desktop), `SystemBackHandler` (the system back
   gesture on Android, a no-op on Desktop) and `rememberAppEntryDecorators` (the
   ViewModel-store decorator, now on both platforms).
3. **The agenda's context menu is a parameter.** The gesture stays the platform's
   — a bottom sheet on Android, a popup on Desktop — while the list of sections
   that show it moves to common code. Android gains the three sections it was
   missing: blocked, stagnant and overdue.
4. **The entries live with their feature.** Each feature contributes its
   app-level entries from `feature/nav/entries/`; the platform files became
   assemblers.

## What is not in this change

- **No new destination.** The set of app-level destinations is unchanged — 22
  entries, the same as before, on both platforms.
- **No change to the outer navigation.** The top-level stacks, the policy, the
  tab behaviour and the back stack persistence are untouched.
- **The tasks graph entry stays platform-specific.** Desktop seeds the pushed
  graph's stack itself and pushes the detail route onto it. That one is a real
  difference, and it is the only `entry<>` block left outside the common
  contributors.

## The change in behaviour

- **Desktop now scopes the ViewModel store per entry**, like Android already did.
  Before, two entries of a Desktop nested graph shared one store, and Koin keys a
  ViewModel by its class alone — so the second screen reused the first one's
  ViewModel, with the first one's parameters.
- **Android now shows the context menu in the blocked, stagnant and overdue
  sections**, like Desktop already did.

Both were divergences, and both were accidental.

## Why now

The two files were near-identical, so a difference between them looked intentional.
Nobody compared them, because comparing them was not a step in any workflow — and
the difference that mattered was in the one file that had no copy to be compared
against.

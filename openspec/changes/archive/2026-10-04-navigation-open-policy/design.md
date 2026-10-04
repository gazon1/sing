# design: navigation-open-policy

## Context

Multi-back-stack Nav3 shell: top-level destinations own stacks; nested feature graphs render
inside their top-level entry. Cross-feature opens currently flow through `NavCallbacks` +
per-origin allow-list branches duplicated in the Android and JVM entry providers (5 branches
per platform), with silent fall-through to "go back". Architecture rationale and the full
decision record live in ADR
`docs/decisions/2026-10-04-navigation-policy.md` (this design references it and does not
repeat its rationale).

## Decisions (summary, see ADR)

1. `NavigationPolicy.resolve(from, to): OpenAction` — pure commonMain function, sealed
   `OpenAction = SwitchTab | Push | ExitAndOpen`, exhaustive `when` (no `else`).
2. Shell `Navigator.open(target)` computes the current context and applies the policy;
   `close()` replaces the null-dest branch. `NavCallbacks.navigate` delegates to `open` —
   ~50 call sites keep compiling, entry providers collapse to one uniform callback.
3. `familyOf(key): ScreenFamily` — exhaustive classification, no mutable registry.
   `DestinationKind` stays the source of tabs/menu.
4. Typed entity ids on five route properties; `rememberNavBackStackTyped` expect/actual
   factory encapsulates the single unchecked cast (android) / repeated fallback (jvm).

## Behaviour deltas (intentional)

Cross-feature opens that today fall through an allow-list to "go back" will open instead
(project → task from the Plans tab; task → linked note; calendar → project detail). All
today-working paths (`nav.navigate` branches) resolve identically: top-level → tab switch,
otherwise push onto the current stack — matching `Navigator.navigate` semantics exactly.
The B1 companion fix `NotesNavigator.openTask` currently opens the task *create* screen for
a task id; it becomes the task detail.

## Rollback risk — HIGH, bounded

B1 rewires the two platform entry providers + `Navigator`. Rollback: restore the previous
`onExitGraph` lambdas in `AndroidNavEntries.kt` / `JvmNavEntries.kt` and `Navigator.navigate`
body (they are self-contained, ~150 lines total); the policy unit tests stay green
independently and can be kept during a retry. B2 touches serialization of five route
properties — rollback is `git revert` of the property types, guarded by the
`NavSavedStateConfigTest` round-trip inventory which must pass BEFORE merge (see tasks).
No Room schema, no persisted-format change (saved-state stores the same JSON strings —
value classes serialize transparently).

## Test seams (existing, no new seams)

| Seam | What it proves | Where |
|---|---|---|
| desktop flow robots + `PlatformParityTest` | navigation behaviour end-to-end | desktopApp jvmTest |
| policy unit tests | (from, to) → action table, error case | shared jvmTest/commonTest, prior art `Nav3StateReselectTest` |
| round-trip inventory | serialization of typed ids / keys | `NavSavedStateConfigTest`, `NavKeyRegistrationTest` |

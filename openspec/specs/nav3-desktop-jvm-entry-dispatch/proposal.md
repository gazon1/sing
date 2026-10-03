# nav3-desktop-jvm-entry-dispatch

## What

Document the Nav3 navigation dispatch rules for the JVM Desktop shell, specifically: how `NavDisplay` resolves which screen to render when a nested graph entry is entered, and the obligations on callers that pass a `start` route to a graph whose back-stack seed differs from that route.

This is a **baseline spec** — the system already behaves this way. The bug that prompted this spec (wrong screen rendered when navigating from Agenda to task detail) has been fixed. This spec captures the rules so the bug cannot recur silently.

## Why

The `NavDisplay` component used by both Android and JVM renders based on `backStack.top`, not the `start` parameter passed to a nested graph. This is correct and expected behavior, but it creates an obligation: when a caller enters a nested graph with a `start` route that differs from the stack's seed, the entry block must update the stack before rendering, otherwise `NavDisplay` renders the wrong screen.

The bug occurred because `JvmNavEntries.TasksGraph` entry was created with a stack seeded `Create(null)` but received `Detail(taskId)` as the outer route's `start`. The entry block passed `start = Detail(...)` to `TasksNavGraph` but did not add `Detail` to the stack, so `NavDisplay` rendered `Create(null)` — an empty task editor — instead of the task detail screen.

The fix (explicit `backStack.add(detail)` when `start` is `Detail`) is documented here so it cannot regress.

## Scope

### In scope

- `NavDisplay` dispatch rule: renders `backStack.top`, not `start`
- JVM Desktop entry-block obligations when `start` route type differs from stack seed
- `onExitGraph` contract for cross-graph navigation
- Tab reselect behavior and `reselectEvents`
- Back navigation contract (`size <= 1` → exit graph)

### Out of scope

- Android saved-state restoration behavior (platform difference, not desktop-specific)
- `NavBackStack` implementation details (opaque to callers)
- Specific route type conversions (`TasksStartRoute` → `TasksRoute`, etc.) — those are covered by individual feature specs
- Screen-level navigation within a nested graph (handled by each graph's own `*Navigator`)

## References

- `docs/decisions/2026-10-03-nav3-backstack-top-vs-start-dispatch.md`
- `docs/decisions/2026-09-29-single-sealed-navkey-root.md`
- `docs/decisions/2026-09-16-nav3-type-asymmetry-adr.md`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/Nav3SavedState.kt` — `rememberInMemoryNavBackStack`
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/nav/JvmNavEntries.kt` — all JVM entry blocks

## Status

**Active.** This is a baseline spec — no implementation required beyond the fix already applied.

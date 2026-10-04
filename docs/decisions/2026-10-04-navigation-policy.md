---
title: "Navigation as policy: single open-decision function + typed route ids"
date: 2026-10-04
status: accepted
tags: [navigation, nav3, architecture, testing]
---

## Context

The Nav3 shell answers "may current screen X open target Y?" in five per-origin allow-list
branches, each duplicated across `AndroidNavEntries.kt` and `JvmNavEntries.kt`, while three
feature graphs (notes, search, settings) bypass the allow-list and connect the full
dispatcher directly. Consequences observed in code review:

- Cross-feature opens fall through to `goBack()` and are silently dropped (task → linked
  note, project → task from the Plans tab, calendar → project detail).
- A new cross-feature action requires edits in two platform files; platforms drift.
- Outer routes carry raw `String` entity ids; `NotesNavigator.openTask` passes a task id
  into the *create* route — type-safe at the call site, wrong at the destination.
- 7 `@Deprecated` members of `AppDestination` remain in the production mapping
  (`TasksStartRoute.Inbox/Upcoming → TasksRoute.Create(null)`) and in icon `when` branches.
- 7 unsafe `as NavBackStack<X>` casts (each with a `@Suppress("UNCHECKED_CAST")`) sit in
  the androidMain graph files.

Relevant prior ADRs: `2026-09-29-single-sealed-navkey-root` (one sealed key root),
`2026-10-03-nav3-backstack-top-vs-start-dispatch` (dispatch by stack top),
`2026-10-02-nav-entries-dedup-deferred` (entry-provider dedup deliberately deferred).

## Idea

Centralise the open decision in one testable place, and make the compiler carry the
structural knowledge (whose graph is which, which id belongs to which entity) instead of
convention. Candidate shapes considered: a mutable `ScreenHierarchy` registry
(`KClass → Set<KClass>`), a per-feature allow-list table, and a pure decision function.

## Decision

1. **`NavigationPolicy.resolve(from: AppNavKey, to: AppNavKey): OpenAction`** — pure
   commonMain function, `OpenAction = SwitchTab | Push | ExitAndOpen` (sealed), exhaustive
   `when` without `else`. Rules: top-level target → `SwitchTab`; bare start-route target
   (a nested feature's fragment addressed without its graph wrapper) → descriptive error
   naming source and target; same family → `Push`; otherwise → `ExitAndOpen`
   (= push the graph onto the current stack — exactly today's `nav.navigate` semantics).
   No mutable registry: classification knowledge lives in the sealed hierarchy.
2. **Shell facade `Navigator.open(target)` / `Navigator.close()`** is the single consumer
   of the policy. `open` derives `from` from the current stack top; `NavCallbacks.navigate`
   delegates to it, so per-feature navigators and platform entry providers keep their
   typed-`AppDestination` signatures. Entry providers collapse to one uniform
   `if (dest == null) close() else open(dest)` callback — the duplicated `when(dest)`
   allow-lists are deleted.
3. **`familyOf(key: AppNavKey): ScreenFamily`** — exhaustive classification function.
   `DestinationKind` remains the source of tabs/menu entries. No `ScreenHierarchy` map.
4. **Typed entity ids** on five route properties (`TasksStartRoute.Detail`,
   `TasksByProject`, `NotesStartRoute.Preview`, `NotesStartRoute.EditorForTask`,
   `ProjectsStartRoute.Editor`) using the existing `@Serializable @JvmInline value class`
   ids — transparent JSON, saved-state round-trip guarded by inventory tests.
   `sectionPrefillKey` and `CalendarStartRoute.Month(anchor)` stay `String` (not entity ids).
5. **`rememberNavBackStackTyped`** (expect/actual) encapsulates the android unchecked cast
   and the jvm in-memory fallback; the 7 graph files lose their `@Suppress` cast pairs.
6. **B1 companion fix:** `NotesNavigator.openTask` opens `TasksStartRoute.Detail(taskId)`
   (currently `Create()`).
7. **FabActionResolver stays** — it is presentation dispatch (which FAB to render), not
   routing. **Deprecated members are deleted only after a live-reference grep**, updating
   tests that use them as fixtures.

## Rationale

- One pure function is unit-testable without Compose and cannot drift between platforms
  (parity by construction, REQ-7).
- Exhaustive `when` turns "a new screen must be classified" into a compile error — the
  failure mode a mutable registry cannot provide.
- Sealed-hierarchy structure already answers "whose graph"; a second registry would be
  state to keep in sync for zero added information.
- Value-class ids give compile-time id-type safety without changing the persisted JSON
  schema, so process-death restore is unaffected.

## Consequences

- Cross-feature opens previously swallowed by allow-list fall-through start working
  (intentional bug fix, covered by desktop flow tests).
- Adding a new route requires classifying it in `familyOf` and, if cross-feature targets
  exist for it, adding policy cases — a compile error, not a review comment.
- The policy table is the single place to audit navigation rules; platform entry files
  differ only in stack persistence (saved-state vs in-memory).
- Follow-up (out of scope): dedup of Android/Jvm entry providers per
  `2026-10-02-nav-entries-dedup-deferred`, criterion re-evaluated after this change.
- Rollback: bounded to the two entry providers + `Navigator` body; typed-id revert guarded
  by the round-trip inventory.

## Links

- OpenSpec change: `openspec/changes/navigation-open-policy/`
- GitHub issue: #28 «Navigation as policy: unified open/close + typed ids»
- `docs/decisions/2026-09-29-single-sealed-navkey-root.md`
- `docs/decisions/2026-10-03-nav3-backstack-top-vs-start-dispatch.md`
- `docs/decisions/2026-10-02-nav-entries-dedup-deferred.md`
- Skills: `singularity-todo-nav3-nested-graphs`, `singularity-todo-nav3-savedstate`,
  `singularity-todo-cross-feature-navigation` (rewritten to Nav3 in the same change)

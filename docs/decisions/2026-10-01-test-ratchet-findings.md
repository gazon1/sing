---
title: "Post-test-ratchet findings: structural gaps found during MR-10..14"
date: 2026-10-01
tags: [test-coverage, architecture, mr-followup]
status: accepted
---

# Post-test-ratchet findings

Issues discovered while implementing MR-10..14 (agenda test coverage) that are not
part of the original plan but were observed during implementation.

## Desktop navigation — 3 bugs found, 2 fixed, 1 deferred

See `2026-10-01-desktop-nav-followup.md` for full details. Summary:

1. **Fixed**: Shell FAB hijack — `fabActionForNav3` was keyed on `topLevelRoute` (tab)
   instead of the topmost nested screen, so the shell's "Add task" FAB overrode
   nested screens' own FABs.
2. **Fixed**: `goBack()` at tab root always teleported to `startRoute` (Today),
   ignoring the user's actual previous tab.
3. **Deferred** (`nested-back-stack-lost-on-tab-switch`): Nested `NavBackStack`
   is created via `remember` inside entry content, so tab switches destroy it.
   Requires hoisting the stack out of entry scope.

## Draft persistence gap (MR-12 scope reduction)

`TaskCreateViewModel` maintains `checklist: List<DraftChecklistItem>` and
`attachments: List<DraftAttachment>` in `TaskDraft`, but `TaskRepository.upsert`
only writes the domain `Task` (which has no checklist/attachment fields).
The draft fields are serialized separately.

**Impact**: Users can fill in checklist items and attachment URLs in the create
editor, but after save they are lost (not persisted to the task).

**Fix**: Requires extending `TaskRepository.upsert` to also upsert checklist items
and attachments to their own tables, or wiring a `DraftRepository` that
co-locates draft state with the task ID.

**Not fixed in MR-12/MR-13** — scoped out to avoid expanding MR-12 scope.

## File attachment URL entry — no server validation

`TaskCreateIntent.AddAttachmentUrl` accepts any URL string without validation.
There is no check that the URL is reachable, not a local file path, or a
supported scheme (http/https).

**Impact**: Users can enter invalid URLs; no error is shown until later.

**Fix**: Add URL validation in `AddAttachmentUrl` intent handler or in a use case.
Not covered by any test in MR-12/MR-13 scope.

## AgendaBadge.Recurring gap (closed in MR-14)

`AgendaBadge.Recurring` was declared in the enum but never returned from any
badge computation path. `DefaultBadgeRules` covered only 4 of 6 badges.
Closed by MR-14: `computeAgendaBadge()` single source + `DefaultBadgeRules`
aligned.

## StableJson cannot serialize kotlinx-datetime DateTimeUnit in flow tests

`StableJson` uses `classDiscriminator = "_type"` for polymorphic sealed hierarchies
but does NOT register `kotlinx.datetime.DateTimeUnit.DateBased` serializers.
As a result, `RecurrenceSpec.Interval` (which contains `DateTimeUnit.DateBased.DAY`)
fails to serialize in desktop Compose UI flow tests.

**Workaround**: RecurrenceSpec is tested in `jvmTest` (unit tests) rather than
desktop flow tests. `AgendaBadgePolicyFlowTest` covers recurring badge via
`RecurrenceSpec.Weekly` (which only contains `Set<Int>`) in a commented-out test
with a note pointing to `RecurrenceParserTest`.

**Fix**: Either register `DateTimeUnitSerializers` in `StableJson`, or change
the recurrence fixture in flow tests to use `RecurrenceSpec.Weekly` which is
fully serializable.

## SelectorTransformer.matches is always true — scope is unused

`SelectorTransformer.matches` returns `true` in `DefaultBadgeRules` for all
transformers, making it a no-op. The `Selector.matches` extension function
(which actually filters tasks) is called by `AgendaEvaluator` separately, not
through `SelectorTransformer`.

This means the "scope" concept in `SelectorTransformer` is dead code — the
transformer only provides `badgeFor`, not scope filtering. The badge priority
is enforced by `SelectorTransformer.all`'s fold, not by scope.

**Not fixed** — requires clarifying the `SelectorTransformer` contract and
deciding whether `matches` should be removed or wired into `AgendaEvaluator`.

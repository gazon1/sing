---
title: "Tag rename, and validation that create and update share"
date: 2026-09-30
status: accepted
tags: [tags, validation, mvi, testing]
---

## Context

Tags could be created and deleted but not renamed. A user who mistypes a tag
name had to delete it and create a new one, which orphans every task that
referenced it — the task keeps a `TagId` that no longer resolves.

Adding rename surfaced a second problem. `TagDomain` already held the rules
(`validateName` — not blank, max 100; `validateColor` — not transparent), but
**nothing called them**. `CreateTagUseCase` had its own inline
`require(input.name.isNotBlank())`, which is a strictly weaker rule: it
accepted a 400-character name and a transparent colour. The domain object was
dead code with a test-free public API.

## Idea

1. Add `TagsIntent.Rename` and wire it to `UpdateTagUseCase`.
2. Validate only in `UpdateTagUseCase`, leaving create as-is.
3. Validate in both, each calling `TagDomain` directly.
4. Add `TagDomain.validate(name, color)` as the single entry point and have
   both use cases call it.

## Decision

Approach (4). `TagDomain.validate` runs both rules and returns the first
violation; `CreateTagUseCase` and `UpdateTagUseCase` both call it. The inline
`require(isNotBlank())` in create is gone.

Rename keeps the tag's id, `createdAt` and `userId`; only name, colour and
`updatedAt` change. `TagsViewModel.rename` reads the current row and hands the
copy to `UpdateTagUseCase`, which owns validation and the timestamp.

The UI reuses `AddTagDialog` rather than adding a `RenameTagDialog` — the two
differ only in title, confirm label and initial values, and a near-identical
second dialog is guaranteed to drift. `AddTagDialog` gained `title`,
`initialName`, `initialColor` and `confirmLabel` parameters, all defaulted so
existing call sites are unchanged.

## Rationale

**Why the id has to be the unit of the rename.** `Task` stores `TagId`, not the
tag name. Any rename implemented as delete-then-create silently breaks every
link. The intent carries the id, and `UpdateTagUseCase` writes a `copy()` that
preserves it — the tests assert the id and `createdAt` survive.

**Why colour travels in the rename intent.** The dialog shows a colour picker,
so "rename and pick a colour" is the natural single interaction. Putting
`color` in the intent makes it one write instead of a read-modify-write that
races a concurrent rename of the same tag.

**Why a missing tag is a failed `Result` and not a thrown exception.** The
first implementation threw `AppError.NotFound`. `MviViewModel.catchTo`
converts a *returned* `Result.failure` into a one-shot error event; a thrown
exception propagates out of `vmScope.launch` to the uncaught-exception handler.
The test caught this by failing with the raw `NotFound` on the stack rather
than an assertion. `rename` now returns `Result.failure(NotFound(...))`, and a
test asserts the error surfaces as `TagsUiEvent.ShowError`.

## Consequences

- Tag renaming is reachable from Settings → Tags: the pencil on a card opens
  the dialog pre-filled with the current name and colour, and Save writes it.
- **Create now rejects what it used to accept.** Names over 100 characters and
  transparent colours (`ARGB=0`) were previously written. If any exist in a
  user's database they are *not* re-validated — the rule applies to new writes
  only, and re-validating existing rows was deliberately not done.
- `TagDomain` is no longer dead. Its two rules and the combined `validate` are
  now covered by `UpdateTagUseCaseTest`.
- `TestTags.tagRename(name)` gives the pencil a stable `tag_rename_<slug>`
  selector for Maestro and Compose UI tests.
- The rename path is a read-then-write without a transaction. Two renames of
  the same tag in quick succession are last-write-wins; the second read can
  clobber the first. Not currently reachable — the dialog is modal and the
  screen shows one dialog at a time — but it is the shape to watch if
  multi-select tags ever land.

## Open items (non-critical, not fixed here)

- **No duplicate-name check.** Two tags can be called "work"; only the id
  distinguishes them. Acceptable for now, but a tag picker listing by name
  becomes ambiguous.
- **The rename is not undoable.** Delete is confirmable and rename is not; a
  mis-typed name overwrites the old one with no way back.
- **Other write paths may still bypass `TagDomain`.** Sync applies remote
  events through `repo.upsert` and does not run these rules — deliberate,
  since rejecting a remote change would break replication, but it means the
  invariant is "locally created and locally renamed tags are valid", not
  "all stored tags are valid".

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tags/domain/TagDomain.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tags/domain/usecase/TagsUseCase.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tags/TagsViewModel.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tags/components/AddTagDialog.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/tags/TagRenameTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/tags/TagsRenameUiTest.kt`

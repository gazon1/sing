---
title: "AI proposal confirmation: compare-and-set, transactional apply, tag suppression"
date: 2026-10-02
status: accepted
supersedes: 2026-09-30-card-level-ai-actions-deferred
tags: [ai, proposals, tasks, tags]
---

## Context

`TaskAiSlot` in MR-6+ performed immediate writes for AI actions — `RefineTitle`,
`GenerateDescription`, `GenerateChecklist`, `Decompose` — with no preview, no
confirmation, and no undo on mobile list surfaces. The 2026-09-30 ADR deferred card-level
AI for this reason, and required a preview-and-confirm step as a prerequisite for any
future card-level AI.

MR-7 implements that prerequisite: a proposal system where AI actions are surfaced as
confirmable items, applied only on explicit user approval, and rejected with reason causes
suppression of the same suggestion.

MR-8 (checklist sovereignty + tag suppression) builds on this layer.

## Decision

### Tables

Two Room tables with ownership via `user_id` on the parent proposal (no column duplication
on child entities):

```
ai_proposal:   id, task_id, user_id, source, status, created_at, updated_at, [sync columns]
ai_proposal_item: id, proposal_id, kind, target_id, args_json, human_summary,
                 status, decided_at, decided_actor, rejection_reason,
                 fingerprint, sort_order
```

`source ∈ {Card, Detail, ExtractActions, Agent}` tracks where the proposal originated.
`status ∈ {Pending, PartiallyResolved, Resolved, Retracted}` on proposals;
`status ∈ {Pending, Confirmed, Rejected, Retracted}` on items.

Ownership is enforced at the SQL level: every write on `ai_proposal_item` carries
`WHERE proposal_id IN (SELECT id FROM ai_proposal WHERE user_id = :userId)`.
No `user_id` column exists on `ai_proposal_item` — the subquery is the only mechanism.

### Compare-and-set apply

`ProposalItemDao.claimItem` is a single SQL statement:

```sql
UPDATE ai_proposal_item
SET status = :status, decided_at = :ts, decided_actor = :actor, rejection_reason = :reason
WHERE id = :id
  AND proposal_id IN (SELECT id FROM ai_proposal WHERE user_id = :userId)
  AND status = 'Pending'
-- returns 1 if this call won the race, 0 if already decided
```

`claimAndRead` wraps `claimItem` + `getItem` in a `@Transaction` DAO method, so the
row returned to the caller is the one the claim saw — no stale read between decision and
dispatch.

`rows == 0` is a silent no-op. A double-tap, a concurrent confirm on another device, or
a crash-between-dispatch-and-record all converge on the same state: the item is decided,
the second tap is ignored, and the user sees the already-decided outcome.

### ApplyProposalItemUseCase pipeline

```
plan(item)        → PlanResult.Ok(WriteTask) | PlanResult.Invalid(reason)
  -- all validation (parse, range, require non-blank) happens HERE
  -- no state written at this point
  -- item remains Pending; caller can retry on bad value

claim(item, userId) → ProposalItemEntity?  (null = lost race)
  -- CAS: UPDATE WHERE Pending → Confirmed/Rejected
  -- returns the row as it was at claim time

dispatch(plan, userId) → Unit
  -- pure payload dispatch from the resolved plan
  -- no more parse-after-claim: plan() already validated

refreshStatus(proposalId) → Unit
  -- aggregate item statuses → update proposal status
  -- PartiallyResolved / Resolved / Retracted
```

`plan()` runs **all** validation before `claim()`. A bad value leaves the item `Pending`
and re-tryable. `dispatch()` receives a fully resolved `WriteTask` payload, never raw
JSON that might parse differently after the claim.

### ProposalItemKind as single dispatch point

Six variants, one `when{}` in the entire codebase (in `ApplyProposalItemUseCase`):

```
SetTaskField(field: TaskField, value: String)
AddTags(names: List<String>)
RemoveTags(names: List<String>)
AddChecklistItems(items: List<ChecklistItemInput>)
AddSubtasks(titles: List<String>)
AddTimeEntries(entries: List<TimeEntryInput>)
```

`TaskField ∈ {Title, Description, Priority, Status, DueDate, Estimate}`.

`fingerprintTarget` is an extension property on `ProposalItemKind` (not an interface
property — Kotlin's interface dispatch does not support `when(this)` in a property).

### Fingerprint-based rejection suppression

`ProposalFingerprint.of(kind, targetId, args)` = FNV-1a 64-bit hash, 16 hex chars.
Stable across JVM and Android (no platform dependencies, pure Kotlin `String`/`ByteArray`).

`ProposalRepository.rejectedFingerprints(userId, limit=50)` returns the most-recent
rejected fingerprints for the user. Before building a proposal, the agent checks each
item's fingerprint against this list — if already rejected, the item is not included
in the proposal.

Rejected fingerprints are stored on `ai_proposal_item`, not denormalized on the task.
This avoids a second source of truth for task state; the join through the proposal is
the only query path.

### Tag suppression (MR-8)

`Task.aiSuppressedTagIds: Set<TagId>` stored as JSON array in a task column.

`TaskRepository.setTags(taskId, tagIds, actor: TagEditActor)` — `TagEditActor = User |
AiProposal`. When `actor = User` removes a tag → the removal is recorded as suppression.
When `actor = User` adds a tag manually → suppression for that tag is cleared (user
overrides AI). When `actor = AiProposal` suppresses → existing suppression is preserved.

Three layers: (1) prompt-side suppression by id+name, (2) validator rejects if tag is
suppressed at dispatch time, (3) `rejectedFingerprints` blocks re-proposing the same item.

User rejection with a reason writes a `rejection_reason` on the item; this reason is
surfaced in `ProposalFeedbackBuilder` as "Recent user decisions: ✗ 'AddTags: work'
— rejected, reason: already have too many tags". Reasons shorter than 20 characters are
rejected at dispatch time.

## Rationale

Immediate writes on mobile list surfaces were the core problem documented in the
2026-09-30 ADR. The CAS semantics here are identical to what `ConflictResolver` uses for
sync — the technique is proven. The `@Transaction` DAO pattern means the race protection
is database-enforced, not application-logic-enforced.

Storing decisions on the item (not recomputing from task state) means the audit trail is
immutable: an item that was confirmed at 14:00 because the title was "Buy milk" remains
correct even if the user changes the title to "Buy oat milk" at 14:05.

Fingerprint suppression is conservative: the agent does not re-propose an item with the
same fingerprint. This does not prevent semantically different proposals on the same field
— "Buy milk" rejected, "Get milk" is a different fingerprint and can be proposed. The
suppression is per-item-per-target, not per-field.

## Consequences

- `TaskAiSlot` no longer writes directly. All five `TaskAiAction` variants become
  proposal sources. `TaskAiSlot.execute` calls the proposal repository, not the task
  repository.
- MCP tools (`create_task`, `update_task`, `delete_task`, `decompose_and_create`) are
  **not** affected. These operate in a separate dogfooding profile with a separate user
  identity and do not require confirmation. This is intentional and documented here to
  prevent future confusion.
- `ai_proposal` + `ai_proposal_item` are local-only for now. Cross-device sync requires
  a new doc-type in `SyncEngine` and is explicitly deferred.
- `checked_by` / `checked_at` on checklist items (MR-8) requires `row_version`
  increment on every toggle to prevent concurrent user/AI writes from losing the other's
  change.

## Links

- `feature/proposals/domain/model/ProposalItemKind.kt` — sealed kind
- `feature/proposals/domain/usecase/ApplyProposalItemUseCase.kt` — pipeline
- `feature/proposals/data/ProposalItemDao.kt:claimItem` — CAS SQL
- `feature/proposals/domain/logic/ProposalFingerprint.kt` — FNV-1a
- `feature/proposals/domain/logic/ProposalStatusReducer.kt` — status aggregation
- `2026-09-30-card-level-ai-actions-deferred.md` — supersedes this ADR

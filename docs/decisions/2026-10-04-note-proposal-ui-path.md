---
title: Note Proposal UI Path — How to Add Proposals to New Surfaces
date: 2026-10-04
status: draft
deciders: 
deciders: 
---

## Context

Proposals currently render on the task detail screen via `TaskProposalsCollector` → `TaskDetailCoordinator` → `TaskDetailViewScreen`. When adding proposals to the **note editor** surface, we need a repeatable pattern.

## Decision

### Architecture

```
Surface ViewModel
    │  (creates + owns)
NoteProposalsCollector(scope, proposalsFlow)
    │  (exposes StateFlow<List<AiProposal>>)
    │
ProposalCardList(proposals, onConfirm, onReject)
```

### Components

**1. `NoteProposalsCollector`** — `AutoCloseable`, scoped to the editor's `vmScope`.
```kotlin
class NoteProposalsCollector(
    noteId: String,
    proposals: ProposalRepository,
    scope: AutoCloseableCoroutineScope,
) {
    val proposals: StateFlow<List<AiProposal>>
}
```

**2. Surface integration** — in `NoteEditor`:
```kotlin
class NoteEditor(..., proposals: ProposalRepository) {
    private var collector: NoteProposalsCollector? = null

    fun openEditor(noteId: String) {
        collector?.close()
        collector = NoteProposalsCollector(noteId, proposals, vmScope)
        // vmScope lifecycle = editor lifecycle
    }

    fun closeEditor() {
        collector?.close()
        collector = null
    }
}
```

**3. Proposal card list** — reusable composable:
```kotlin
@Composable
fun ProposalCardList(
    proposals: List<AiProposal>,
    onConfirmItem: (ProposalItemId) → Unit,
    onRejectItem: (ProposalItemId, reason: String?) -> Unit,
)
```

### Missing piece: proposal UI composable

The `ProposalCardList` composable exists for tasks (`TaskProposalsCard` or similar). It must be extracted into a shared component and adapted for note-editor context (no task title, different action chips).

## Steps to Add Proposals to Any Surface

1. **Add `watchProposalsFor{surface}`** in `ProposalRepository`
   - Use `watchProposalsByTargetKind(targetKind, status)` — self-scoped
   - Filter by targetId in collector (not in query — avoids multi-column index for nullable targets)

2. **Create `{Surface}ProposalsCollector`**
   - `AutoCloseable`, owns the `scope`
   - Filters `proposals.items.any { it.pending }`

3. **Add `applyProposal: ApplyProposalItemUseCase` to surface VM**
   - Confirm/reject intents delegate to use case
   - VM owns collector lifecycle

4. **Add proposal card composable**
   - Reuse shared `ProposalCardList` if possible
   - Surface-specific: what does the user see as "what changed"?

## Current Status (2026-10-04)

- ✅ `ProposalRepository.watchProposalsByTargetKind` exists
- ✅ `ApplyProposalItemUseCase` handles confirm/reject
- ❌ `NoteProposalsCollector` exists but is **not instantiated** in `NoteEditor`
- ❌ `NoteEditor` does NOT hold `applyProposal` and `proposals` (params added but not used)
- ❌ No proposal card composable for note editor

## Links

- `ApplyProposalItemUseCase` TooManyFunctions refactor ADR
- Phase A retrospective 2026-10-04

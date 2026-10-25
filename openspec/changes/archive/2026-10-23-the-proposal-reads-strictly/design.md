# The proposal reads strictly — Design

## The two formats, and how the reader knows which one it holds

The flat format packs everything into text: a list of checklist texts in one
column, separated by Unit Separator; the time entries in another, separated by
Record Separator; a value in a string. The sealed format carries the model's
own shape: a nested object for a value, an array for a list, a nested object
for a created thing.

That difference is visible in the blob without reading it:

```kotlin
private fun JsonObject.carriesStructure(): Boolean =
    values.any { it is JsonObject || it is JsonArray }
```

The old flat writer only ever wrote primitives — text, numbers, booleans — so
a flat blob has no structure, and a blob that has one is a sealed blob. The
two shapes that coincide (the deletions and the unknown kind) mean the same
thing either way, so they are read by the flat reader without harm.

The reader therefore knows what it holds before it reads it:

```kotlin
return if (obj.carriesStructure()) {
    decodeSealed(obj) ?: ProposalItemKind.Unknown(raw)
} else {
    decodeFlat(obj, raw) ?: ProposalItemKind.Unknown(raw)
}
```

## Strictness, and what it costs

The `Json` instance the sealed format is read and written with is the default
one — `ignoreUnknownKeys = false`. A key this build does not know is a key a
newer build wrote; reading the blob without it fills in a default and produces
a *different, valid* value. The reader refuses the blob instead, and the
refusal is `ProposalItemKind.Unknown`, which the card shows with the reason.

The flat reader is read by hand, so it is unaffected: it never asks a
serializer, and a key the old writer did not know is a key the old reader
ignores — which is what the old format always did.

The written format did not change: `ignoreUnknownKeys` never affected writing,
and the writer is still the model's own serializer.

### The residual limit

A field the older build knows *and* has a default for, which the newer build
dropped, is defaulted by the older build. Closing that needs a version
envelope in the blob — `{"v":2,"kind":…}` — so that a reader that does not
know the version refuses the blob instead of decoding it with what it
happens to know. It is a follow-up: the case that matters (a field the older
build has never heard of) is already refused.

## The routing, and how the compiler checks it

### The model

```kotlin
sealed interface ProposalTarget {
    data class Creation(val kind: CreationBoundKind) : ProposalTarget
    data class OnTask(val kind: TaskBoundKind) : ProposalTarget
    data class OnNote(val kind: NoteBoundKind) : ProposalTarget
    data class OnProject(val kind: ProjectBoundKind) : ProposalTarget
    data class OnTag(val kind: TagBoundKind) : ProposalTarget
    data object Unreadable : ProposalTarget
}
```

The group interfaces are sealed and are implemented by the kinds themselves:

```kotlin
data class SetTaskField(val value: TaskValue) : ProposalItemKind, TaskBoundKind {
    override val target: ProposalTarget = ProposalTarget.OnTask(this)
    ...
}
```

The payload is what makes the claim checkable: `ProposalTarget.OnTask` takes a
`TaskBoundKind`, so a kind that claims the task group without being one does
not compile. The groups are:

| group | kinds |
|---|---|
| task | `SetTaskField`, `SetTaskTags`, `AddTags`, `RemoveTags`, `AddChecklistItems`, `AddSubtasks`, `AddTimeEntries`, `DeleteTask` |
| note | `SetNoteField`, `DeleteNote`, `ExtractActions` |
| project | `SetProjectField`, `DeleteProject` |
| tag | `SetTagField`, `DeleteTag` |
| creation | `CreateTask`, `CreateProject`, `CreateNote`, `CreateTag` |
| — | `Unknown` (the target is `Unreadable`) |

### The use case

```kotlin
private suspend fun buildPlan(item: ProposalItem, userId: UserId): ProposalPlan {
    val kind = item.kind
    return when (val target = kind.target) {
        ProposalTarget.Unreadable -> error(kind.readFailure ?: "This change cannot be read.")
        is ProposalTarget.Creation -> planner.planCreateItem(item, target.kind, userId)
        is ProposalTarget.OnTask -> { /* fetch the task, resolve the tag names, plan it */ }
        is ProposalTarget.OnNote -> { /* fetch the note, plan it */ }
        is ProposalTarget.OnProject -> { /* fetch the project, plan it */ }
        is ProposalTarget.OnTag -> { /* fetch the tag, plan it */ }
    }
}
```

The `when` is over a sealed type, so it is exhaustive. A group with no branch
does not compile.

### The planner

One function per group, each switching over its group exhaustively:

```kotlin
fun planTaskItem(
    item: ProposalItem,
    kind: TaskBoundKind,
    task: Task?,
    resolvedTagIds: Set<TagId> = emptySet(),
): ProposalPlan = when (kind) { … }
```

A kind that joins the group and is not handled here does not compile. The
`else -> error("Unexpected task-bound kind: $kind")` at the end of each
planner function is gone: there is nothing left to fall through to.

The separate "delete" planner is gone with it — a deletion is a change about a
task, a note, a project or a tag, and it says which.

### The names a change carries

`tagNames` moved into the model, so the use case resolves a name into an id
without knowing which kind carries names:

```kotlin
val tagNames: Set<String>
    get() = when (this) {
        is AddTags -> names.toSet()
        else -> emptySet()
    }
```

## The reasons, and who keeps them

`ProposalDecisions` owns the store; the surfaces subscribe to it. The store is
no longer a constructor parameter, so a surface cannot show a reason it forgot
to collect — there is nothing to collect.

The mirroring stays: a surface's state is what the surface draws from, and the
reasons belong next to the change they belong to. What is gone is the third
copy of the rule about when a reason is forgotten.

## The names, and the check on them

Each dictionary declares the name of its field twice — in the variant and in
the reader. The test compares the two lists, in both directions:

- every name a value declares is a name the reader knows (the reader does not
  reject it as unknown);
- the reader reads it as *that* field (the value it returns carries the same
  name).

The values that cannot be read by design — each dictionary's own `Unreadable`
— are left out.

## The scope, and who builds it

The agenda's tests now say what kind of ViewModel they are testing:

```kotlin
private fun TestScope.createVm(): AgendaViewModel = testVm(
    stateAccessor = { vm: AgendaViewModel -> vm.stateFlow },
    vmScope = VmScope.Background,
) { scope -> AgendaViewModel(…, scope = scope) }.vm
```

`VmScope.Background` is the scope for a ViewModel whose collector never ends:
the background scope's marker keeps that work out of `advanceUntilIdle`'s way,
so the test is not held open by a collector that will never finish.

The exemption list in the harness gate is empty, and the gate that keeps it
empty still runs.

## What the tests say

- `a change a newer build wrote with a field this one does not know is refused`
- `a sealed blob is never read as a flat one`
- `a blob that is not a blob keeps the change` — and now the reason says the
  blob is not stored in a form this version can read, which is a different
  fact from "another version wrote it"
- `every field a task value names, the task reader reads` — and the same for
  the note, the project and the tag
- `a name no value declares is not read as some other field`

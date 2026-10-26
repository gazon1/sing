# Contract: The Saved Agenda View's Selector Document

**Feature**: `016-task-states-and-items` · **Date**: 2026-10-26

A saved agenda view is one JSON blob in one column (`AgendaViewEntity.sectionsJson`)
holding the whole `AgendaDefinition`. Each section carries a selector, and a selector is
a tagged object:

```json
{ "_type": "<the variant's tag>", … }
```

This feature adds two type tags, which makes the document's forward compatibility a
practical concern rather than a theoretical one.

---

## What changes

Two new selectors, each a new `_type`.

### `WorkflowState`

```json
{ "_type": "WorkflowState", "state": "InProgress" }
```

| Field | Type | Notes |
|---|---|---|
| `state` | string | one of `"Open"`, `"NextUp"`, `"InProgress"` |

An unknown `state` decodes to `Open` rather than failing the document.

### `FollowUp`

```json
{ "_type": "FollowUp" }
```

No payload. It matches the tasks that own a "follows up on" relation.

### `Blocked` — no new tag, but newly reachable

`{ "_type": "Blocked" }` already existed in the hierarchy, in `typeTag` and in the
serializer registry. It had **no** `SelectorTemplate`, so the editor never offered it and
a blocked view could not be built. This feature gives it a catalogue entry; the document
format does not change for it.

---

## The registry is the contract's enforcement mechanism

The decoder is a map lookup:

```kotlin
return registry.allEntries.getValue(type).decode(obj)
```

An unknown `_type` throws `NoSuchElementException`. Because a saved view is **one**
document, one unknown selector takes the **whole view** down — not just one section of
it.

So the registry must contain every type tag the hierarchy can produce, and the
repository has been bitten by exactly this before: `TimeOfDay` was in the hierarchy and
in `typeTag` but never in the registry, and `getValue("TimeOfDay")` threw the moment
anything tried to serialise a work-day section — silently, until
`2026-10-19-selector-registry-completeness-guard.md` added the guard.

### The five places, always together

A new `Selector` variant is only half-added until it is in **all five**:

| # | Where | What |
|---|---|---|
| 1 | `Selector.kt` | the sealed hierarchy — the variant itself |
| 2 | `SelectorSerializer.kt` → `typeTag` | the discriminator `when` |
| 3 | `SelectorSerializer.kt` → `RegistryHolder.leafEntries` | the encode/decode entry — **and the `require(it.size == 14)` beside it becomes 16** |
| 4 | `SelectorSerializer.kt` → `expectedTypeTags` | the set of every type tag — **17 becomes 19** |
| 5 | `SelectorMatcher.kt` → `Selector.matches` | the match branch — the single source of truth for matching |

The `init` block in `SelectorSerializer` checks that the registry's size equals
`expectedTypeTags.size` and that nothing is missing. It cannot catch a variant that is
absent from **both** the registry and `expectedTypeTags` — which is exactly how
`TimeOfDay` slipped through.

---

## Forward compatibility across builds

| Situation | Behaviour after this feature |
|---|---|
| A view is saved and read by the **same** build | exact round trip |
| A view with a new tag is read by an **older** build | the view is **dropped** — it is undecodable, and the saved-views list continues without it |
| A view with an old tag is read by a **newer** build | exact round trip; the old tags are unchanged |

The middle row is the contract's actual guarantee, and it is deliberately weaker than
"an older build understands a newer view":

> **A saved view written by a newer build and read by an older one is dropped, not
> corrupted. The rest of the saved-views list stays usable.**

The alternative — making the decoder skip unknown sections — is not available: the
decoder maps one selector to one selector and has no list level at which it could skip.
Inventing a sentinel variant would put a lie into the domain. The guard therefore lives
one level up, in the repository, which drops the undecodable view rather than failing the
list.

**This is best-effort, and it is one-directional.** It protects the common case (a user
who has both builds, or who downgrades) from a whole list becoming unreadable. It does
not make the format forward-compatible in the general sense, and the plan does not claim
that it does.

---

## Files

| File | What changes |
|---|---|
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/model/Selector.kt` | **+ `WorkflowState`**, **+ `FollowUp`** |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/model/SelectorSerializer.kt` | **+ 2 registry entries**, **leaf count 14 → 16**, **type tags 17 → 19** |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/selector/SelectorMatcher.kt` | **+ 2 match branches**, **+ `followUpIds`** in `SelectorContext` |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/selector/SelectorTemplate.kt` | **+ catalogue entries**, including the one that finally offers `Blocked` |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/data/SavedAgendaViewsRepositoryImpl.kt` | **+ the undecodable-view guard** |

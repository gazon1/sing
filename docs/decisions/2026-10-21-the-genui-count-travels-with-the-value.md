---
title: "The GenUI count travels with the value it counts"
date: 2026-10-21
status: accepted
tags: [architecture, genui, text-fields]
---

# The GenUI count travels with the value it counts

## Context

A GenUI surface holds a data model, and a field bound to a path in it needs to know two
things about that path: the value, and whether the value was replaced from outside.

The second one is a **count**. The bridge cannot tell a lagging model from a model that
deliberately rewrote the value to something the field had already sent — both arrive as "a
value I sent a moment ago" — so the owner has to say so, and the way it says so is a
counter that moves when the *server* replaces the value.

The count was kept in the surface controller, in a map beside the surfaces:

```kotlin
private val _serverRevisions = MutableStateFlow<Map<SurfaceId, Long>>(emptyMap())
```

One entry per surface, holding the number of the server's writes to *any* path of that
surface. The context then assembled the pair itself, reading the value from the data
model and the count from the controller, and gluing them together:

```kotlin
current = sourceOf(
    value = controller.snapshot(surfaceId)?.dataModel?.get(path),
    revision = controller.currentServerRevision(surfaceId),
    fallback = fallback,
),
updates = combine(
    controller.value(surfaceId, path),
    controller.serverRevision(surfaceId),
) { value, revision -> sourceOf(value, revision, fallback) },
```

What that cost:

- **The count was not about the value.** A server write to one path moved the count for
  *every* path of the surface. A field bound to an untouched path was told "you were
  rewritten from outside", the bridge lifted its echo guard, and a stale value the field
  had pushed earlier was applied over what the user had typed. The guard exists to stop
  exactly that.
- **The count outlived the surface.** The map was never pruned: `deleteSurface` and
  `prune` removed the surface and left the count, and `reset` cleared the surfaces and
  left the counts. A surface that came back under the same id inherited a count that
  described a surface that no longer existed.
- **The pair could be torn.** The value and the count came from two stores, read at two
  moments. Between them the server could write, and the caller would hold a value with a
  count that does not go with it.

## Idea

The count is a fact about a value. It belongs inside the value, the way the field
contract's `Revisioned<T>` already puts the count of a value's replacements inside the
value — see `2026-10-21-the-count-travels-with-the-value`.

## Decision

**The count lives in the surface's data model, per path, and the two are read together.**

- The data model holds the document and the counts in **one state**. A reader takes both
  from one read, and a subscriber gets both in one emission. There is no window in which
  a value and a count can disagree.
- The count is **per path**. The count says "this value was replaced from outside", and
  it is the count of *this path's* replacements. A write to one path says nothing about
  another.
- The field's own write leaves the count where it is; the server's write moves it. The
  two writes are two methods, and the difference is written down at the call site.
- The controller no longer holds a count. The context no longer assembles a pair from two
  places: it reads the value and the count together and turns them into the field
  contract's source.
- The GenUI's own pair — the value and its count — is a type of the schema layer, not
  the field contract's `Revisioned`. The schema layer must not depend on the UI layer's
  types: the GenUI's data model knows nothing about text fields. The render layer
  converts once, at the boundary.

## Rationale

- **A counter beside the data is a second store for a fact about the data.** Two stores
  can disagree, and here they did — in three different ways, none of which failed
  loudly.
- **The count belongs to the path it rewrote.** The bridge's question is "was *my* value
  replaced from outside?", and the answer for a field bound to an untouched path is no.
  A surface-wide counter answered a different question, and the wrong one.
- **One read is atomic; two reads are not.** The pair is what the bridge compares, and a
  pair assembled from two reads can be a value from one moment and a count from another.
- **Two ways to write is the way to say what happened.** The field's write and the
  server's write are different facts about the data, and the difference is the count. A
  single `set` would have to decide, and the decision is the whole point.

## Consequences

- A server write to one path no longer tells the other paths of the surface that they
  were rewritten. The echo guard stays armed where it belongs.
- A surface that is closed and opened again comes back with no count, because the count
  went with its data model.
- The controller's `serverRevision` and `currentServerRevision` are gone: there is no
  second place to ask for the count, and no way to ask for a count that does not go with
  a value.
- The GenUI's schema layer grew a pair type. It is a small price for a layer that stays
  independent of the UI layer.

## Links

- `2026-10-21-the-count-travels-with-the-value` — the same principle in the field
  contract, and the type this change follows.
- `2026-10-21-the-genui-context-hands-out-the-source` — the context that builds the
  source, and now reads the pair in one go.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/schema/DataModel.kt`
  — the document and the counts, in one state.
- `openspec/changes/the-genui-count-travels-with-the-value/` — the change this records.

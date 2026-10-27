---
title: "The layer is a segment of the package, and a DAO is a DAO whatever it is called"
date: 2026-10-27
status: accepted
tags: [architecture, testing, gates, genui, repository]
---

# The layer is a segment of the package, and a DAO is a DAO whatever it is called

## Context

Two rules of the architecture test read the source by **name** rather than by
**declaration**, and both rules were wrong in both directions.

The first is the layer rule. It decided whether a package sits in the domain
layer by looking at a fixed position in the package name:

```kotlin
// com.singularity.todo.feature.<area>.<layer> — the layer is the sixth segment.
return packageName.split('.').getOrNull(5) == layer
```

A fixed position holds only for a package that is exactly `feature.<area>.<layer>`.
Two real packages are not:

- `feature.ai.chat.domain` — a domain package one level deeper than the area.
  The rule read its fifth segment (`chat`) and saw no layer, so a domain package
  that imports a presentation type passed the gate.
- `feature.genui.render.domain` — a **presentation** package that happens to
  carry the word `domain` in its name, because it renders the app's own
  vocabulary (task card, due date, project chip). The rule read it as the
  domain layer and reported it as a violation — a finding against a package
  that was doing nothing wrong.

The second is the DAO-write rule. It recognised a write by the **name** of its
receiver:

```kotlin
Regex("""\b\w*[Dd]ao(?:\(\))?\.\w*(?:upsert|insert|update|delete|…)\w*\s*\(""")
```

`taskDao.deleteForUser(...)` was caught; `db.taskDao().deleteForUser(...)` was
caught. A DAO held under any other name — `private val tasks: TaskDao` and then
`tasks.deleteForUser(...)` — was not. The name says nothing about what the
thing is; the declaration does.

## Idea

A rule that reads names will be wrong about names. Both rules should read what
the code says.

## Decision

**The layer is a segment of the package, not a position in it.** A package is
in the `domain` layer when it is under `feature.` and contains a `.domain`
segment, wherever that segment sits:

```kotlin
private fun isInLayer(packageName: String, layer: String): Boolean =
    packageName.startsWith("$PKG.feature.") && packageName.contains(".$layer")
```

**A package that is not a layer must not be named after one.** The components
that render the app's own vocabulary are presentation, so
`feature.genui.render.domain` becomes `feature.genui.render.app` — and the
registration it performs is `registerAppComponents()`, not
`registerDomainComponents()`. The name of the package is part of what the
architecture rule reads, so the rule and the names have to agree.

**A DAO is a DAO by its declaration.** The set of DAOs a file holds comes from
the declarations in it (`val|var <name>: *Dao`), and the receiver of a write
call is matched against those names. A write is a write whichever name the
DAO is held under.

Both rules keep their positive controls: the layer rule is still proved able
to fail on a domain package one level deeper than the area, and the DAO rule
is still proved able to fail on a file that deletes a row through a DAO it
holds under another name.

## Rationale

- The fixed position was an accident of the package shape, not a rule about
  layers. A rule that only holds for one depth reports nothing for every other
  depth, and a rule that reports nothing is indistinguishable from a rule that
  finds nothing — which is what the "vacuous rule" control exists to catch.
- The word `domain` in a package name was carrying a claim the code did not
  make. The rename removes the claim instead of teaching the rule to look
  past claims.
- A DAO held under a name that does not end in `Dao` is still a DAO, and a gate
  that misses it is a gate that passes on a real violation.

## Consequences

- `feature.genui.render.domain` no longer exists; imports of
  `render.domain.registerDomainComponents` become
  `render.app.registerAppComponents`.
- The layer rule is stricter: domain packages nested deeper than
  `feature.<area>.<layer>` are now checked for presentation imports.
- The DAO rule is stricter: writes through a DAO held under a non-`Dao` name
  are now reported.

## Links

- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/ArchitectureTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/render/app/AppComponents.kt`
- `docs/decisions/2026-09-27-write-layer-soundness.md`
- `docs/decisions/2026-09-30-repository-naming-and-package-convention.md`

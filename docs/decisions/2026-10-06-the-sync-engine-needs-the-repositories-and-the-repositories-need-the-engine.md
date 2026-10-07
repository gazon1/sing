---
title: The sync engine needs the repositories, and the repositories need the engine
date: 2026-10-06
status: accepted
---

## Context

Resolving a lost per-field race (REQ-OS-026, #203) requires writing a document back to
the local store by type — "put this document in the right table". That knowledge was a
lambda closed over six repositories inside `SyncBootstrapper.registerHandlers` and
reachable only from a pull handler, so it moved into `SyncDocumentWriter` and was
injected into `SyncEngine` as a constructor argument, exactly as `SyncBootstrapper`
received it.

That is the obvious wiring and it does not work. It closes a cycle:

```
TaskRepository → SyncRepository → SyncEngine → SyncDocumentWriter → TaskRepository
```

`SyncDocumentWriter` needs the repositories — that is what it is a table of — and the
repositories need the engine, because `enqueue` is how a write tells the server about
itself. Neither edge is wrong. Together they are a loop, and Koin resolves it by
recursing until the stack is exhausted.

## Idea

1. **Keep the eager argument** and accept that the graph cannot resolve.
2. **Reach into the repositories from the engine**, passing one into `resolveLostRace`.
   Breaks the cycle by inverting the dependency — the sync layer starts constructing
   feature repositories, which is the direction the layering runs away from.
3. **Defer the resolution.** Take `() -> SyncDocumentWriter` and resolve it where it is
   used.

## Decision

**Option 3 — a provider, resolved at the point of use.**

## Rationale

Option 1 is not a design at all: it is a graph that cannot be resolved, discovered late.

Option 2 trades a loud runtime failure for a quiet structural one. `SyncEngine` would
know six repositories to satisfy one rare branch, every constructor and every test would
carry them, and the next person to add a repository would have to thread it through the
engine as well — which is how a module ends up holding the whole application.

Option 3 states the cycle instead of hiding or fighting it. The writer is needed only to
resolve a lost race, and by then the repositories exist, so resolution cannot recurse.
The cost is a lambda in a constructor, and the benefit is that the one place the cycle
could be reintroduced says so in its own KDoc.

**The interesting part is why nothing caught it.** `koin-compiler-plugin` validates the
graph at compile time and did not report it — the definition that closes the loop is
built by a `{ get<SyncDocumentWriter>() }` expression the plugin could not analyse across
the module boundary, exactly as it already cannot analyse the test-local
`desktopPlatformModule()` mirror. The failure surfaced only in
`KoinGraphValidationTest`, which resolves singletons for real, and it surfaced as a
`StackOverflowError` whose stack had no frame in the project's own source: the repeating
unit was four Koin frames, so the deepest application frame named was arbitrary and
pointed at unrelated lines.

That is worth recording as its own fact. A stack trace with no application frames in it
is not a mystery, and reading "the deepest named line is nonsense" as *the sources are
stale* sent this investigation down a build-cache and classpath dead end for several
steps. The trace was honest; the cycle was simply not attributed to any one line.

## Consequences

- `SyncEngine` takes `writerProvider: () -> SyncDocumentWriter`. `SyncBootstrapper`
  still takes the writer directly — it is a leaf and closes no cycle, so making it a
  provider too would be ceremony.
- A resolution cycle in this graph is a **runtime** failure, not a compile-time one. The
  compiler plugin is a guard, not a guarantee, and the only check that resolves the graph
  for real is `SyncDiGraphResolutionTest`. It stays in the suite for that reason.
- Anything else added to `SyncEngine` that needs a feature repository must go through the
  same door, or it re-opens this.

## Amendment 2026-10-07 — the check this relied on was deleted

This ADR originally named `KoinGraphValidationTest` as the one test that resolves the
graph for real. `dc7f1d5d` deleted it, correctly: it "asserts a hand-picked handful of
singletons rather than resolving them", and a whole-graph test of that kind fails for
unrelated reasons until it is curated — at which point it is a handful again. GenUI's half
was replaced by `GenuiDiGraphTest`.

What was lost with it was not a design, it was **coverage of this decision**: after the
deletion nothing in the repository resolved the sync graph, so the claim above was
asserting something that no longer existed. `SyncDiGraphResolutionTest` restores it, and is
narrow on purpose — it resolves the sync chain only, so it has one reason to exist and one
thing to break.

The general lesson is the one worth keeping: **deleting a gate is not only about the gate.**
`GenuiDiGraphTest` is better than what it replaced, and the sync graph lost its only check
as a side effect of a change made for an unrelated reason. When a test is retired, the
question to ask is not "is it redundant?" but "which claim in the decision log was this
test the evidence for?"

## Links

- `2026-10-04-sync-server-schema-and-merge.md` — the per-field merge that produces
  `lost: true` in the first place.
- #203 — the defect this cycle was introduced while fixing.
- `2026-10-05-koin-w003-in-a-test-graph.md` — the other half of the same fact: a
  definition the compiler plugin cannot analyse is checked by resolution instead.
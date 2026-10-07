---
title: Three write rules, and the one that needs a fact only the server has
date: 2026-10-07
status: accepted
---

## Context

`SyncedWriteEnqueuesTest` started as one rule — a method that writes a synced row must
enqueue — and became three, because each attempt at the next rule exposed that the first
one was resting on an assumption about the *source*, not about the domain.

The assumption was how a Kotlin method body is found. The rule cut each body at "the next
declaration", which is right on the tree as it stands (measured: no body contains a further
`fun`) and wrong the moment one does. Replacing it with a brace walk found two bugs in the
replacement itself, and both are worth writing down because both made the rule report the
opposite of the truth:

1. **The walk matched declarations only at depth 0** — which never happens inside a class.
   The result was an empty list for every file, and an empty list passes.
2. **Expression-bodied functions have no braces.** `fun delete(id) = softDelete(id)` ran the
   walk on and absorbed the *next* method's body, so a delegation looked like it wrote a row
   unscoped with no guard — a false accusation of a cross-user write.

Both were found by controls, and both would have shipped otherwise. The first control is the
general one: a rule with no case that would make it fail is not evidence of correctness.

## Decision

**Three rules, all on the same walk, and the exemption that would need a fourth fact is not
built.**

1. A synced write enqueues a patch. (`every write to a synced table enqueues a patch`)
2. An unscoped write is guarded, or takes its identity from the ambient current user —
   **and the guard comes first**, matched as `assertCanWrite(…) … Dao.upsert(` rather than
   as a presence check, because a guard after the write is a cross-user write that has
   already happened.
3. A method that writes twice enqueues twice.

## Rationale

Rule 2 is not the one the KDoc describes, and that is the interesting part.
`GenericUserScopedRepository` states the canonical pipeline as `assertCanWrite` →
`dao.upsert` → `enqueue`, which reads as though every implementation either misses the guard
or has it in the wrong place. Neither is true: of the 37 methods that write a synced row, most
take the owner **in the query** — `softDeleteForUser(id, ts, uid)`, `setPinnedForUser(…, uid)`
— and need no guard, because the scope is in the WHERE clause. A rule reading "writes and
enqueues, therefore must guard" reports twenty of them and is wrong twenty times.

What is left after discounting the scoped calls is genuinely decidable from source: an
unscoped write must either be preceded by the guard or build its row from
`currentUser.scopedUserId`, which is what a `create` does by construction. All three rules are
green on the tree today — not because they are loose, but because the tree is clean.

Rule 3 is the partial-patch case. Rules 1 and 2 both ask whether an enqueue *exists* in a
method, so a method that writes two synced rows and enqueues one passes them — and the row it
forgot reaches this device and never reaches the server, which surfaces as divergence on the
second device, long after the merge.

## What is not built, and why

Two things, both recorded here rather than left to be discovered:

**The brace-aware method splitter (item 2).** It is written, and it found two real bugs in
itself — a declaration matcher that only fired at depth 0, so the walk returned an empty
list for every file and an empty list passes; and an expression-bodied `fun delete(id) =
softDelete(id)`, which opens no block and so absorbed the next method's body and produced a
false accusation of an unscoped write with no guard. Both were found by controls written for
them, which is the argument for controls.

It is **not landed**, because the two controls stayed red against the final version while the
corpus rules passed. That combination has one honest reading — the splitter behaves
differently on synthetic sources than on real files, and I did not find out why — and a rule
whose splitter I cannot demonstrate is worse than the declaration-based one it replaces,
which is right on this tree and wrong only on a shape no method here has. So the splitter
stays out, and this entry is the record of what it would have to prove first.

## What is not built, and why (exemptions)

The remaining item was replacing `NOT_SYNCED_WRITES`' method names with **synced columns**, so
that a column which becomes synced invalidates its exemption. A method name survives a
refactoring that changes what the method is for; a column does not.

It is not built because the set of synced columns does not exist in this repository.
`sync_field_allowlist` is a server table, and `SyncDocumentWriter.supportedTypes` is a set of
*entities*, not fields. So a column-based exemption needs a fact that only the live database
holds — the same blocker as the open half of #221, and the honest response is to name it
rather than to hardcode a column list that would be a fourth hand-maintained list in a class
of document this repository has now produced three of.

When the live half of #221 lands, the floor it writes is exactly this list, and the exemption
becomes derivable like the apply-handler set already is.

## A fourth rule, in its own file: is the scan set still the set that enqueues?

The three rules above all answer questions *about a file they are already looking at*. None can
notice a file they are not looking at, and the files they look at are chosen by a glob —
`feature/**/*RepositoryImpl.kt` declaring a `SyncRepository`-typed property. A glob is a
hypothesis about where code lives, and a hypothesis does not fail when it stops being true: it
quietly stops covering. A new `WidgetStore.kt` under `feature/widget/data/` writes rows and
enqueues, and all three rules pass, because none was ever asked about a file not named
`*RepositoryImpl.kt`.

So a fourth rule, `EnqueueSiteIsScannedTest`, asks the coverage question: every call that
enqueues through the port **and writes a row** must sit in a file the other three scan. It lives
in its own file because it answers a different question — per `2026-10-07-an-atomicity-rule-and-an-enqueue-rule-answer-different-questions`,
a file that answers two questions gets fixed when one of them changes.

Its exemptions are derived from the same discipline as the apply-handler set. The question
"which call is a repository enqueueing its own synced write" is answered by the **receiver**:
`syncRepository.enqueue(`. `SyncRepositoryImpl` forwards to `engine.enqueue` — it *is* the port —
and `CoreDiModule` hands `get<SyncEngine>().enqueue` to the writer — it is wiring. Neither is
matched, rather than matched and excused by filename; a list of those two would be the fourth
hand-written list in a document that has now produced three which went stale while the tree
stayed green. The remaining case is documentation: `GenericUserScopedRepository`'s KDoc spells
out the pipeline, receiver and all, and a dependency that exists only in a comment is not a
dependency.

The comment stripper is deliberately one-directional. It removes KDoc and `/* */`, and comments
alone on their line, and leaves trailing `//` alone — because a stripper that mis-tracks string
literals eats code, and eating code here would hide the very call the rule exists to find.
Under-stripping costs a false positive a human resolves in a minute.

Verified on the real corpus, not only on controls: with a `WidgetStore.kt` added under
`feature/widget/data/`, the rule reported `feature/widget/data/WidgetStore.kt:29` and named the
line of the enqueue call. That is a deliberate check that the rule bites, run once and reverted,
because a rule with no case that makes it fail is not evidence of correctness.

**What it still does not reach:** a repository that writes synced rows and never enqueues at all
has no enqueue call to be found by, and a repository that reaches the engine directly instead of
the port enqueues without matching. Closing the first needs to know which files are *supposed* to
write synced rows, which is the same server-side fact the exemption above is waiting for.

## Consequences

- `methods()` counts braces on every line. The early return that looked like an optimisation
  — "no declaration open here, skip" — was skipping the class's own brace.
- Braces inside double-quoted strings are blanked before counting; braces inside line
  comments are stripped; braces inside block comments are **not** handled, and that limit is
  stated in the KDoc rather than left to be discovered.
- Two controls cover the two shapes a brace walk gets wrong. Both were written *because* the
  corresponding bug had already happened.
- The three rules share one walk and one `Method` splitter, so a splitter fix is one edit —
  which is the whole argument for not writing each rule as its own file-scanner.

## Links

- `2026-10-07-an-atomicity-rule-and-an-enqueue-rule-answer-different-questions` — why the
  exemption set is derived from `SyncDocumentWriter`, and the shape the drifting lists share.
- `2026-10-07-a-fork-that-died-left-no-evidence` — why the tree's green run is only as
  trustworthy as the evidence a failure leaves behind, which is why the rule above names lines
  in its own failure message rather than printing a count.
- `2026-10-05-who-owns-a-row-and-the-patch-that-describes-it` — the invariant all three serve.
---
title: "Cross User Write Rule Measured And Rejected"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `feature/agenda/data/CrossUserWriteRegistry.kt` ships the single sanctioned bypass, and `CrossUserWriteRegistryTest` pins that each entry exists, that the count has not grown, and that each documents itself. The rejected PSI rule stays as ledger #18 in `2026-09-27-write-layer-soundness` — deferred, not lost.

**Found in:** 2026-10-04, while trying to mechanise the `assertCanWrite` bypass
that `SavedAgendaViewsRepositoryImpl.duplicateForProfile` documents in its KDoc.

The rule that looks right — "a repository method that takes a `userId` and writes
must call `assertCanWrite`" — was written and measured. It matches **eight**
methods in the data layer, and **seven are legitimate**:

```
ReminderRepositoryImpl::delete(id, userId)
ProjectRemindersRepositoryImpl::delete(id, userId)
TimeTrackingRepositoryImpl::startEntry / createManualEntry / updateNote
ProposalRepositoryImpl::refreshStatus / retract
```

These are user-*scoped* writes: the userId goes into a DAO query that already
reads `WHERE user_id = :userId`. Passing a userId to a scoped DAO is not a
cross-user write. Only `duplicateForProfile` writes a row belonging to somebody
*else*, because that is the operation's purpose.

Nothing syntactic separates them — both take a `userId` and both call `upsert`.
Telling them apart requires knowing what the DAO query does with the value,
which is the PSI-level rule `2026-09-27-write-layer-soundness.md` ledger #18
already deferred as disproportionate. Allowing the seven would make the list
meaningless: it would grow with every new scoped-DAO method and could never fail
on a real violation.

**What was shipped instead:** `CrossUserWriteRegistry` — a named list of the one
sanctioned bypass, with `CrossUserWriteRegistryTest` pinning that every entry
still exists, that the count has not grown, and that each one documents the
bypass in its own KDoc. It does not *catch* a new cross-user write. It makes the
existing hole greppable, and it is honest about being a registry rather than a
gate.

**Do this first:** if the project ever wants the real rule, it belongs in
`detekt-rules/` next to `PassThroughUseCaseRule`, and it needs to resolve the DAO
query, not the call site.

---

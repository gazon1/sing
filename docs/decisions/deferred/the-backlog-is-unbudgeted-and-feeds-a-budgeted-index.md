---
title: "The Backlog Is Unbudgeted And Feeds A Budgeted Index"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Found in:** 2026-10-05, while closing out the audit of the 28 untracked entries and
checking what had moved underneath the numbers. Not a regression — a structural property
of how findings are recorded.

**Tracked as:** #113

**Status: OPEN — a judgement call, not a chore.** Two files, one problem seen from two
ends:

| File | Size | Budget | Headroom |
|---|---|---|---|
| `docs/decisions/DIGEST.md` | 1196 lines / 436 entries | 1250, blocking | 54 lines |
| `docs/decisions/deferred-backlog.md` | 1934 lines / 59 entries | **none** | unbounded |

`check-doc-sizes.py` budgets `AGENTS.md`, `DIGEST.md`, `ARCHITECTURE.md`, `PROGRESS.md`
and `SKILL.md`. It does not budget this file — the one every finding lands in. The digest
indexes every ADR permanently and is at 96% of a hard ceiling (#52 tracks its pressure),
so an unbounded queue feeds a bounded index and the index fails first. The pressure
arrives as an unrelated-looking error on a commit that touched a doc gate rather than the
index.

**Try next, in this order.** Deciding is the work; implementing is mechanical afterwards.

1. **Split closed from open.** 19 entries now carry a dated closure and a reference, so
   they are a record rather than a queue. Moving them to a `deferred-backlog-archive.md`
   halves this file and leaves the live queue legible. An archive without a budget is the
   honest form: history is allowed to grow, a work queue is not.
2. **Budget the remainder** once it is only open entries, at a number low enough that
   hitting it means "triage now" rather than "the build is broken".
3. **Index rather than accumulate**, if the entries are staying whole — but note that
   `MODULE-INDEX.md` already does this for specs, and a second index for findings would
   need its own gate to stay honest.

**Not to do:** raise the digest ceiling to make room. The ceiling is the only thing that
made the pressure visible; a queue that has to be diluted before it can be indexed is not
being managed.


---

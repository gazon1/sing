---
title: "Sync Auth Repository Is Still A Stub"
date: 2000-01-01
status: RESOLVED
tags: ["deferred"]
---

**Status:** RESOLVED (2026-10-04). Phase 8 replaced the stub; `AuthRepositoryTest` (20)
covers it. Kept in the file rather than deleted because the shape of the defect is
worth remembering, and because the section below it is the reason it was tracked at
all.

The class was bound in the DI graph and its methods validated their arguments and
did nothing else. It was not dead code and the dead-symbol detector did not flag it:
a bound class has a production call site. That is exactly why it needed an entry of
its own — **the gate that would otherwise have caught it passed.**

`SupabaseConfigResolver` had sat in the same table while wired by nothing, and the
detector was right to flag it. But the moment `SupabaseClientProvider` appeared and
started resolving configurations, the flag cleared, because the binding *is* a
production reference. A binding proves that something can reach the class; it says
nothing about whether the class does anything. Reachability is not behaviour, and a
gate that measures reachability cannot tell a wired class from a working one.

The general form: a DI container makes a whole category of stub invisible to
reachability checks, because a stub is bound and called and simply returns. Catching
those needs a different signal — a test asserting the *effect* — not a better
reachability rule.

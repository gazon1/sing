---
title: "Test Doubles In Commonmain Source"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "unwired-detector-test-double-exemption"]
---

**Found in:** 2026-10-05 spec-governance sweep. `scripts/find-unwired-surfaces.py`
detector 7 reported `MapFileSystem`, `FakeSecureStorage` and `FakeDraftStore` as
symbols with test references and zero production references. The baseline recorded
each as `BacklogRef: none`, which its own header rule defines as a gate failure
("a line without a live backlog reference is a gate failure").

**Tracked as:** #97
**OpenSpec change:** `openspec/changes/unwired-detector-test-double-exemption/`

**Status: CLOSED (#97).** These are not dead code. They are test doubles that live in
`commonMain` production source, so they are reachable from `commonTest` without
depending on a JVM/Android-only source set. The detector cannot distinguish
"unwired production code" from "test infrastructure in the wrong source set",
so it flags them by construction.

**Decision (2026-10-05):** accept them as a known false positive of detector 7 and
give them this entry as a live backlog reference, rather than moving them. Moving
the fakes to a test-only source set would break `commonTest` compilation, which
cannot see `jvmTest` sources.

**Try next:** if detector 7 is ever refined to skip paths under
`test/fakes/` or filenames matching `Fake*`/`InMemory*`, these three lines can be
removed from `scripts/find-unwired-surfaces-baseline.txt` entirely. Until then the
baseline entry is the exemption, and this entry is why it is not `none`.

---

---

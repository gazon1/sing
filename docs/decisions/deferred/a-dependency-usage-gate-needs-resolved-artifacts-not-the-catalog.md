---
title: "A Dependency Usage Gate Needs Resolved Artifacts Not The Catalog"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED — tracked GitHub issue is closed****

**Tracked as:** [#205](https://github.com/gazon1/sing/issues/205)

**Found in:** 2026-10-05, while trying to close the gap that let MaterialKolor
sit declared-but-unimported in the catalog and on the `commonMain` classpath
while nothing referenced it.

**Situation.** `scripts/find-unwired-surfaces.py` counts symbols. A declared
dependency has no symbol to count until something imports it, so a library that
is vendored, resolved onto the classpath and called by nobody passes every
current gate. The obvious gate — "every `[libraries]` entry has at least one
import" — was assumed cheap in planning and is not.

**Why not.** A Gradle module coordinate does not determine the import package.
Mapping `org.jetbrains.compose.material3:material3` to the package a source file
imports is not a prefix operation; that one is `androidx.compose.material3`.
Measured on this tree: a first two-segment heuristic over all 98 library entries
reports **45 of 98 as unused**, and every one of those 45 is used. The heuristic
is wrong in nearly half the catalog, and a gate with 46% false positives is worse
than no gate — it trains everyone to ignore it.

**Checks already performed.** Ran the heuristic across `shared/src`,
`androidApp/src`, `desktopApp/src` and `mcp-server/src`; counted the false
positives by hand for the whole result set. Confirmed the mapping is the problem,
not the source sets (the failing entries are widely used: `koin-core`,
`compose-material3`, `kotlinx-coroutines-core`, `coil-compose`).

**Try next:** stop mapping coordinates to packages and read the packages out of
the resolved artifacts instead. A Gradle task that prints, per source set, the
resolved files with their originating coordinates gives a coordinate → artifact
map; scanning each artifact's entries for its package roots yields the real
mapping, including the KMP case where one coordinate contributes several
artifacts. The gate then compares that map against the catalog and needs no
guessing. Budget it as a small Gradle task plus a Konsist check, not a grep.

**Do not** re-attempt the prefix heuristic and "just allowlist the false
positives": a 45-entry allowlist of libraries that are definitely used is
indistinguishable, to the next reader, from a 45-entry list of libraries that
genuinely are not.

---

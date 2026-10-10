---
title: "Detektbaseline Caches Its Output And Cannot Drain"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Found in:** 2026-10-04, while trying to shrink the detekt baseline after fixing
`ViewModelMustHaveKDoc`. Four attempts produced an unchanged file.

**Status: OPEN**

**Tracked as:** #457

**Symptom:** `:shared:detektBaseline` is a Gradle task whose output is a tracked
source file. It gets cached like any other task, and two separate traps stack:

1. **It is additive.** Running it against an existing baseline merges rather than
   replacing, so an entry for a violation that no longer exists stays forever. The
   file has to be deleted first for it to shrink.
2. **It is cached.** With the file deleted, the task was still served from the
   build cache (`2 from cache`) and the *old* file was restored. `--rerun-tasks`
   alone was not enough; the combination that actually worked is:

   ```bash
   rm -f config/detekt/baseline-shared.xml config/detekt/baseline-desktopApp.xml
   ./gradlew :shared:detektBaseline :desktopApp:detektBaseline \
       --rerun-tasks --no-build-cache --no-configuration-cache --no-daemon
   ```

`./gradlew --stop` (documented in the detekt-rules-authoring skill for *rule*
changes) does not help here — the trap is the build cache, not the daemon. Two
attempts were lost to this, and the symptom is identical to "the fix did not
work": the entry is still in the file.

**Why it matters beyond the two entries I was chasing:** a baseline that cannot be
made smaller is not a ratchet, it is a high-water mark. `check-baseline-ratchet.py`
verifies the *committed* size, so it cannot detect that regeneration is a no-op.

**Try next:** the delete-plus-flags incantation above is the recipe; consider
putting it in a `just` recipe (`just detekt-baseline-drain`) so the next person
does not rediscover it, and note in the recipe that a plain `detektBaseline` run
only ever grows the file.

---

---
title: "Detektbaseline Drops Every Custom Rule Entry"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "detekt-tooling-honesty"]
---

**Found in:** 2026-10-05, immediately after the entry count of
`config/detekt/baseline-shared.xml` fell from 357 to 338 without anyone deleting an entry.

**Status: CLOSED**

**Tracked as:** #137
**OpenSpec change:** `openspec/changes/detekt-tooling-honesty/`

**Symptom.** `./gw :shared:detektBaseline` runs **without the custom rule set**. Regenerating
silently removes every custom-rule entry; the surviving file contains built-in rules only. The
`git diff` shows only removals, and nothing else reports it.

**Why it is separate from #58.** That entry is about entries that never leave — the baseline as a
high-water mark. This one is about entries that leave — the baseline as a lossy record. They share
a file and an incantation, so whichever lands first must consider the other or it will reintroduce
it.

**The part that actually matters.** The lost entries are recoverable by hand. The dangerous part is
that **regenerating the baseline is a way to turn the custom rules off and have the gate agree with
you.** Someone clearing debt, or absorbing a new rule's findings, silently converts `just lint` from
"the project's rules ran" to "only the built-in rules ran".

**Ruled out.** Not a path problem: `:shared:detekt` in the *same daemon* still caught a planted
`runCatching` violation, so the two tasks are demonstrably not sharing a plugin classpath. Not a
stale daemon: reproduced after `./gw --stop`.

**Try next.** `shared/build.gradle.kts` sets `baseline = …` inside the `detekt { }` extension and
declares `detektPlugins(project(":detekt-rules"))` in a separate `dependencies { }`. Confirm
whether the `detektBaseline` task family picks up the `detektPlugins` dependency at all, by
bisecting that file.

**Until then.** Never run `detektBaseline` without `git diff` on the baseline immediately after,
and treat a *shrinking* custom-rule section as a red flag rather than progress. `just cr` does not
run `detektBaseline`, so the gate itself is safe; this bites a human at a keyboard.

---

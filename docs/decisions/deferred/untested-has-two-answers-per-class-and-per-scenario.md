---
title: "Untested Has Two Answers Per Class And Per Scenario"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status:** CLOSED — tracked GitHub issue is closed

**Tracked as:** #300
**Supersedes:** #157 (closed — remaining decision captured in #300)

**Found in:** the same layer, while writing its README and skill and noticing
that the new matrix answers the question the old one already answered.

**Root cause:** `gaps.py` measures *never-run per test class* over the
`Automated/*` plans; the scenario matrix measures *claimed targets per user
scenario* over the `Scenarios` plan. Both are correct about different things, and
both are presented as authoritative. A reader arriving cold has no rule for which
to trust, and the two will drift in vocabulary — "hole" means a claimed target
with no automation in one layer and something closer to "never run" in the other.

**Already ruled out:** merging them. The polarity is opposite, so a single
instrument cannot express both: for a class, *never run* is the failure; for a
scenario, *never run* is normal and *missing from a claiming commit* is the
failure. The per-class floor stays correct for what it measures.

**Try first:** write the decision down before adding more scenarios. Growing the
layer without settling this is how two authoritative-looking answers to one
question get created. The decision owed: does the scenario layer eventually
replace the legacy per-class reporting, with Kover keeping code coverage and
`Automated/*` retired?

---

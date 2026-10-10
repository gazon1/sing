---
title: "Kover Full Jvmtest Run Unmeasured"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN**

**Tracked as:** [#85](https://github.com/gazon1/sing/issues/85); **#476** (branch coverage floor not wired, koverVerify not in CI)

**Additional finding (2026-10-07):** kover branch coverage is 65.1% with no floor set (no `verify { rule { } }` DSL), and `koverVerify` is not wired into `check` or CI. `just kover-rules` is informational only. Branch coverage: class 92.2%, method 89.4%, line 93.3%, instruction 89%.

**Found in:** MR-6, while building the agenda coverage ratchet.

Instrumentation for `:shared:jvmTest` is opt-in behind `-Pkover.jvmTest=true`,
and only a *filtered* run has ever been exercised — a filtered agenda suite,
~90 s, no OOM. The full suite under instrumentation has not been run since the
OOM that motivated disabling it, and that OOM was itself misattributed
(ledger #11 above: it reproduces with Kover off and in isolation).

**Status: MEASURED — the premise was wrong, the entry stayed open anyway**
(2026-10-04). The measurement this entry asks for was run: a full instrumented
`:shared:jvmTest` (every test, no filter, `-Pkover.jvmTest=true`) completed in
**9m27s** at **PEAK_RSS 125MB**, no OOM. Peak RSS is the number that matters
here, and it is nowhere near a memory ceiling — the `gradlew` wrapper process is
what the measurement covers, and the forked jvmTest JVM is a separate process.

So the OOM that motivated disabling instrumentation reproduces with Kover *off*
and in isolation (ledger #11 in `2026-09-27-write-layer-soundness.md`), which
means the flag was a workaround for a workaround. It stays opt-in anyway, for a
reason that has nothing to do with safety: `check.sh` runs on every change, and
instrumenting every test would add ~6 minutes to each of those runs.
`config/coverage-ratchet.json` documents the measured numbers inline so nobody
re-derives them.

**Still open:** merging the `desktopApp` report into the Kover report, which is
what would let the three Compose subtrees come out of the `excluded_subtrees`
list. Their 71.51% → 33.18% cliff is currently explained away in a config note
rather than measured, and a note is a promise, not a proof.

---

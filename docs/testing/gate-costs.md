# What the verification loop costs

The local loop is the sum of decisions nobody priced. This page publishes what it
costs, so that the next decision is priced too.

The numbers come from the runs that happened anyway: `sgate run-all` times every gate
it runs, and `check.sh` times every Gradle step it runs. Nothing is executed twice to
be measured, and the baseline in `config/docs/gate-benchmarks.json` records the
machine the numbers came from — a duration measured on one machine says nothing about
another, so the comparison refuses to conclude anything when the environment changed.

Two caveats, so the numbers are read as what they are: they are **one run on one
machine**, and a warm Gradle cache makes them lower than a cold one, a busy machine
higher than an idle one. Re-record after a cold, quiet run if that is the number you
want to compare against.

To refresh the numbers:

    ./check.sh                       # measure the loop
    sgate gate-costs --record        # write the baseline and re-render this page

The block below is generated. A hand-edited number is a failing gate, not a fact.

The price of the pre-push verdict is a second loop, and it is measured separately:
the verdict runs on the tree that is being pushed, in the developer's checkout, and
not in the loop above. `scripts/measure-verdict-cost.py` measures it and writes the
record in `docs/testing/verdict-cost.json`; the last section of the generated block is
rendered from that record and from nothing else. When the record is missing or stale,
the section says so — a price published as current when it is not is worse than a
price that is not published at all.

<!-- BEGIN GENERATED: sgate gate-costs -->

Recorded on 2026-10-21T15:00:39Z — cpus=16, javaHome=/usr/lib/jvm/java-25-openjdk, os=Linux, python=3.12.15.

| What | Median |
|------|--------|
| Step: adr-cli:detekt | 1.0 s |
| Step: adr-core+core-base:detekt | 961 ms |
| Step: androidApp:assembleDebug | 34.4 s |
| Step: androidApp:compileDebugKotlin | 27.0 s |
| Step: desktopApp:test | 13.1 s |
| Step: detekt | 1.0 s |
| Step: detekt-rules:koverVerify | 1.3 s |
| Step: detekt-rules:test | 1.7 s |
| Step: gate liveness | 1 min 10 s |
| Step: gate script tests | 1 min 45 s |
| Step: gate wiring | 2 min 27 s |
| Step: koverVerify | 1.4 s |
| Step: mcp-server:compileKotlin | 3.4 s |
| Step: mcp-server:detekt | 969 ms |
| Step: shared:jvmTest | 6 min 44 s |
| Step: shared:testAndroidHostTest | 35.8 s |
| Step: static gates | 38.3 s |
| Gate: GitHub workflows are valid | 307 ms |
| Gate: YAML is structurally valid | 4.0 s |
| Gate: adr references are valid | 558 ms |
| Gate: ci workflow shape is declared | 2.2 s |
| Gate: coverage holes did not grow | 1.4 s |
| Gate: coverage matrix current | 912 ms |
| Gate: declared dependencies are used | 8.5 s |
| Gate: detekt governance is consistent | 3.5 s |
| Gate: doc dead refs | 1.6 s |
| Gate: doc links | 573 ms |
| Gate: gate registries agree | 300 ms |
| Gate: gate registry is current | 710 ms |
| Gate: gates speak the JSON contract | 36.0 s |
| Gate: git hooks path is per-worktree | 674 ms |
| Gate: maestro flow catalogue is current | 880 ms |
| Gate: maestro selectors valid | 809 ms |
| Gate: pro licence boundary | 558 ms |
| Gate: provenance | 2.0 s |
| Gate: releases describe themselves | 839 ms |
| Gate: room schema integrity | 752 ms |
| Gate: scenario specs valid | 1.0 s |
| Gate: script conventions | 682 ms |
| Gate: settings read by a feature | 2.0 s |
| Gate: supabase schema is self-consistent | 915 ms |
| Gate: sync allowlist seed matches contract | 991 ms |
| Gate: the published gate costs are current | 521 ms |
| Gate: the shell registry is current | 512 ms |
| Gate: the toolbox surface is declared once | 481 ms |
| Gate: ui wait budgets are consistent | 1.3 s |
| Gate: unwired surfaces | 26.1 s |
| Gate: version catalog has no literals | 990 ms |
| Gate: waiver references resolve | 1.3 s |
| Gate: workflow env context resolves | 920 ms |
| Gate: workflow steps keep their exit status | 1.1 s |

### The instrumented coverage run

Measured on 2026-10-22 — 16 vCPU, warm Gradle cache.

| Population | Cost |
|---|---|
| fast | 37 min 25 s |
| full | 33 min 23 s |

The uninstrumented `:shared:jvmTest` in the table above is a different run: the Kover agent multiplies the cost of every test, and this is the number a timeout has to be set from.

### The last CI run

The CI loop is the one that blocks merges, so it is priced too — 2026-10-21T13:39:52Z — https://github.com/gazon1/sing/actions/runs/39609950342.

| Job | Duration |
|------|----------|
| Android (free) | 3 min 24 s |
| Android (pro) | 4 min 15 s |
| CI gate | 4.0 s |
| Gate tooling | 4 min 03 s |
| JVM tests, detekt, coverage | 31 min 29 s |
| Static gates | 4 min 17 s |
| adr-gate | 1 min 58 s |
| changes | 6.0 s |
| nightly-alert | — |
| spec-create | — |

### The price of the pre-push verdict

Measured by `scripts/measure-verdict-cost.py`; the record is `docs/testing/verdict-cost.json` — recorded 2026-10-23, tree 5896686b.

This record expires on 2026-11-22 — after that it is a history.

Measured while the host was at load 34.0 on 16 CPUs — the cost of the verdict is a property of the verdict and the host together, and this is the host.

| Phase | Cost | What ran |
|---|---|---|
| detekt | 5 min 07 s | the analysis the verdict waits for — `:shared:detekt`, read-only |
| auto-correct | 7 min 03 s | the same run with `--auto-correct` — what the verdict deliberately does not do, because it reads the tree it judges |
| digest | 1.0 s | the content digest the verdict computes before and after the analysis (`git ls-files` plus `git hash-object` over every Kotlin file) |
| together | 11 min 43 s | `:shared:detekt` and `:desktopApp:detekt` judged in ONE Gradle invocation — whether judging several modules costs less than judging them one after the other |

Correcting the tree costs 1 min 56 s on top of the read-only run — 38 % more than the analysis, and 27 % of the correcting run. The verdict reads the tree it judges and never corrects it, so the push waits for the analysis and not for the correction.

Notes from the record:

- `together`: the global `./gw` slots were already taken, so the two modules were serialised — parallelism was not available on this host
- The measurement was taken in the development checkout, which is shared with ten agent sessions: the load average was 34–50, so the absolute numbers are an upper bound and the ratios between the phases are the fact.

The command each phase ran is in the record:

- `./gw :shared:detekt --rerun --continue --no-daemon --console=plain`
- `./gw :shared:detekt --auto-correct --rerun --continue --no-daemon --console=plain`
- `git ls-files -- *.kt *.kts`
- `./gw :shared:detekt :desktopApp:detekt --parallel --rerun --continue --no-daemon --console=plain`

The record's fields:

- `measuredAt` — when the measurement was taken.
- `status` — `fresh` or `stale`; a stale record says why in `staleReason`.
- `expiresDays` — how long the record stays fresh before it must be re-measured.
- `sha` — the tree the measurement was taken on.
- `treeDigest` — the content digest of that tree; a tree that no longer has it makes the record stale.
- `environment`, `host` — the machine and its load: a duration without them is not comparable with anything.
- `phases` — what was measured: `name`, `what`, `command`, `durationMs`, `exitCode`, `note`.
- `notes` — the observations a number cannot make.

To measure it again:

    python3 scripts/measure-verdict-cost.py             # measure and write the record
    python3 scripts/measure-verdict-cost.py --refresh   # re-decide freshness only
<!-- END GENERATED: sgate gate-costs -->

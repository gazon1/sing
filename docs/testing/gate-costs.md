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

## The price of the pre-push verdict

The hook's detekt step is not in the block below: it runs on the tree that is
being pushed, in the developer's checkout, and its cost is what the push
waits for. Measured on 2026-10-23 with the hook's own invocation
(`./gw :shared:detekt :desktopApp:detekt --auto-correct --rerun`):

| Path | Seconds | What ran |
|---|---|---|
| warm — the current worktree | 561 s | `:shared:detekt` 7 min 26 s, `:desktopApp:detekt` 1 min 51 s |
| cold — a fresh worktree at the same sha | 565 s | `:shared:detekt` 8 min 20 s |

Two readings, and one caveat:

- **`:shared` is the whale.** 227 000 lines, 239 packages, 90 % of the verdict.
  The other modules are seconds to two minutes.
- **Warm is not warm.** The two paths cost the same, because `./gw` gives every
  worktree a private `GRADLE_USER_HOME` and the configuration cache is cold in
  a fresh worktree regardless. Judging in the current worktree saves the build
  directory, not the analysis — the number of modules is the lever.
- **Caveat:** the load average on this host was 34–50 (ten agent sessions
  share the machine), so the absolute numbers are an upper bound. The ratio
  between the paths is the fact.

The verdict was narrowed to the modules the change can reach (ADR
`2026-10-23-the-local-verdict-judges-what-the-change-reaches.md`), so the
price now follows the change: one module for a narrow change, none for a
change that moves neither Kotlin nor rules, and four for a change in
`shared`. The local verdict is partial, and the hook says so.

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
<!-- END GENERATED: sgate gate-costs -->

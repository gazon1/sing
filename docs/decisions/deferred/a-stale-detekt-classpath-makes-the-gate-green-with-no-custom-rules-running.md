---
title: "A Stale Detekt Classpath Makes The Gate Green With No Custom Rules Running"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "detekt-tooling-honesty"]
---

**Found in:** 2026-10-05, three times in one session, while adding the two rules that became
`NoUnreportedFailurePathRule` and `AppErrorCodeRule`.

**Status: CLOSED**

**Tracked as:** #136
**OpenSpec change:** `openspec/changes/detekt-tooling-honesty/`

**Symptom.** `:shared:detekt` reported success while no custom rule was running. Once as a hard
failure — `ServiceConfigurationError: Provider …NoUnreportedFailurePathProvider not found`, for a
provider that `unzip -p` showed in the jar's services file, that `javap` resolved, and that a
standalone `ServiceLoader` probe over that exact jar loaded alongside all eighteen others. Twice as
a **false pass**, the second time after `./gw :shared:detektBaseline` had left a baseline with
zero custom-rule entries and `:shared:detekt` then reported a clean tree.

**Ruled out.** Not the config: `config/detekt/detekt.yml` had the blocks. Not the services file:
it listed the provider. Not the jar: verified three ways. Not the configuration cache:
`--no-configuration-cache` did not help.

**Workaround, and it is not a fix.** `./gw --stop`. Every time.

**Why it is here and not just in the issue.** The lesson generalises past detekt, and this
repository now has several instances of it: `2026-09-26-pr-0-3-retro.md` records a baseline
regenerating "without all rules registered", #58 records the same task being cached, and this
records the rules not being loaded at all. Three records of the same class from three directions.
The general form — *a measurement that cannot be distinguished from its own failure* — is what the
proposed guard targets: plant a violation, assert it is reported, run that before trusting a green.

**Try next.** Reproduce deliberately: `./gw --stop`, add one rule, re-run without `--stop`. Then
find where the plugin classpath is cached. `--no-configuration-cache` already fails, which points
at the daemon's classloader or a Gradle transform keyed on a stale hash — dev.detekt 2.0.0-alpha.3
builds its plugin classloader in the worker.

---

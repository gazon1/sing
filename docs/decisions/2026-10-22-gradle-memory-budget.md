---
title: Gradle memory budget for parallel worktrees
status: accepted
date: 2026-10-22
tags: [gradle, memory, worktrees, build]
---

# Context

This machine has 16 vCPUs, 62 GiB of RAM, 8 GiB of swap and **33 git worktrees**
of the same repository. Every worktree has its own `GRADLE_USER_HOME` — the
isolation ADR (`2026-10-04-gradle-daemon-isolation`) requires it, because a shared
home makes `./gradlew --stop` a machine-wide weapon — and the side effect of that
isolation was never costed: **each worktree raises its own daemon set**.

Per worktree, before this decision:

| process | raised by | lives | heap |
|---|---|---|---|
| Gradle daemon | `org.gradle.jvmargs` | 3 h idle (Gradle's default) | 3 GB |
| Kotlin daemon | Kotlin Gradle Plugin | 2 h idle | 3 GB |
| test forks | `maxParallelForks` × `maxHeapSize` | per test class (`forkEvery = 1`) | 4 × 3 GB = 12 GB |

That is a **18 GB ceiling per active worktree**, and the ceiling was never the
number anybody looked at — the number was the sum of whatever happened to be
running.

What the machine actually showed on 2026-10-22:

- **CPU pressure is real.** `/proc/pressure/cpu` reported `some avg10=48.79`,
  `avg60=40.29` — roughly half of the time, tasks were *waiting for a CPU*.
  `full=0`: all 16 cores were busy, not throttled.
- **Memory pressure is not.** `/proc/pressure/memory` reported `some avg10=0.00`
  on every window. Swap stood at 7.2 of 8.0 GiB — **full, but not throttling**.
- **21 JVMs held 24.2 GB of RSS.** Six Gradle daemons and three Kotlin daemons
  were alive across the worktrees.
- **The OOM killer took one of our own.** `journalctl -k` shows
  `oom-kill: … task=java, pid=3554313` at 15:38:25 with `anon-rss:3615736kB` —
  and `daemon-3554313.out.log` places that pid in `lugworm/.gradle-user-home/daemon/9.7.1/`.
  The victim was a Gradle daemon of this repository, not a stranger's process.

So the problem has two halves with different shapes, and they need different
instruments. "The memory ceiling is 18 GB" and "the CPUs are oversubscribed" are
not the same complaint, and one remedy does not fix both: a slot pool limits
concurrency and frees nothing, and a smaller heap frees memory without limiting
concurrency.

A second fact shaped the decision. The CI runner has **16 GB of RAM** and the
current configuration's ceiling is the same 18 GB. A configuration that can
outlive its own runner is not a configuration that can be defended.

# Idea

Give a worktree a **budget** rather than a pile of defaults, and make the budget
**measured**:

1. The **compilation** runs inside the Gradle daemon, so the separate Kotlin JVM
   per worktree disappears. The daemon's heap then has to hold the compiler, so
   its size is a measurement, not a preference.
2. The **daemon** dies quickly when idle, so the worktrees nobody is working in
   stop holding a gigabyte each.
3. The **test forks** get a measured heap of their own.
4. A **slot pool** limits how many builds run at once — the CPU half of the
   problem.

# Decision

**D1. The Kotlin compilation runs in-process.**
`kotlin.compiler.execution.strategy=in-process` in `gradle.properties`.

Incremental compilation is **not** lost by this. The Kotlin plugin reads the
incremental flag from the project properties regardless of the execution
strategy; only the strategy switch differs (verified against the bytecode of
`kotlin-gradle-plugin-2.4.10-gradle.jar`: `BaseKotlinCompileConfig$1$1` calls
`setIncremental(...)` from `PropertiesProvider.getIncrementalJvm()`, and the
`IN_PROCESS` branch in `TasksUtilsKt` exists only to reword an error message).

**D2. A slot pool of three builds.** `scripts/gradle-slot.sh` holds one of three
machine-wide slots for the duration of a build; `./gw` runs every build through
it.

**D3. Two sets of coverage floors, one per population.** Fast-only on a pull
request, the full set on the schedule. See
`2026-10-22-ci-fast-only-pr-full-on-schedule.md`.

**D4. This is a spec-level change too** — the CI half is an
`openspec-change`, not only an ADR.

**D5. `GRADLE_USER_HOME` stays isolated.** The ADR of 2026-10-04 is not
reopened. The daemon registry cannot be shared by a property anyway:
`DaemonParameters` builds `baseDir` as `new File(gradleUserHomeDir, "daemon")`
with no property to change it. The idle timeout and the smaller heap deliver
the same saving without giving up the isolation.

**D6. Part A applies everywhere, CI included.** The CI runner has 16 GB; the
old ceiling was 18 GB, the new one is 12 GB. This is also where the first
measurement of the daemon's heap turned out to be wrong — CI was the surface
that could show it, and it did, on the first run of the branch.

**D7. The budget is a record, and a gate checks it.** The numbers this
decision measured live in `config/docs/gradle-memory-budget.properties`, and
the build reads that file. `scripts/check-memory-budget.py` checks the tree
against it: every test task runs with the heap the record declares, and the
daemon's heap in `gradle.properties` is the recorded one.

Before this, the heap of a test fork was a literal string in four build
scripts and the daemon's heap was a literal string in `gradle.properties`,
while the measurement behind them lived in this ADR — which no build read and
no gate checked. That is the shape the coverage floor had before its
declaration was moved to the place Kover reads it: **a number that nobody can
check is a number nobody enforces.** A module that raised its heap to 3 GB
would have stayed green everywhere, and the measurement behind 1536 MB would
have quietly stopped describing the build.

The record is written in the house style of
`config/docs/coverage-cost.properties`: a prose header that says what the file
is and where the measurement comes from, and data lines below it. The build
reads its own line of it — `test.heap.module.<project>` — so the number a
module's test JVMs run with is the number the record holds for that module.

`gradle.properties` is the one place the record cannot be read from: Gradle
reads that file before it reads anything else, and there is no way to
interpolate a value from another file into it. The heap is therefore written
there as well, with a comment that names the record, and the gate is what
keeps the two copies in step.

**D8. A module's coverage floor stays in that module's own build script.**
The `detekt-rules` floor is a **module** floor: it measures the branch
coverage of that module's own rules, and Kover reads it from
`detekt-rules/build.gradle.kts`, where the module declares the Kover plugin
and configures its own extension. The app floor is the aggregate one, and it
lives in `settings.gradle.kts`, because the aggregation plugin reads its rules
from there — the root project has no Kover extension of its own to configure.

The two are not interchangeable, and moving a module floor to the settings
would make it unenforced for the same reason the app floor was: a floor
declared in a place its Kover does not read is evaluated by no task. So the
rule is made explicit and checkable instead: **the app floor is declared in
`settings.gradle.kts`, a module floor in that module's own build script, and
no floor is declared where its Kover does not read it.** The app half of that
rule is `scripts/check-coverage-floor-declaration.py`'s job; the module half
— the floor's constant declared in the same file as the rule that binds it,
and no floor constant in the root build script — is `scripts/check-memory-budget.py`'s.
The two halves live in different gates because they are checked by different
readers: the app floor is read by the aggregation plugin, and a module floor
is read by the module.

## The measured numbers

Every value below was measured on this machine on 2026-10-22, with the
Kotlin compilation in-process, by bisecting on `-Xmx` and reading the daemon's
own GC log.

### The daemon's heap

The FIRST measurement was about one task, and it was wrong about a build.

| `-Xmx` | result |
|---|---|
| 1536 MB | `Evacuation Failure` at 1535M/1536M — the collector hit the ceiling and the build only survived because the heap was about to be reclaimed |
| 2048 MB | BUILD SUCCESSFUL in 3m12s, peak heap **1807 MB** after GC — for **one** compilation of the shared module |

2048 MB was written down as "the smallest value that leaves room". Then CI ran
the branch on 2026-10-22 and died:

```
> Task :shared:compileAndroidMain FAILED
> Task :shared:compileKotlinJvm FAILED
java.lang.RuntimeException: java.lang.OutOfMemoryError: Java heap space
```

Because a build is not a single task. CI compiles the shared module twice (the
Android target and the JVM target), then the desktop and android modules, and
the Kotlin compiler — running in-process — holds the live set of all of it in
the one heap. The 1807 MB was one compilation; the build is several.

The second measurement used the task set CI actually compiles, `--no-parallel`
as CI runs it, and read the daemon's RSS:

| `-Xmx` | result |
|---|---|
| 2048 MB | `OutOfMemoryError: Java heap space` — the CI failure, reproduced |
| 4096 MB | BUILD SUCCESSFUL, peak RSS **5.67 GB** — the daemon sat AT the cap through the whole compile |
| **6144 MB** | **BUILD SUCCESSFUL**, peak RSS **5.9 GB** — the heap grew past the 4 GB cap, so the live set is ~4.3 GB and 4 GB was never enough |

4096 MB "worked" locally and would not have worked in CI: the daemon was
heap-bound the whole way, and CI's step additionally holds Kover's agent and
the detekt analysis in the same heap. 6144 MB is the smallest round number
above the measured live set with room for the rest of the daemon.

The old value was 3072 MB — but the old build did not run the Kotlin compiler
inside the Gradle daemon at all, and the Kotlin daemon's heap was never
bounded: the plugin's default is a quarter of the machine's RAM, which is
15 GB on this host and 4 GB on a CI runner. "One daemon of 6 GB" replaces
"one daemon of 3 GB plus a second JVM with an unbounded heap".

### The workers

`org.gradle.workers.max` was 8, then 4, and is now **2** — and the reason is
memory, not CPUs.

8 oversubscribed the 16 vCPUs (`/proc/pressure/cpu` measured
`some avg10=48.79` on 2026-10-22: about half the time tasks waited for a CPU).
Four matched the core count. But the compilation runs INSIDE the daemon, so
four workers mean four concurrent compilations sharing one heap — and one
compilation's live set is ~4.3 GB. Two fits under the 6 GB ceiling; four would
OOM the daemon exactly as CI did. Two also leaves the slot pool room to run
three builds at once (6 compilations, 6 of 16 cores) instead of fighting over
them.

### The test forks' heap

Measured by running the full `shared:jvmTest` population with a reduced
`maxHeapSize` and watching for `OutOfMemoryError`:

| `maxHeapSize` | result |
|---|---|
| **1024 MB** | **`java.lang.OutOfMemoryError: Java heap space`** in the forked test JVM, with the Kover instrumentation agent attached — the run died with `Gradle Test Executor 106[jvm] FAILED` |
| **1536 MB** | **no `OutOfMemoryError` across the whole population** — the run completed with `executedClasses=440`, and the run manifest recorded `tags=fast,slow`, i.e. it was the *full* population, not a fast-only subset that would have fitted into less |

The 1536 MB row is a full-population run: the fast-only population would have
fitted into a smaller heap and would have proved nothing about the nightly run.
The measurement is therefore "the full suite passes at 1536 MB", which is the
claim the budget needs — not "1536 MB was enough for the tests that ran".

`forkEvery = 1` is **not** touched: it is the isolation invariant that
`ForkEveryIsolationTest` asserts, and it is load-bearing twice over (Koin
platform state between classes, and the `FileSystemContract` temp paths).

`maxParallelForks` is **not** touched either — 4 on a 16-core machine, 2 on a
4-core CI runner.

### The resulting ceiling

| component | heap |
|---|---|
| Gradle daemon | 6144 MB |
| Kotlin daemon | none — the compilation is in-process |
| test forks | 4 × 1536 MB = 6144 MB |
| **total per active worktree** | **12288 MB = 12 GB** |

The two big components do not run at the same time: the daemon's 6 GB are
live during COMPILATION, when no test fork exists, and the forks' 6 GB are
live during the TESTS, when the compiler has finished. The peak a worktree
actually reaches is therefore ~8 GB — but 12 GB is the number that is true
without an assumption, so it is the number written down.

Against a 62 GB machine with a swap that was already full, that is the number
that matters. It is also the number the fleet is bounded by: the idle timeout
(ten minutes) and the three-slot pool mean three to five daemons are live at
once, not thirty-three.

### The idle timeout

`org.gradle.daemon.idletimeout=600000` — ten minutes instead of Gradle's
default of three hours (10800000 ms). This is the main lever: 33 worktrees ×
3 GB × three hours of tail is where the memory went, and the tail is the part
nobody is using.

The cost is a cold daemon after a pause, and a cold daemon also means a cold
Android Studio sync, because the IDE reads the same `gradle.properties`. The
trade is accepted: a cold daemon costs a JVM start and a configuration-cache
load, and the saving is a gigabyte per idle worktree.

## The record, and the gate that checks it

`config/docs/gradle-memory-budget.properties` is the record of this decision:

| key | value | where it came from |
|---|---|---|
| `test.heap.default` | `1536m` | the measurement below — the full `:shared:jvmTest` population at 1536 MB |
| `test.heap.module.shared` | `1536m` | the same measurement |
| `test.heap.module.desktopApp` | `1536m` | the same measurement |
| `test.heap.module.mcp-server` | `1536m` | the same measurement |
| `test.heap.module.adr-core` | `1536m` | the default — the module declares no heap of its own |
| `test.heap.module.tools.unwritten-properties` | `1536m` | the default — the module declares no heap of its own |
| `test.heap.module.detekt-rules` | `3g` | the value the module declared before the record existed — NOT measured here |
| `test.heap.module.adr-cli` | `1g` | the value the module declared before the record existed — NOT measured here |
| `gradle.daemon.heap` | `6144M` | the measurement below |

The two module budgets that are not 1536 MB are the ones the 2026-10-22
measurement did not cover: it measured `:shared:jvmTest`, and the two
toolchain modules carry a different suite. They are recorded so that the gate
has one place to compare them with — and they are recorded as what they are,
the value the module ran with, rather than as a measurement they never had.

The gate (`scripts/check-memory-budget.py`) is textual, like the sibling
gates: the repository has no Kotlin-DSL parser. It finds the modules in
`settings.gradle.kts`, reads each module's `maxHeapSize`, and compares it with
the record. A module that reads the record is followed through the value it
reads — the name the module binds the record's line to — so that "the module
reads its own budget" is a checked statement and not a search for a string.

## The slot pool

`scripts/gradle-slot.sh` takes one of three machine-wide slots, held on file
descriptor 9 with `flock(2)`.

Three things about it are load-bearing and were each got wrong at least once
during the design:

- **The lock lives on an open file description, not on a process.** `flock`
  exits, and the lock stays — because descriptor 9 stays open in the shell. The
  child is started with `9>&-`, so the *Gradle daemon* does not inherit the
  descriptor and does not hold the slot for its lifetime.
- **The command's exit code is the result.** `flock` does not participate in it.
  A command that exits 75 is not "the slot was busy, run it again" — that
  conflation would re-run a build that already failed.
- **The wrapper does not set the "a slot is already held" flag.** The script
  sets it, for its child. A wrapper that set it would make the script's very
  first check true, and the pool would never take a slot at all.

The pool is off in CI (`GITHUB_ACTIONS=true` — one checkout per runner, and the
jobs on it run in parallel), and off with `SINGULARITY_GRADLE_SLOTS_DIR=0`.

**What the pool does not do:** it does not limit memory. The slot is released
when the build's client exits; the daemon it started lives on for the idle
timeout afterwards. Memory is held by the heap, not by the semaphore. The pool is
justified by the CPU measurement, and it is not offered as a memory remedy.

**What the pool does not cover:** Android Studio's own Gradle sync, and a bare
`./gradlew`. Both bypass `gw` on purpose — see the ADR of 2026-10-04 for why the
wrapper exists.

The pre-commit hook runs through `gw` but with the pool **off**. A commit must
not wait in a queue for up to thirty minutes behind somebody else's build; that
would contradict the point of a hook. The decision is explicit rather than
implied by "the hook is different".

## Stopping the daemons

`scripts/gradle-stop-all.sh` stops the daemons of every worktree, and
`just gradle::stop-all` is its recipe.

It uses the Gradle binary **already downloaded** in the worktree's wrapper
directory, with the version taken from the directory name of that worktree's
daemon registry. The wrapper script is deliberately not used: it would try to
resolve and download the distribution the branch points at, and "stop the
daemons" must not be an operation that reaches the network. A version whose
distribution is not on disk is skipped with a warning.

The shared `~/.gradle` is **never** a target. A daemon there belongs to whoever
is running a build right now.

# Rationale

**Why the compilation moves in-process.** A separate Kotlin daemon per worktree
is the largest single line item that no test needs: it exists to isolate the
compiler from the daemon, and with 33 worktrees it costs 3 GB each on a machine
whose swap is already full. The price is that parallel compile tasks now share
the daemon's heap — which is why the daemon's heap is *measured* rather than
assumed, and why the acceptance criterion checks for an `OutOfMemoryError` in
the daemon's log at `workers.max=4`.

**Why the idle timeout is the main lever.** The memory that hurts is not the
memory in use; it is the memory held by daemons nobody is using. A three-hour
idle timeout on 33 worktrees is 33 × 3 GB of pure tail. Ten minutes turns that
tail into a rounding error.

**Why the pool exists even though it fixes no memory.** The CPU pressure is
measured (`some avg10=48.79`) and it is the reason builds on this machine take
five times their solo time. The pool is the cheapest instrument for it: three
concurrent builds instead of an unbounded number.

**Why the fork heap is 1536 MB and not 1024 MB.** 1024 MB was the number that
would have made the budget comfortable, and it does not work: the full
population ran out of heap with the instrumentation agent attached. The budget
was written to be reachable — a floor that no legitimate run can satisfy is a
permanent red, and the coverage floors already record what that mistake looks
like (the 1003 that was never a measurement).

**Why the coverage floors became two sets.** A floor describes a measurement,
and the measurement depends on the population. Splitting the populations without
splitting the floors would have left the fast run checked against a number the
full run produced — unreachable by construction.

# Consequences

- The per-worktree heap ceiling is 8 GB instead of 18 GB, and the CI runner's
  16 GB is now enough for the configuration it runs.
- **The idle daemon count drops.** A worktree nobody is working in holds no
  daemon ten minutes after its last build.
- **A cold daemon is normal now.** Every pause longer than ten minutes costs a
  JVM start. Android Studio syncs feel it too.
- **The slot pool serialises work across worktrees.** Three builds at a time on
  this machine; a fourth waits up to 30 minutes and then fails with exit code
  97, which is deliberately distinct from Gradle's 0 and 1 and from the 75 that
  used to mean "busy". `SINGULARITY_GRADLE_SLOTS_DIR=0` opts out.
- **The rollout is ordered, and the order is part of the decision.** Changing
  `org.gradle.jvmargs` makes every live daemon incompatible: Gradle starts new
  ones and the old ones stay alive until their own idle timeout expires. So
  `just gradle::stop-all` runs immediately after the properties change, and the
  measurements are taken after the worktrees have been rebased — before that,
  the old daemons are still running and a "per machine" number means nothing.
- **The flake comparison across runs now compares different populations.** A
  pull request run (fast) after a nightly run (full) lists the slow classes as
  "no longer in the report". The step is advisory and does not fail on it, and
  the alternative — weakening what "disappeared" means — would lose a real
  signal: a deleted test.
- **The budget is checked now.** A module that runs its tests with a heap
  other than the recorded one is a finding, and so is a module that runs tests
  with no recorded heap at all. The cost of that is a record to keep true: a
  re-measurement is a change in the record, and the build follows it.
- **The two small toolchain modules now run their tests with the recorded
  default** (1536 MB) instead of Gradle's own default (512 MB). They declare
  no `maxHeapSize` of their own; the root build script applies the record's
  default to every test task, and a module that names a budget of its own
  overrides it. A ceiling is not a reservation — the forks take what they
  need — but the number is now the same number everywhere, and it is the
  number the record holds.

# Alternatives considered

**A shared daemon registry** (symlinking `daemon/` to a common directory) would
give one or two daemons per machine instead of 33. Rejected: it revokes ADR
2026-10-04, and `--stop` becomes global again — the exact accident that ADR
exists to prevent. The idle timeout and the smaller heap deliver the same
saving without reopening that decision.

**Raising the floor on the swap partition** or configuring `systemd-oomd` /
`earlyoom` would address the random OOM victim directly. Rejected: the task is
confined to tracked files of this repository, and both are system settings.

**Renaming the aliased `Tag` import** — rejected on the same grounds as before:
the domain class `com.singularity.todo.feature.tags.Tag` and `kotlin.test.Test`
make the alias mandatory in the files that use it. The scanner resolves the
alias instead.

**Develocity Predictive Test Selection** overlaps with the CI half of this
change conceptually, and was rejected: it needs a Develocity server, and its
sample is not deterministic — which collides with the requirement that one job
measures one population.

# Links

- `docs/decisions/2026-10-04-gradle-daemon-isolation.md` — the isolation this
  decision does not reopen
- `docs/decisions/2026-10-22-ci-fast-only-pr-full-on-schedule.md` — the CI half
- `scripts/gradle-slot.sh`, `scripts/gradle-stop-all.sh` — the instruments
- `config/docs/gradle-memory-budget.properties` — the record, read by the build
- `scripts/check-memory-budget.py` — the gate that checks the tree against it
- `gradle.properties` — the budget itself

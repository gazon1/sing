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
old ceiling was 18 GB.

## The measured numbers

Every value below was measured on this machine on 2026-10-22, with the
Kotlin compilation in-process, by bisecting on `-Xmx` and reading the daemon's
own GC log.

### The daemon's heap

| `-Xmx` | result |
|---|---|
| 1536 MB | `Evacuation Failure` at 1535M/1536M — the collector hit the ceiling and the build only survived because the heap was about to be reclaimed |
| **2048 MB** | **BUILD SUCCESSFUL in 3m12s**, peak heap **1807 MB** after GC |

The peak leaves ~240 MB of headroom, and one evacuation failure event — the
compiler was close to the ceiling. 2048 MB is therefore the *smallest* value that
works, not a comfortable one; the alternative is 2560 MB, which would push the
per-worktree ceiling past the budget in D6.

The old value was 3072 MB.

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
| Gradle daemon | 2048 MB |
| Kotlin daemon | none — the compilation is in-process |
| test forks | 4 × 1536 MB = 6144 MB |
| **total per active worktree** | **8192 MB = 8 GB** |

Against a 62 GB machine with a swap that was already full, that is the number
that matters.

### The idle timeout

`org.gradle.daemon.idletimeout=600000` — ten minutes instead of Gradle's
default of three hours (10800000 ms). This is the main lever: 33 worktrees ×
3 GB × three hours of tail is where the memory went, and the tail is the part
nobody is using.

The cost is a cold daemon after a pause, and a cold daemon also means a cold
Android Studio sync, because the IDE reads the same `gradle.properties`. The
trade is accepted: a cold daemon costs a JVM start and a configuration-cache
load, and the saving is a gigabyte per idle worktree.

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
- `gradle.properties` — the budget itself

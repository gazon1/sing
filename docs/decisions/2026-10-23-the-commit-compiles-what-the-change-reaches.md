---
title: The commit compiles what the change touches — the set is computed, not written down
date: 2026-10-23
status: accepted
supersedes: 2026-10-23-the-commit-hook-compiles-in-a-worktree
tags: [hooks, worktrees, build, tooling]
---

# The commit compiles what the change touches

## Context

The pre-commit hook compiled two modules, always: `:shared` (main and test) and
`:desktopApp`. The pair was written down so the hook had something to run, and
it stayed because a pair is easier to read than a rule.

The price of that pair was paid twice, and neither payment was visible.

**A change that could not be affected paid for a compile that could not say
anything about it.** A change to a gate script, a document or a configuration
file compiled the JVM target of `:shared` and the whole of `:desktopApp`. The
compile passed or failed on grounds that had nothing to do with the change, and
the wait was the hook's price for saying nothing.

**A change that could be affected was blocked by a breakage that was not its
own.** The ADR of 2026-10-23 records it plainly: "the compile is of whole
modules, not of the diff … a breakage that is NOT yours blocks your commits in
that worktree, and the only way past it is `--no-verify`". A module broken by
somebody else's commit was a wall in front of every commit in that worktree,
and the way past it was the one way that skips every check the hook makes.

The compile itself was right: the hook of 2026-10-23 made the commit compile,
and that is the check the whole line of work was for. What was wrong was the
*set* — a fixed pair, where the module graph could have named the set.

### What the old hook actually cost

The old hook made **two** Gradle invocations, and the price of a commit was
both of them:

1. `:shared:compileKotlinJvm :shared:compileTestKotlinJvm` — one invocation;
2. `:desktopApp:compileKotlin` — a second invocation.

Measured on 2026-10-23 on a loaded host (load 35–74 on 16 cores) with the
hook's own flags (`--no-daemon`, one invocation per list):

| What | Seconds | Condition |
|---|---|---|
| `:shared:compileKotlinJvm` | 490 s | cold — nothing of `:shared` compiled |
| `:shared:compileKotlinJvm` | 447 s | warm — up-to-date check |
| `:shared:compileKotlinJvm` + `:shared:compileTestKotlinJvm`, one invocation | 645 s | after `:shared` main was compiled |
| `:desktopApp:compileKotlin` | 393 s | cold |
| `:desktopApp:compileKotlin` | 197 s | warm |
| `:mcp-server:compileKotlin` | 443 s | cold |
| `:mcp-server:compileKotlin` | 251 s | warm |
| `:androidApp:compileDebugKotlin` | 497 s | cold |
| `:detekt-rules:compileKotlin` | 46 s | cold |
| every module at once: `:shared` (main + test), `:core-base`, `:adr-core`, `:adr-cli`, `:detekt-rules`, `:desktopApp`, `:mcp-server`, `:tools:unwritten-properties` | 842 s | cold, **one invocation**, no Android SDK |

**The rows are separate invocations, and they do not add up.** Each row was
measured on its own, so each one paid for the Gradle configuration by itself.
The last row is the whole set in a single invocation, and it costs 842 s
rather than the sum of the parts — one configuration, one build. That is also
why the old hook's two invocations cost 1038 s while its first invocation
alone cost 645 s: what was expensive was the second invocation, not the
modules it compiled.

So the old hook paid **645 s + 393 s = 1038 s** for every commit, whatever the
change — for a change that might have been a line in a document. A second run
with a daemon and nothing to compile gave 447 s, 197 s and 251 s for the first
three: the host, not the daemon, is where the time goes.

**These numbers and the published table answer different questions.**
`docs/testing/gate-costs.md` publishes the median of each gate *step* —
`androidApp:compileDebugKotlin` 27.0 s, `mcp-server:compileKotlin` 3.4 s —
and those are the cost of an **up-to-date** step: the check that says "nothing
needs recompiling". The numbers above are **cold full compiles** of the same
tasks: what a developer pays the first time the Kotlin of that module changed.
Both are real; neither is the other, and the ratio inside each set is the fact.
The published table is not a baseline for this ADR, and this ADR does not
contradict it.

## Decision

**The compile set is the modules the change touches**, and it is computed from
the module graph — `scripts/changed-modules.py`, the one that already answers
"which module owns this path?" for the local verification plan and for the
pre-push detekt verdict. The commit gate asks the same question and reads the
same answer.

Three rules, and the reason for each:

1. **A change to the build compiles everything.** `settings.gradle.kts`,
   `gradle/`, `build-logic/`, `gradle.properties` and the root build file
   decide how every module is built, so a change to them can break every
   module. (`build-logic/` was missing from the build areas of the graph: a
   change to a convention plugin compiled nothing at all, while the pre-push
   verdict, which knows the same fact, judged every module. That gap is closed
   here.)

2. **A change that touches no module compiles nothing.** A change under
   `scripts/`, `docs/` or `config/` is a change to the gates. There is no
   Kotlin in it, and the hook says what it did instead of waiting.

3. **`:androidApp` is compiled when the change touches it, and the Android SDK
   is CI's.** The Android target needs the SDK. When the change touches
   `:androidApp` and no SDK is reachable from the machine, the script says that
   it did not compile it — a check that did not run must not look like a check
   that passed.

**The scope is the modules the change touches, and not the modules that compile
against them.** That is a deliberate narrowing, and it is where the reach of a
change is judged instead. What judges it, precisely:

- **The pre-push verdict judges the reach with detekt** — style, complexity and
  the repository's own rules, on the modules the change reaches. Detekt runs
  **without type resolution** in this repository, so it is not the compiler:
  a *type* error in a reachable module passes the verdict.
- **CI is the compiler's verdict.** Every push builds the modules that carry
  the app — `:shared` through the JVM tests, `:desktopApp` and `:mcp-server`
  through their test tasks, `:androidApp` through `compileDebugKotlin` — so a
  type error in a reachable module fails the push.
- **One module is neither built nor tested by CI:**
  `:tools:unwritten-properties` is a standalone analysis module that nothing
  depends on, and no CI job builds it. A change in it is verified by the
  commit hook alone, and its own tests do not run in CI. That is a hole in
  CI's coverage, not a consequence of this decision, and it is named here
  rather than left for someone to find out the hard way.

The commit hook answers one question, and only that one: does the code I just
wrote compile?

The narrowing is not only a price argument, though it is that too. The old
pair was redundant in one direction: `:desktopApp` depends on `:shared`, so
compiling `:desktopApp` compiles `:shared` as well — the pair paid twice for
the same Kotlin. The closure of the graph, for a change in `:shared`, names
four modules: `:shared`, `:androidApp`, `:desktopApp` and `:mcp-server`. That
set is a subset of the widest set of the table above, so compiled in one
invocation it costs no more than **842 s** — against the 645 s of `:shared`
alone, the module the change actually touches, and against the 1038 s the
fixed pair cost in two invocations. So the closure is not cheaper than the
touched set: it is bigger, and the commit hook has no business compiling a
module the change did not touch. The set that answers the commit gate's
question is the set of modules the change touched.

The plan is printed before the compile: which modules, which tasks, and why
each one is in the set. A compile that runs without saying what it covers is a
compile nobody can trust a week later.

## Consequences

- **The hook compiles in a worktree, exactly as it does in the main checkout.**
  That decision is not changed here — it is the one of
  `2026-10-23-the-commit-hook-compiles-in-a-worktree`, and it stands. What
  changed is the set: the hook no longer compiles a fixed pair, it compiles
  what the change touches.
- A commit that touches no module costs the lint step alone. The compile of
  two whole modules is no longer the price of every commit.
- A commit is blocked by a breakage in a module it does not touch. The module
  it does touch still blocks it — that is the check, and `--no-verify` remains
  the way past every check the hook makes.
- A change in `:shared` that breaks `:desktopApp` is no longer found at commit
  time. It is found by **CI**, which compiles the app's modules on every push —
  and the pre-push verdict does *not* find it, because detekt there runs
  without type resolution. That is the narrowing, stated here rather than
  discovered later, and so is the hole it leaves: `:tools:unwritten-properties`
  is built by no CI job, so a change in it is verified by the commit hook
  alone.
- The Android target is CI's, and the local loop says so rather than pretending
  it checked.
- The module graph grew a table: `COMPILE_TASKS` says what has to compile for
  a module, next to the table that says what has to run for it. One home for
  "what belongs to which module".
- A graph that has fallen behind the build is an **error**, not an empty plan:
  a module the graph does not name a compile task for is reported and the
  commit stops. A module that is not compiled locally says why, in
  `NOT_COMPILED_LOCALLY`.
- The paths are read out of git in the `-z` format, and git hides three things
  without it: a rename names only one of its two paths, a path with a space, a
  quote or a backslash is written in quotes, and an untracked directory is
  collapsed into one path. A quoted path starts with a quote character, so no
  module claims it — and the commit would pass without compiling anything.
- A base that cannot be measured against is an error, not an empty plan: the ref
  has to resolve **and** to have a merge base with `HEAD`. A base that resolves
  but shares no history with the branch makes the three-dot diff fail, and the
  committed part of the change would silently fall out of the plan.

The price, from the measurements above: a commit that touches no module pays
**0 s** of compile instead of 1038 s; a commit in `:detekt-rules` pays 46 s;
a commit in `:desktopApp` pays 393 s instead of 1038 s; a commit in `:shared`
pays 645 s instead of 1038 s.

A change to the build itself compiles every module, and that set is measured
too: one invocation of all of them — `:shared` (main + test), `:core-base`,
`:adr-core`, `:adr-cli`, `:detekt-rules`, `:desktopApp`, `:mcp-server` and
`:tools:unwritten-properties` — is **842 s** without the Android SDK. Even the
widest case is cheaper than the fixed pair, because one invocation pays for the
configuration once and the old pair paid for it twice.

## Links

- `scripts/compile-changed.py` — the plan and the compile
- `scripts/changed-modules.py` — the module graph, and the compile tasks
- `.githooks/pre-commit` — the hook that calls it
- `scripts/tests/test_compile_changed.py` — the tests of the plan
- `scripts/tests/test_pre_commit_hook.py` — the hook, driven through a real commit
- ADR `2026-10-23-the-commit-hook-compiles-in-a-worktree` — the hook that made the commit compile
- ADR `2026-10-23-the-local-verdict-judges-what-the-change-reaches` — where the reach of a change is judged
- ADR `2026-10-22-gradle-memory-budget` — the slot pool the compile switches off

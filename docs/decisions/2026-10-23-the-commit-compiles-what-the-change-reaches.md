---
title: The commit compiles what the change touches — the set is computed, not written down
date: 2026-10-23
status: accepted
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

The price of the pair, measured on 2026-10-23 on a loaded host (load 35–74 on
16 cores, with the hook's own flags — `--no-daemon`): `:shared:compileKotlinJvm`
490 s, `:desktopApp:compileKotlin` 393 s, `:mcp-server:compileKotlin` 443 s,
`:androidApp:compileDebugKotlin` 497 s, `:detekt-rules:compileKotlin` 46 s.
Every commit paid the first two: **883 s**, for a change that might have been
a line in a document. A second run with a daemon and nothing to compile gave
447 s, 197 s and 251 s for the first three — the host, not the daemon, is
where the time goes.

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
change is judged instead: the pre-push verdict judges the affected modules, and
CI compiles every module on every push. The commit hook answers one question:
does the code I just wrote compile?

The narrowing is not only a price argument, though it is that too. The modules
that compile against a changed module are compiled BY it: `:desktopApp` compiles
`:shared` as a dependency, so a change in `:shared` is already in the compile
of `:desktopApp` — and the hook does not pay for that compile twice. The
closure of the graph was measured: for a change in `:shared` it is `:shared`,
`:desktopApp` and `:mcp-server` — 1326 s against the 883 s the fixed pair
cost, for a set that is larger and no more honest. The set that answers the
commit gate's question is the set of modules the change touched.

The plan is printed before the compile: which modules, which tasks, and why
each one is in the set. A compile that runs without saying what it covers is a
compile nobody can trust a week later.

## Consequences

- A commit that touches no module costs the lint step alone. The compile of
  two whole modules is no longer the price of every commit.
- A commit is blocked by a breakage in a module it does not touch. The module
  it does touch still blocks it — that is the check, and `--no-verify` remains
  the way past every check the hook makes.
- A change in `:shared` that breaks `:desktopApp` is no longer found at commit
  time; it is found by the pre-push verdict and by CI. That is the narrowing,
  stated here rather than discovered later.
- The Android target is CI's, and the local loop says so rather than pretending
  it checked.
- The module graph grew a table: `COMPILE_TASKS` says what has to compile for
  a module, next to the table that says what has to run for it. One home for
  "what belongs to which module".

The price, from the measurements above: a commit that touches no module pays
**0 s** of compile instead of 883 s; a commit in `:detekt-rules` pays 46 s;
a commit in `:desktopApp` pays 393 s; a commit in `:shared` pays 490 s
instead of 883 s.

## Links

- `scripts/compile-changed.py` — the plan and the compile
- `scripts/changed-modules.py` — the module graph, and the compile tasks
- `.githooks/pre-commit` — the hook that calls it
- `scripts/tests/test_compile_changed.py` — the tests of the plan
- `scripts/tests/test_pre_commit_hook.py` — the hook, driven through a real commit
- ADR `2026-10-23-the-commit-hook-compiles-in-a-worktree` — the hook that made the commit compile
- ADR `2026-10-23-the-local-verdict-judges-what-the-change-reaches` — where the reach of a change is judged
- ADR `2026-10-22-gradle-memory-budget` — the slot pool the compile switches off

# Build scripts

> **Reference, not a procedure.** This page is about the build scripts of this
> repository: how to change one, and how to find out that you broke one.

## The shape of the build

| File | What it holds |
|---|---|
| `settings.gradle.kts` | The modules, and every coverage floor — the floors live here, because the root project has no Kover extension of its own, and because one file holds the whole floor policy. A module binds the floor it checks; it does not declare one. |
| `build.gradle.kts` | The shared configuration: detekt, kover, the Gradle version catalogue. |
| `gradle/libs.versions.toml` | The version catalogue. |
| `gradlew` / `./gw` | The wrapper. **Use `./gw`**: it gives the worktree a private `GRADLE_USER_HOME`, so a worktree does not fight the neighbouring checkouts over the shared cache. |

The root `build.gradle.kts` is one file with one shared block, plus whatever each
module adds. A change that affects every module belongs in the shared block; a
change that affects one module belongs in that module's file.

## A conflict in a build script

A rebase or a merge can conflict in `build.gradle.kts`, and the conflict is
awkward: both sides changed the same region, and the result is a file that
parses as nothing.

**Take the upstream file whole and re-apply your own block on top of it.** Do not
merge the two sides by hand. The reason is mechanical — the two sides are written
for different sets of blocks, and a hand-merge produces a file that is neither:
blocks in the wrong scope, a `val` declared inside `subprojects { }` where it was
meant to be at the top level, a duplicated plugin block. Those mistakes are
invisible until something fails much later, and the failure names a line that has
nothing to do with the mistake.

The recipe:

1. `git show origin/main:build.gradle.kts > /tmp/upstream-build.gradle.kts` — the
   file as it is upstream, whole.
2. Take your own block from your branch (`git show HEAD~1:build.gradle.kts`) and
   put it back into the upstream file — in one place, at the top level, with the
   imports it needs.
3. Check that the file **parses** — see below. This is the step that is cheap and
   that everybody skips.

## The check that finds a broken build script in seconds

Gradle **configures** the root project before it runs any task, so a syntax error
in a build script is found while the project is being configured — not while the
code is being compiled:

```bash
./gw help      # seconds: the build script is read, and that is all
```

`./gw :shared:compileKotlinJvm` finds the same error, but only after the build
script is read, the project is configured, and the compiler is started — minutes,
on a loaded machine, and the error arrives with a stack trace of the build rather
than with the line that is wrong.

So: **`./gw help` is the check for a build script.** Run it after every edit of
`build.gradle.kts`, `settings.gradle.kts` or a module's build file, and before
the first compile.

## What the detekt block does here

`build.gradle.kts` registers `detektChanged` — a task that runs detekt over the
files a change touched rather than over the whole module. `just lint-changed`
calls it; the pre-commit hook calls it too. It exists because the whole-module
detekt run takes minutes, and a mechanical finding in the file you just wrote
should be visible in seconds.

The task is at the **top level** of the build script, not inside `subprojects { }`:
a task declared inside `subprojects` is registered for every module, and the
values it reads would be local to that block. If a build change "does nothing",
that is the first thing to look at.

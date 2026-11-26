# FlowMVI spike (#3234) — disposable

Comparison of this repository's MVI with FlowMVI 3.2.1 on the same Task List
scenarios. **Nothing here is production**, and no app module depends on it.

The answer is in [`FINDINGS.md`](FINDINGS.md). Short version: FlowMVI builds here and
reproduces the behavior exactly, and is still not worth adopting.

## Why a standalone build

Same shape as `build-investigation/kt-62686/probe`: one directory, no root
`settings.gradle.kts` change, no app module depends on it, and every repository gate
that enumerates modules ignores it. Adding `:flowmvi-spike` to the app build would have
put a throwaway module into the coverage denominator, the detekt registry and the gate
wiring check.

The price of that isolation is that variant A cannot `implementation(project(":shared"))`
from here, so the production MVI is staged by a `Sync` task instead — see below.

## Layout

| Path | What it is |
|---|---|
| `spike/` | The comparison: one contract, two variants, one suite. JVM. |
| `spike/src/commonMain/kotlin/spike/scenario/` | The Task List contract. Names neither framework. |
| `spike/src/commonMain/kotlin/spike/variantA/` | The app's `MviViewModel`, subclassed as production does. |
| `spike/src/commonMain/kotlin/spike/variantB/` | The same behavior in FlowMVI 3.2.1. |
| `spike/src/commonTest/` | The six scenarios, written once. |
| `spike/src/jvmTest/` | The comparison run and the semantic-gap tests. |
| `resolveProof/` | The issue's stop/go gate: FlowMVI core + compose on both targets, plus Koin and Navigation 3 ownership. |

## Variant A is the real thing, not a copy

`spike/build.gradle.kts` stages eleven files from `shared/src/commonMain` with a `Sync`
task whose **inputs are those files**: editing production invalidates the staging. The
probe therefore compiles production bytecode, and a divergence is impossible rather than
unlikely.

Two approaches that do not work, recorded so the next attempt does not repeat them:

- a source **symlink** into `shared/` — Gradle's file snapshot refuses a source that
  escapes the project directory (`Couldn't follow symbolic link`);
- `kotlin.srcDir(<single file>)` — `srcDir` requires a directory.

## Run

There is **no wrapper in this directory**, on purpose: a `gradle-wrapper.jar` is
gitignored (`*.jar`), so a wrapper committed here would be missing from every fresh clone
and the instructions would not work. The probe is driven by the repository's own wrapper
with `-p`, which is also how the `kt-62686` probe is run.

```bash
cd build-investigation/flowmvi-spike
../../gw -p . :spike:jvmTest
../../gw -p . :resolveProof:compileKotlinJvm :resolveProof:compileAndroidMain
```

Measured on 2026-11-27, host idle, via exactly those two commands: 13 tests / 0 failures,
and BUILD SUCCESSFUL for both targets.

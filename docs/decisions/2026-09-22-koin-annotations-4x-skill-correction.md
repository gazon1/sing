---
title: "Koin Annotations 4.x skill correction — removed aspirational @IntoSet/@Single references"
date: 2026-09-22
tags: []
status: accepted
---

## Context

During the planned Koin Annotations adoption (ADR `2026-09-21-tier1-interface-cleanup` companion track), we discovered that three skills in `.agents/skills/` documented Koin Annotations incorrectly:

- `singularity-todo-koin-di` — listed `@Single`, `@IntoSet`, and `koin-annotations-compiler` as Koin 4.x annotations
- `singularity-todo-ai-tool` — described tool registration via `@Single @IntoSet`
- `singularity-todo-feature-scaffold` — referenced `@IntoSet` as the extensibility pattern
- `singularity-todo-koog-agent` — showed `@ComponentScan` as if it were current state

Direct jar inspection of `io.insert-koin:koin-annotations-jvm:4.2.2` revealed all of these references were aspirational (Koin 2.x docs) or fictional.

## Discovery method

We downloaded `koin-annotations-jvm-4.2.2.jar` from Maven Central and listed its contents:

```bash
unzip -l koin-annotations-jvm-4.2.2.jar | grep "org/koin/core/annotation/.*\.class"
```

Real annotations present in 4.x: `ComponentScan`, `Configuration`, `Factory`, `KoinApplication`, `KoinViewModel`, `Module`, `Monitor`, `Named`, `Property`, `PropertyValue`, `Qualifier`, `Scope`, `ScopeId`, `Scoped`, `@Singleton` (yes, with the "on" suffix), `ViewModelScope`.

**Critical absences:**
- `@Single` (no "on" suffix) — does NOT exist. Aspirational examples used this name.
- `@IntoSet` — does NOT exist. This was a Koin 2.x feature for `Set<T>` aggregation that was not carried forward into 4.x annotations.
- `koin-annotations-compiler` artifact — does NOT exist for 4.x. The compiler is now shipped as `io.insert-koin:koin-gradle-plugin` (a Gradle plugin, not a KSP processor dependency).

The JAR's `META-INF/gradle-plugins/koin.properties` confirms the plugin id is `koin` (not `io.insert-koin.compiler`).

## Idea

1. Keep using DSL — Koin Annotations offer no win for this project's module sizes.
2. Adopt Koin Annotations 4.x for the AiToolsModule only — 32 tools means `@ComponentScan` removes manual enumeration of tools.
3. Replace skill documentation with accurate 4.x annotation list.

We chose option 3 for this PR, with option 2 explicitly deferred.

## Decision

Update four skills to reflect verified Koin 4.2.2 reality:

1. `singularity-todo-koin-di`:
   - Replace "Key Annotations (planned, not yet adopted)" section with verified 4.x annotation list (jar contents above)
   - Add explicit "Common misconceptions" subsection listing `@Single`, `@IntoSet`, `koin-annotations-compiler` as non-existent in 4.x
   - Replace "Gotchas" item about `koin-annotations-compiler` with correct `koin-gradle-plugin` Gradle DSL setup
   - Replace `@IntoSet` section with `getAll<T>()` runtime aggregation pattern
2. `singularity-todo-ai-tool`:
   - Replace frontmatter `@IntoSet` claim with `getAll<Tool<*, *>>()` aggregation
   - Replace aspirational `@Single @IntoSet` example with current DSL pattern + future-state `@Singleton` alternative
3. `singularity-todo-feature-scaffold`:
   - Update the `@IntoSet` row to reflect `getAll<T>()` aggregation
4. `singularity-todo-koog-agent`:
   - Update "Tool Registration via Koin" to current DSL state + call out 4.x annotation caveats

Koin Compiler Plugin infrastructure was already wired in commit `1eb272a` — verified to load without breaking the existing DSL. Single-tool annotation experiment (then reverted) proved the plugin processes `@Singleton` correctly.

## Rationale

- **Verified-by-jar fact-gathering** — every claim in the corrected skills is supported by direct inspection of the published artifact, not by 2.x docs or aspirational examples.
- **Documentation accuracy > convenience** — future agents following these skills would otherwise be misled into writing code that fails to compile (`@Single`) or doesn't exist (`@IntoSet`).
- **Defer code migration** — the cost-benefit analysis for actually migrating AiToolsModule to annotations is deferred. With `@IntoSet` not available, the manual `listOf(get<RefineTaskTool>(), ...)` block is the only aggregation mechanism, and it's already short enough that annotations don't pay off. A future PR can revisit if/when the project gains enough tools to make `@ComponentScan` worth the boilerplate.

## Consequences

- All skills now reference verified Koin 4.x API surface (jar inspection as the ground truth).
- Future agents reading these skills will not waste time on `koin-annotations-compiler` setup that doesn't exist.
- Code migration to Koin Annotations is explicitly **deferred** — see ADR `2026-09-22-koin-annotations-4x-skill-correction` for the analysis.
- The `koin-gradle-plugin` is already wired in `shared/build.gradle.kts` (commit `1eb272a`) but no annotations are in use. If a future agent wants to adopt annotations, they can reapply the pattern shown in commit `1eb272a`'s setup; the plugin doesn't break anything.

## Links

- Direct jar inspection: `~/.gradle/caches/modules-2/files-2.1/io.insert-koin/koin-annotations-jvm/4.2.2/.../koin-annotations-jvm-4.2.2.jar`
- Koin Compiler Plugin JAR: `~/.gradle/caches/modules-2/files-2.1/io.insert-koin/koin-gradle-plugin/4.2.2/.../koin-gradle-plugin-4.2.2.jar` (contains `META-INF/gradle-plugins/koin.properties` → `org.koin.gradle.KoinPlugin`)
- Koin 4.x setup reference: https://insert-koin.io/docs/setup/compiler-plugin
- Koin 4.x annotations reference: https://insert-koin.io/docs/reference/koin-annotations/start
- Related ADRs: `2026-09-06-koin-vm-viewmodelof-koinviewmodel.md` (note: "KSP not configured" reason is now obsolete; the migration path is open), `2026-09-21-tier1-interface-cleanup.md`
- Related commit: `1eb272a` (Koin Compiler Plugin infrastructure)

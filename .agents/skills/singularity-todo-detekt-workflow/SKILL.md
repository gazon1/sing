---
name: singularity-todo-detekt-workflow
description: 'Run detekt and ktlint in this KMP project: format, auto-fix, baseline rebuild, and the project''s custom rules. Use before committing or when a lint failure needs diagnosing. For rule authoring see singularity-todo-detekt-rules-authoring; for the full toolchain (ktlint, kover, coverage) see singularity-todo-quality-tools.'
---

> **When to use:** Any lint/formatting work, especially before committing, or when adding new code patterns.

## ⚠️ Auto-correct works only per module (ADR 2026-10-17)

`--auto-correct` is **not** inert — but it only reaches the **last** detekt task of a
Gradle invocation. Passing `:shared:detekt :desktopApp:detekt --auto-correct` reports
both and rewrites neither except the last. Run **one module per invocation**, with
`--rerun-tasks` so a cached task does not skip the rewrite:

```bash
for task in :shared:detekt :desktopApp:detekt :detekt-rules:detekt :androidApp:detekt :mcp-server:detekt; do
    ./gw "$task" --auto-correct --rerun-tasks --console=plain || true
done
./gw :shared:detekt :desktopApp:detekt :detekt-rules:detekt :androidApp:detekt :mcp-server:detekt --console=plain
```

First pass corrects; second pass reports what is left. Collapsing the loop into one
call reintroduces the bug silently. See ADR `2026-10-17-detekt-auto-correct-works-use-it`.

## Workflow

### 1. Report (before commit)

```bash
just detekt-fix
```

Runs the per-module auto-correct loop above, then a report-only pass. Violations that
cannot be auto-fixed still need manual edits.

```bash
just lint
# equivalent to:
./gw :shared:detekt :desktopApp:detekt :detekt-rules:detekt :androidApp:detekt :mcp-server:detekt
```

**Enforcing** — `ignoreFailures = false` in `shared/build.gradle.kts` and
`desktopApp/build.gradle.kts`. A violation fails the build; the baselines in
`config/detekt/` cover the accepted debt.

### 2. Fix manually

For each reported violation, open the file and fix it by hand. Common patterns:
- **Naming** (FunctionNaming, VariableNaming, etc.): rename
- **Complexity** (LongMethod, TooManyFunctions): split or suppress with `@Suppress`

Layout (ArgumentListWrapping, ImportOrdering, indentation) is **not** detekt's job
anymore — it is ktlint's. Do not reformat by hand; run the formatter:

```bash
just ktlint-fix     # ktlint CLI rewrites the tree in seconds
just ktlint         # check only
```

### 2b. Format after every Kotlin edit

Run the formatter **right after** you write Kotlin, before you lint:

```bash
just ktlint-fix
```

A formatting violation caught by the lint round is a wasted round trip. The
formatter is a separate, fast tool — it does not wait for detekt, and it does
not need a Gradle daemon. See ADR
`2026-11-02-ktlint-cli-replaces-the-detekt-formatting-layer.md`.

### Custom rules that bite in Composables

- **NoUnstableCollectionParam** — a `@Composable` must not take a raw `List`/`Set`/`Map`
  parameter; use `ImmutableList`/`ImmutableSet`/`ImmutableMap` (from kotlinx.collections.immutable).
  Passing a raw collection breaks skippability and the rule fires at the parameter.
- **NoCollectionMaterializationInComposable** — a `@Composable` must not call
  `toImmutableList()`/`toImmutableSet()`/`toImmutableMap()` in its own body (it rebuilds
  on every recomposition). Build the collection in `remember { … }`, `derivedStateOf { … }`,
  an event handler, or a top-level `val`, and hand that instance over unchanged.
  A content lambda (`Column { … }`, `items { … }`) is NOT exempt — it recomposes with
  the parent.

### 3. Rebuild Baseline (capture accepted debt)

```bash
just detekt-baseline
# or
./gw :shared:detektBaseline :desktopApp:detektBaseline :androidApp:detektBaseline :mcp-server:detektBaseline
```

Captures **current** violations into `baseline-shared.xml` etc. Run after a large
manual-fix pass to freeze accepted debt.

### 4. Adding a new rule

1. Write the rule and its provider in `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/`,
   named after the rule — e.g. `NoCombineSideEffectRule.kt` + `NoCombineSideEffectRuleProvider`.
   Both names are built from the rule's own name — the placeholder above is not a
   file to create, and `check-doc-dead-refs.py --skill-symbols` will say so.
2. **Add the provider to `detekt-rules/src/main/resources/META-INF/services/dev.detekt.api.RuleSetProvider`.** A rule missing from this file never loads.
3. Add the `xxx:` block to `config/detekt/detekt.yml` with `active: true`.
3b. **Check the rule can run at all.** If its class implements `dev.detekt.api.RequiresAnalysisApi`,
   the aggregate detekt task drops it before reading a file and the rule is inert no matter how
   many findings you expect. Check one name with:

   ```bash
   jars=$(find ~/.gradle/caches/modules-2/files-2.1/dev.detekt -name 'detekt-rules-*-2.0.0-alpha.6.jar' | grep -v sources)
   javap -classpath "$jars" dev.detekt.rules.style.YourRule | head -2
   ```

   `implements dev.detekt.api.RequiresAnalysisApi` → do not ship it as a guard; the ground it
   covers is owned by no gate (ADR `2026-11-04-detekt-runs-without-type-resolution`,
   follow-up `detekt-classpath`).
4. Run `./gw :shared:detekt` and read the finding count.
   - **0 findings** → only meaningful once step 3b passed; an inert rule also reports 0. Then the
     rule is live and enforcing.
   - **> 0** → either fix them, or accept them into the baseline (`just detekt-baseline`)
     and treat the rule as advisory until the tree is clean.

See `singularity-todo-detekt-rules-authoring` for the PSI-level pitfalls.

## Configuration

- **shared**: `shared/build.gradle.kts` + `config/detekt/detekt.yml`
- **desktopApp**: `desktopApp/build.gradle.kts` + shared detekt config
- **mcp-server**: `mcp-server/build.gradle.kts` + `mcp-server/detekt-baseline.xml`
- **androidApp**: `androidApp/build.gradle.kts` + `androidApp/detekt-baseline.xml`

Ktlint is a **standalone CLI** — its rules live in `.editorconfig` under the
`ktlint_*` keys, not in detekt's config. See ADR
`2026-11-02-ktlint-cli-replaces-the-detekt-formatting-layer.md`. Key settings:
- `ktlint_code_style = ktlint_official` — the code style, plus per-rule
  `ktlint_<rule-id> = disabled` opt-outs
- `.editorconfig` owns `max_line_length = 120`; detekt's own `MaxLineLength` is off
- detekt's `WildcardImport`, `UnusedImport` and `TrailingWhitespace` are off too — ktlint
  owns them (`standard:no-wildcard-imports`, `standard:no-unused-imports`,
  `standard:no-trailing-spaces`). `standard:no-unused-imports` is experimental, so
  `.editorconfig` enables it by name
- the aggregate `detekt` task runs with `--analysis-mode light`: it is registered without a
  classpath, so detekt drops every rule implementing `RequiresAnalysisApi` before reading a
  file. Those rules have never run — on main sources either, not only on tests. Confirmed
  with `javap` at the pinned 2.0.0-alpha.6, each declaring
  `implements dev.detekt.api.RequiresAnalysisApi`:
  `dev.detekt.rules.style.UnusedImport`, `…style.UnusedPrivateFunction`,
  `…style.UnusedPrivateProperty`, `…style.UnusedVariable`,
  `…potentialbugs.Deprecation`.
  Their PSI-only siblings `…style.UnusedParameter` and `…style.UnusedPrivateClass` do **not**
  implement it and do run, so "unused" in a rule's name tells you nothing — check the
  interface. The full list of inert rules and the one command that checks any name live in
  ADR `2026-11-04-detekt-runs-without-type-resolution`.

## See also

- `singularity-todo-quality-tools` — Full quality toolchain overview (detekt + kover + ktlint)
- `ADR 2026-10-09-detekt-auto-correct-inert-multi-module`

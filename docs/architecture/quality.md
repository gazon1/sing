# Quality rules

> **Reference, not a procedure.** This describes the thresholds and rules this
> repository actually enforces. It goes stale whenever the config changes, so a
> change to a threshold updates this file in the same commit.

## The thresholds this repository uses

Detekt's defaults are stricter than this code wants in some places and looser in
others. The config is `config/detekt/detekt.yml`; the reasons live in comments
*inside* that file, and this page is the index of them.

| Rule | Here | Detekt default | Why |
|---|---|---|---|
| `LongMethod` | 80 lines | 60 | The house shape of a ViewModel handler is a `when` over the intents, and those are longer than 60 lines. |
| `CyclomaticComplexMethod` | 20 | 14 | The same handlers. A method at 21 is a method to split — the ceiling is high enough to stop a mechanism from being split for the sake of a number. |
| `TooManyFunctions` | 25 per file, 16 per class, 13 per interface | 10/11/11 | A ViewModel has one handler per intent; the interface of a repository *is* its contract. The file limit is the one that catches accidental bloat. |
| `LongParameterList` | 8 (data classes ignored) | 6 | The canonical constructors are wide on purpose. |
| `NestedBlockDepth` | 5 | 4 | |
| `LargeClass` | 600 lines | 600 | |
| `ReturnCount` | 4 | 2 | |
| `max_line_length` | 120 | 120 | `.editorconfig` and detekt have to agree — otherwise `--format` and the gate fight each other. |

`config/detekt/baseline-*.xml` holds what is already known. **The baseline only ever
shrinks** — a new finding is a finding, not a line to add to the baseline.

## The rules that are not obvious

### ktlint: `BlankLineBetweenWhenConditions`

**If at least one branch of a `when` is multi-line, there must be a blank line
between *all* the branches. If every branch is single-line, there must be no blank
lines at all.**

This is not written down anywhere in the repository's own docs, and it is the rule
that gets re-learned by reading the ktlint bytecode. It applies to `when`
*expressions* (and `if`/`else` chains) whose branches are separated by blank lines:
the blank line is either a separator between all of them, or none of them.

So a `when` cannot have one multi-line branch standing out among single-line ones —
either all branches get a blank line before them, or the multi-line branch is broken
out of the `when` entirely.

`scripts/lint/normalize-when-blank-lines.py` applies that rule to a file: it reads
the branches, works out whether any of them is multi-line, and puts the blank lines
where the rule wants them. It is a helper, not a gate — the gate is detekt's.

### detekt: `ClassSignature`

The primary constructor of a `data class` has to fit on one line. `.editorconfig`
sets `ktlint_class_signature_rule_force_multiline_when_parameter_count_greater_or_equal_than`
to a value no real count reaches, so ktlint does not fight the rule.
ADR `2026-10-09-detekt-class-signature-gotcha`.

### detekt: `MaximumLineLength`

The `formatting` ruleset's line length is **off** in detekt — ktlint owns line length
through `.editorconfig` (120). Two owners of one rule is how a formatter and a gate
end up arguing.

## The rules this repository wrote for itself

`detekt-rules/` — 25 rules, each about a mistake that the compiler cannot catch and
that the code review kept missing. The ones that bite most often:

| Rule | What it forbids |
|---|---|
| `PassThroughUseCase` | A use case that only forwards to a repository. The use case must carry logic of its own. |
| `NoCoroutineLaunchInInit` | `launch` in a ViewModel's `init` — the collector has to be a property initializer, so it cannot observe a half-built ViewModel. |
| `NoRunCatchingInSuspend` | `runCatching` in a suspend function — it swallows `CancellationException`. |
| `NoViewModelScopeInProduction` | `viewModelScope` — a ViewModel takes an injected scope. |
| `NoRunBlocking` | `runBlocking` outside tests. |
| `NoRealDelayInTest` | `delay()` in a test — virtual time instead. |
| `NoStateIn` / `NoOpUpdateState` | Mutable state and no-op updates in a ViewModel. |
| `NoDroppedUiStateProperty` | A UI state property that is not a parameter of every variant. |
| `NoTextFieldStateInViewModel` / `NoValueBasedTextField` | A text field's state inside a ViewModel, and the value-based text field API. |
| `NoFactoryViewModel` | `factory { ViewModel(...) }` — a ViewModel is not a factory product. |
| `NoDirectClockSystem` / `NoDirectDispatchers` | `Clock.System` and `Dispatchers.IO` called directly — they are injected. |
| `NoUnreportedFailurePath` | A `Result` whose failure nobody reports. |
| `UserScopedRepository` | A repository that is not scoped to a user. |
| `AppErrorCode` | An error without a code. |
| `KDoc` rules | A public declaration without a KDoc that says what it is for. |

## What catches what, and when

| When | What runs | Scope |
|---|---|---|
| On commit | `.githooks/pre-commit` | `just lint-changed` over the changed files (seconds) + the compile of `:shared` and `:desktopApp` — in a worktree too, since 2026-10-23 (ADR `2026-10-23-the-commit-hook-compiles-in-a-worktree`). |
| On push | `.githooks/pre-push` | The structural gates + `detekt --auto-correct` on the pushable tree. |
| Locally, on demand | `./gw :shared:detekt` | The whole module. |
| Everywhere | `sgate run-all --summary` | Every gate in the registry — one line per failed gate. |

**`just lint-changed` is the fast loop.** It runs the same rules with the same
config and the same baseline as the module-wide task; only the input is narrower. A
mechanical violation is therefore visible in the file that was just written, instead
of minutes later in a module-wide run.

**The gates are the registry** (`scripts/src/sgate/commands/_registry.py`), not the
shell scripts: `scripts/ci/static-gates.sh` and `docs/scripts/gate-registry.md` are
generated from it.

## Where the findings are

`shared/build/reports/detekt/detekt.xml` — path, line, rule. The Gradle console
gives a counter, not the findings. When the report is long, summarise it: rule →
count → place. A dump of forty findings is forty lines of context; the answer is
almost always in the first twenty.

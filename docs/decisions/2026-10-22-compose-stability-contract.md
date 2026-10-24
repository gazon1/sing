---
title: "The stability contract is a file the compiler reads, and the leaves carry it"
date: 2026-10-22
status: accepted
tags: ["compose", "performance", "architecture", "tooling"]
---

# The stability contract is a file the compiler reads, and the leaves carry it

## Context

Kotlin 2.4.10 and Compose Multiplatform 1.12.0 compile this project with strong
skipping on by default. Strong skipping means the Compose compiler decides for
each composable parameter whether it may compare by `equals` (skip the
recomposition when the value is equal) or must compare by identity (`===`, skip
only when it is the same instance). The decision is made from the parameter's
*type*, and an interface — `List`, `Set`, `Map` — is a type the compiler cannot
reason about.

Two facts about this repository made that matter:

1. The Room mapper builds a **new instance** of every domain model on every
   read. `Task`, `Note` and `Project` each hold a `List` or a `Set`, so each of
   them landed in the *runtime-unstable* class: compared by identity. Every row
   of every list recomposed whenever its parent did.
2. Six screen-sized state classes carried `@Immutable`, which is a claim that
   the compiler compares them by `equals`. For a screen holding a month of
   calendar entries or a list of every project, that claim is expensive — and
   it was never measured.

Nobody had a way to tell which of this was true. `grep` cannot: it does not
distinguish `List` from `ImmutableList`, does not see parameters that arrive
inside lambdas, and knows nothing about slots. The Compose compiler can, and
writes a report — but nothing in the build asked for it, and the report is a
build artifact that only exists when a compilation actually ran.

The plan that produced this decision was reviewed before it was implemented, and
the review moved it in two ways that matter here:

- **The screen must not become stable.** The first formulation made the whole
  agenda state stable through persistent collections. That solves the wrong
  problem: a stable parameter is compared by `equals`, which over a screen's
  worth of collections is a deep walk. The correct boundary is the *leaf*: the
  row compares its item, the screen compares itself by identity.
- **The report is an instrument, not a gate.** A gate that reads build output
  is fragile by construction — the report exists only when the compilation ran,
  the jvm and android compilations write the same file names, and the directory
  is git-ignored. The repository already has `scripts/check-build-output-readers.py`
  because such readers break.

## Idea

Four layers, each leaning on the one below it, and none of them adding
infrastructure that already exists:

1. **Measure.** Ask the compiler for its stability report and read the answer
   instead of guessing. An opt-in property, not a gate.
2. **Declare.** A contract file the compiler reads, naming the types that are
   stable. One file, shared by every module that compiles composables.
3. **Stabilize the leaves.** Persistent collections in the leaf and section
   models; the false `@Immutable` annotations removed from the screen states.
4. **Protect.** Two detekt rules and one static gate, none of which reads
   build output.

## Decision

### The contract is a file, and it is read by the compiler

`config/compose/stability_config.conf` lists the types the Compose compiler
treats as stable. It is wired into `shared` and `desktopApp` through
`composeCompiler.stabilityConfigurationFiles` (the plural form; the singular
one is deprecated and fails the build with an error in this compiler version).

A type belongs in it when all three hold: every property is `val`; the
collections it holds are rebuilt on every read and never mutated afterwards;
and it is passed to composables, so the comparison buys something.

### The leaves carry the stability, the screen does not

`Task`, `Note`, `Project`, `Tag`, `Reminder`, `ChecklistItem`, `Attachment`,
`CalendarTaskUi` and the serializable `TaskDraft` are in the contract. The
leaf and section models — `TaskUi`, `RenderedSection`, `TaskDetailUi`, the
collection fields of `ProjectsUiState`, `ProjectDetailUiState`,
`CalendarUiState` and `AgendaUiState` — use `ImmutableList` / `ImmutableSet` /
`ImmutableMap`, which are known-stable constructs for the compiler.

The screen-sized states lost their `@Immutable`: an annotation that has to be
*true* is not a decoration, it is a promise about a type, and it was making the
compiler compare whole screens by `equals`.

### The value classes from the standard libraries are in the contract too

`LocalDate`, `LocalTime` and `Instant` are `@JvmInline value class`es over a
`Long`. They are immutable by construction and their `equals` is two integer
comparisons — but the compiler cannot see through a value class at compile time
and marks such a parameter *unstable*.

The measurement named them as the single largest group of unstable parameters,
and listing them is the cheapest change in the whole contract: three lines, no
source touched.

### The report is an instrument

`./gw :shared:compileKotlinJvm -PcomposeStabilityReport=true` writes
`shared/build/compose_compiler_reports/<target>/`. Nothing in CI reads it. The
compilation is forced to re-run when the property is set, because a report that
was not regenerated is read as a measurement of the current code and is not
one.

### The contract is proved by behaviour, not by configuration

`ComposeStabilityContractTest` (desktopApp, JVM) puts a `Task` into a state,
replaces it with an equal-but-new instance, and counts the recompositions of
the row that shows it. With the contract in place the count does not move.

The proof runs both ways, and both directions were executed: with `Task`
removed from the contract the same test fails — `expected: <1> but was: <2>` —
because the comparison falls back to identity and the row recomposes.

The test carries its own negative control: the same shape with a type the
contract does not name, where the row *does* recompose. Without it the first
test could pass for the wrong reason — a counter that never moves looks
exactly like a row that never recomposes.

The control type is a class with a **mutable property**, and that detail is
load-bearing. The first attempt used a class holding a raw `List` — the shape
`Task` has — and the row did *not* recompose: the comparison fell back to
value equality, and a control that behaves like the thing it controls proves
nothing. A type the compiler can see the body of and knows to be mutable is
the control that works.

### How the compiler actually decides, measured rather than assumed

The Compose compiler writes a stability marker into the class file of every
class it compiles — `Task.$stable` — and the call site passes that marker in
the parameter mask. The callee reads it: a marker that says *stable* makes it
compare the parameter with `equals`, a marker that says *not stable* makes it
compare with identity (`===`). The generated bytecode of this project shows
exactly that, and the three measured outcomes are:

| the parameter's type | comparison | an equal-but-new instance |
|---|---|---|
| named in the contract | `equals` | the row skips |
| not named, read from another module | `===` | the row recomposes |
| a class with a mutable property | `===` | the row recomposes |

The marker travels inside the compiled class, which is why the contract has to
be applied in the module that *declares* the type and not only in the modules
that read it — and why `scripts/check-compose-stability-config.py` checks both
`shared` and `desktopApp`.

### A rule that marks its own findings as suppressed is mute

The first version of `NoUnstableCollectionParam` reported every finding with
`suppressReasons = listOf("NoUnstableCollectionParam")` — a copy of a line
that read like a description of the rule. In detekt a non-empty
`suppressReasons` means *this finding is suppressed*, and detekt drops such a
finding before it reaches the report. The rule therefore reported nothing in
the build while its own unit test saw every finding: the test calls the rule
directly, the build goes through detekt's reporting layer.

Nothing was red. `:shared:detekt` said "0 number of total findings" and the
tree was green, and that green meant "the rule is mute", not "the code is
clean". The sites the rule was written to catch were sitting in the tree the
whole time.

Two lessons, both mechanical:

- a rule's finding must carry `suppressReasons = emptyList()`, and the rule
  test has to assert that — a test that counts findings cannot see a finding
  that the reporting layer would drop;
- **a rule that has never fired is indistinguishable from a rule that has
  nothing to say.** The proof that the rule works is a red run on a tree that
  contains the thing it forbids, not a green run.

### The contract is an input of the compilation

The Compose compiler plugin passes the contract as a *plugin option*, and
plugin options are not part of the compile task's up-to-date inputs. Left
alone, editing the contract leaves the compilation `UP-TO-DATE`: the new
classification never reaches the bytecode, and the change looks like it took
effect when nothing changed. Both modules therefore declare the file as an
input of their Kotlin compile tasks.

The same trap makes the stability report unreliable, which is why asking for
the report forces the compilation to re-run instead of trusting the inputs.

### Protection is in the sources

- `NoUnstableCollectionParam` (detekt): a `@Composable` function must not take
  a raw `List` / `Set` / `Map` / `Collection` parameter.
- `NoMutableCollectionCast` (detekt): no `as MutableList` / `as MutableSet` /
  `as MutableMap`. There are none in the repository today; the rule exists
  because the contract is now written down, and a rule that only describes
  last year's mistakes is a comment.
- `scripts/check-compose-stability-config.py`: the contract names types that
  exist, and the modules that compile composables actually read it. A name that
  resolves to nothing makes the compiler ignore it, and a contract no module
  reads is not a contract; neither failure has an error message attached to it
  anywhere else in the build.

### The remaining raw collection parameters are named, not hidden

`ComposeCollectionExemptions.kt` lists the composables that still take a raw
collection, with the parameter each one carries. It is a register of debt, not
an allowance: an entry disappears when the parameter becomes persistent, and
every *new* raw collection parameter is a finding.

The alternative was to convert all of them at once. Each conversion is a
signature change plus every call site, and each one changes what the compiler
compares: a stable parameter is compared by `equals`, which over a collection
is a deep walk. That is the right trade for a leaf — one task's tags — and the
wrong one for a screen that holds a month of entries. The conversions that were
worth making are made; the rest are named.

## Rationale

The measurement is what settled the design. Before the contract, the compiler
reported **1439 stable, 349 runtime-unstable and 59 unstable** parameters
across 712 composables. After the contract and the conversion of the hot
states, it reported **1522 stable, 322 runtime-unstable and 3 unstable**.

The 56 parameters that vanished from the unstable class were the date and time
value classes; the 27 that vanished from the runtime class were the eleven
types the contract names besides the value classes — `Task` (7), `Note` (7),
`Project` (3), `Tag` (2), `Attachment` (2) and one each of `Reminder`,
`ChecklistItem`, `CalendarTaskUi`, `TaskDraft`, `ProjectWithCounts` and
`SectionPrefill`. The domain models in list rows — the thing the whole
exercise was for — are now compared by `equals`, and a row whose task did not
change skips itself.

The remainder, 322 runtime-unstable parameters, is dominated by raw
collections: 82 parameters across 62 composables — 62 `List`, 11 `Set`, 8
`Map` and one `MutableList` — and the register that names them
(`ComposeCollectionExemptions.kt`) is counted from that list rather than
estimated. That is the next piece of work, and it is recorded rather than
hidden: the register names every parameter of every site, so the debt is
visible and every new raw collection parameter is a finding.

Two things the measurement taught that no amount of reading the source would
have:

- **A type argument is stable only if the type itself is declared stable.** The
  compiler honours an annotation or an entry in the contract for a type
  argument; it does not honour a stability it inferred. `ImmutableList<Project>`
  was stable while `ImmutableList<ProjectWithCounts>` was not — the second
  type is a two-field data class whose stability the compiler had worked out
  for itself. Listing it in the contract made it stable.
- **The leaf and the screen end up on opposite sides of the same decision.**
  `AgendaUiState.Loaded` holds its sections in a plain `List` and is compared
  by identity; `RenderedSection` holds its tasks in an `ImmutableList` and is
  compared by content. The screen's body re-runs cheaply, and the section
  inside it does not.

The reason the contract is a *file* rather than an annotation is that the
annotation was already there and was already wrong. `@Immutable` on a class
holding a `List` is a promise the compiler cannot check, and it was making the
comparison deep instead of making the type stable. A file the compiler reads
states the same thing in a form the compiler can honour.

## Consequences

- List rows for tasks, notes and projects stop recomposing when their item did
  not change. Every date parameter in the project stopped being compared by
  identity.
- A type may be listed in the contract only if it is genuinely immutable after
  construction. Violating that does not crash — the screen keeps showing old
  contents. `NoMutableCollectionCast` is the guard for the obvious way to
  violate it.
- `TaskCreateUiState.kt` no longer imports `androidx.compose.runtime`. The
  draft is stable because the contract says so, not because a presentation
  model claimed to be immutable. The `LAYER_ALLOWLIST` entry in
  `ArchitectureTest.kt` stays, and the reason recorded in
  `docs/decisions/2026-09-26-konsist-architecture-tests.md` is now the real
  one: presentation types live in presentation so that the domain does not
  depend on it.
- The remaining 82 raw collection parameters are a known debt with a known
  owner. Converting them is mechanical, but it is not free: each one changes
  a signature and every call site, and the plan that produced this decision put
  a full sweep over the composables outside its scope.
- The report is not a gate, so nothing in CI will notice if a future change
  makes the contract stale. The gate on the contract file, and the detekt rule
  on new raw collection parameters, are what stand in its place.

## Links

- `config/compose/stability_config.conf` — the contract itself.
- `docs/architecture/compose-stability.md` — what is stable, what is not, and
  how to write a new composable.
- `scripts/check-compose-stability-config.py` — the gate on the contract.
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoUnstableCollectionParamRule.kt`
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoMutableCollectionCastRule.kt`
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/ComposeCollectionExemptions.kt`
  — the register of the raw collection parameters that are still there.
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/ComposeStabilityContractTest.kt`
  — the behavioural proof.
- `docs/decisions/2026-09-26-konsist-architecture-tests.md` — the allowlist
  entry whose reason this decision changes.

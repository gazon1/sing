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
4. **Protect.** Three detekt rules and one static gate, none of which reads
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

### The date and time classes from the standard libraries are in the contract too

`LocalDate`, `LocalTime` and `Instant` are not value classes. They are
ordinary final classes: `kotlinx.datetime.LocalDate` and `LocalTime` wrap
`java.time.LocalDate` and `java.time.LocalTime`, and `kotlin.time.Instant`
wraps a `Long` of seconds plus an `Int` of nanoseconds. Checked with `javap`
against the jars rather than assumed.

They are immutable by construction and their `equals` is two field
comparisons — but the compiler infers stability from what it can see of a
type, and a class from another module that carries no stability marker of its
own is runtime-unstable to it. So a parameter of such a type is compared by
identity.

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
  a raw `List` / `Set` / `Map` / `Collection` parameter, and a registered
  parameter must still declare the type the register recorded.
- `NoMutableCollectionCast` (detekt): no `as MutableList` / `as MutableSet` /
  `as MutableMap`. There are none in the repository today; the rule exists
  because the contract is now written down, and a rule that only describes
  last year's mistakes is a comment.
- `NoFalseImmutable` (detekt): a class or an object marked `@Immutable` must
  not declare a `var` or a raw collection. The promise is one the compiler
  cannot check, so the rule checks it.
- `scripts/check-compose-stability-config.py`: the contract names types that
  exist, and the modules that compile composables actually read it. A name that
  resolves to nothing makes the compiler ignore it, and a contract no module
  reads is not a contract; neither failure has an error message attached to it
  anywhere else in the build. The same gate reads the register of debt against
  the repository: a registered site that no source declares, and a registered
  parameter that no declaration of that site has. And it checks the contract
  itself against the declarations it names: a type the contract vouches for
  must not declare a `var`, and must not hold a collection that can change
  after construction. The contract is a promise the compiler cannot check, so
  something has to check it — the same way `@Immutable` needed a rule.
- `config/compose/stability_debt_baseline.conf`: the recorded size of the
  register of debt. The gate fails when the register and the record disagree
  in either direction, so the number everyone quotes cannot drift away from
  the register it describes.

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

### The exemption is keyed by the parameter, and the register is read from both sides

The register was keyed by the composable alone: `isKnown(fqn)` exempted the
whole function. Two things followed from that, and both were wrong.

A **new** raw collection parameter on a known composable was invisible. The
exemption said "this composable is known", and the register's own listing of
parameters was decoration — nothing read it. The register said
"every parameter, not the first one", and the rule could not tell whether the
listing was complete.

An entry could also **rot**. The register only grew: a parameter that was
renamed, removed or retyped left an entry that names something the code no
longer has, and the debt it was accounting for had been paid without anybody
deciding to pay it.

The key is now the pair (parameter, type) under a qualified name, and the
comparison runs in both directions:

- a raw collection parameter the register does not name is a finding, even at a
  known site;
- a registered parameter whose type the code no longer declares is **stale** —
  and the comparison runs for every registered parameter, raw or not, because a
  parameter that became persistent is exactly how the register shrinks. A check
  that only looked at the parameters that are *still* raw would never notice
  that the debt was paid.

Switching the two-sided check on produced **20 findings on the first run**:

- **14** register entries whose recorded type no longer matched the code. The
  register said `List<LocalDate>?` where the source said `List<LocalDate>` — a
  nullable marker that was never in the code, written by hand into a register
  that was supposed to be measured;
- **5** that were an artefact of the first implementation, which looked at one
  overload at a time;
- **1** that was a naming bug, and the more interesting of the two kinds of
  finding.

### A composable has overloads, and they share the qualified name

`Nav3State.toDecoratedEntries` is declared twice, and only one of the two takes
the raw collection. A check that looked at a single overload reported the other
one's parameters as missing — five false findings on one class.

The first fix was to collect the parameters of every overload before judging:
the rule gathered them per file and compared the register against the result in
`postVisit`. That fixed the case in the tree and left a trap: Kotlin allows
top-level overloads of the same name in **different files** of one package, and
the rule sees one file at a time. The moment a registered composable got such
an overload, the build would fail on a correct tree.

So the rule stopped judging that question at all. "This parameter is missing
here" is not the same as "this parameter is gone", and the rule cannot tell the
difference. The gate can: it reads the repository.

### The rule judges the code that is there, the gate judges the register

The division is now explicit, and it is a division of *capability*, not of
effort:

- the **rule** compares the code that exists with the register: a raw
  collection parameter the register does not name, and a registered parameter
  whose declared type no longer matches what the register recorded;
- the **gate** compares the register with the repository: a registered site
  that no source declares any more, and a registered parameter that no
  declaration of that site has.

Neither of them can do the other's job. The rule sees the exact types and the
exact parameters; the gate sees the whole tree.

### The qualified name is the one the compiler prints — and it was not

The rule computed the qualified name with the function **before** the class it
is nested in: `…feature.nav.toDecoratedEntries.Nav3State`. The Compose
compiler's stability report prints it the other way round:
`…feature.nav.Nav3State.toDecoratedEntries`.

The register had been written to match the rule, so the two agreed with each
other and disagreed with the report the register's own documentation claims it
comes from. Nobody noticed, because nothing compared them — a person copying a
name out of the compiler report would have written an entry that never matched
anything, and the site it names would have been reported as a new debt.

The rule now computes the name the compiler prints: the outermost class first,
the function last. The two nested entries in the register were corrected.

### The register is checked from the gate, because the rule cannot see what is gone

A rule visits the code that exists. A composable that was **deleted** leaves
its entry behind, and no rule will ever see it — the entry exempts nothing,
and the debt it was accounting for has vanished from the count without anybody
deciding to pay it.

That case belongs to the gate (`scripts/check-compose-stability-config.py`),
which reads the sources rather than the code that survived. It resolves every
registered site to a declaration and fails when there is none, and it resolves
every registered parameter to a declaration of that site and fails when there
is none.

The first version of that check searched the whole tree for the name, as a
fallback for a source root that does not lay its files out by package path.
The fallback **masked the deletion it was written for**: `ListPickerSheet` is
declared in two packages in this repository, so deleting the registered one
still left a declaration of the same name — in the wrong package — and the
gate passed. A check that cannot tell a deletion from a namesake is not a
check.

The fallback is gone. The site is resolved inside its own package, and the
gate says so when it is not there. A source root that does not follow the
package layout is a thing to fix, not to paper over: the gate would have
reported sixty false positives, and a gate that reports sixty false positives
gets switched off.

A qualified name quoted in the register's own prose is not a site either. The
reader of the register is a program now, and a program that reads prose as
data learns to complain about prose.

Two more ways the same check could pass a tree it should have failed, both
found by review rather than by a test failing:

- **A namesake in a sub-package.** The package *directory* holds the
  sub-packages as well, so a search that reads the directory and never looks at
  the package a file declares finds a namesake in `…presentation.sub` and
  calls the deleted site alive. The package declaration is what says which
  package a name belongs to, and it is now what the gate reads.
- **A composable whose last parameter was deleted.** "The declaration declares
  no parameters" and "no declaration was found" are different facts, and the
  check treated them as one: the site was skipped, and a register entry naming
  a parameter the code no longer has survived. The debt was paid by removing
  the parameter, and a check that cannot see the payment will keep counting it.

A third one is a bug in the gate itself rather than in its logic: the package
of a file was read by anchoring the pattern to the **start of the file**, so a
file with a licence header or a file-level annotation in front of its package
line was read as having no package at all — and every site in it was reported
as gone. Nine sites were reported as deleted by that alone.

### A false `@Immutable` is a rule away from coming back

`@Immutable` is a promise the compiler cannot check: the instance never
changes, so the compiler may compare it by `equals` and skip the
recomposition. The compiler believes the promise and never looks at the
fields.

Six screen states carried a false `@Immutable` before this decision, and the
annotation came back while the question was open. A rule is cheaper than a
habit — it is checked on every build, and it does not need somebody to
remember — so `NoFalseImmutable` (detekt) now reports a class marked
`@Immutable` that declares a `var` or a raw collection.

It found two on its first run, with four properties between them:
`CurrentProjectContent` (three raw collections) and `ProjectCloseConfirmation`
(one raw list). Both annotations were removed, with the reason written down at
the class.

A third one was found by reading rather than by the rule: `SettingsUiState`,
a sealed interface marked `@Immutable` whose implementation carries
`EphemeralState.Ai` — and that carries a raw `List<String>` of model names. The
rule cannot see it, and saying so is part of the rule's contract: a promise
broken **through** a contained type needs resolution, and the rule reads
declared types, not resolved ones.

The annotation was removed there too, and the reason is the same trade the
rest of this decision makes: what `@Immutable` bought was a *deep* comparison
of the whole settings state — every model name, every saved view — and a
screen state is cheap to re-run and expensive to compare. Making the promise
true instead means persistent collections inside `EphemeralState`, and that is
the same debt the register of raw collection parameters names.

`@Stable` is deliberately **not** covered. `@Stable` says the instance may
change and Compose is not told about it, and a `@Stable` class with
`private var by mutableStateOf(...)` is the ordinary pattern in this
repository: the state is observable through the snapshot system, and the
annotation is about the *reference* being stable, not about the fields.

The two rules answer the same question — is this type a raw collection? — so
they share one list of names (`RawCollectionTypes`), because two lists that
drift apart are a rule that contradicts the other. The list covers the mutable
spellings (`MutableList`, `ArrayList`, `HashSet`, `HashMap`) and `Array` as
well as the interfaces; `Iterable` and `Sequence` are deliberately out, because
they are rare as a parameter of a composable and a rule that fires rarely is a
rule people turn off. Their absence is a statement about how often the rule
reports, not about whether they are stable: they are not.

### The contract is checked against the declarations it vouches for

The rule above protects `@Immutable` — a promise written in the code. The
contract is the same promise written in a file, and it was the one nobody
checked. The compiler reads a name from that file and believes it: every
instance of the type is compared by `equals` and may be skipped. A type that
declares a `var` has a second version of its value, and `equals` compares the
wrong one; a type that holds a `MutableList` has a collection that can change
after construction, and `equals` of a collection that changed is a lie about
"the same content".

The gate now reads the declaration of every type the contract names from this
project's namespace and rejects those two. What it cannot see is the rest of the
promise: the contract also says that the collections a type holds are rebuilt on
every read and never mutated afterwards, and *that* is a statement about every
write in the program — a question about data flow, not about declarations. A
read-only `List` is allowed, because `Task` holds a `List<TagId>` on purpose; the
mutable spellings are what a declaration can show, and those are rejected.

A library type is the one case the check does not reach: its declarations are
not in these sources. `kotlinx.datetime.LocalDate` and the two others are
listed on the strength of the library's own immutability, and the gate checks
only that the codebase imports them.

### The kinds are told apart by what the compiler does with them

An entry in the contract is a name, and the names are not all the same thing.
The gate used to treat "not a class" as one failure for all of them. That was
wrong in one direction and imprecise in the other: it refused entries that
work, and it explained every refusal with a reason that fitted none of them.

Measured on 2026-10-24, by adding an entry, rebuilding and reading the
compiler's own report:

- **A class** is what the stability file lists. An entry for it is the
  contract doing its job.
- **An interface** is honoured. The compiler matches the entry against the
  supertypes of a class as well, so an entry for `UiNode` made
  `UiNode.Checkbox` and `UiNode.TextField` stable, and the `$stable` marker
  appeared in their class files. The promise the contract makes is about the
  *instances* of the interface: an instance may be any of the implementations,
  and the compiler compares it by `equals`. So the promise covers the
  implementations, and the gate checks them: it finds them by their supertypes
  — a name in the same package, or an imported one — and holds them to the
  same promise.
- **An object** the compiler judges stable on its own — an object is a
  singleton, so there is nothing to compare, and the stability report shows
  the objects of this repository as `stable class` with no entry for them at
  all. An entry for one says nothing that was not already said, and it makes
  the reader believe the object needs vouching for. The gate reports it.
- **A typealias** is not a class, and the compiler matches class names, so an
  entry for one resolves to nothing. The gate reports it.

The first two are what the contract is for; the last two are entries that say
less than they appear to, which is the failure this whole decision is about.

### The leaves are declared, and the cascade starts at the root

A type the compiler cannot judge needs the contract. It cannot judge an
interface — always runtime-unstable, whatever it holds — and it cannot judge a
class that holds a plain `List`, because the list can change after
construction. The contract grew by ten such types on 2026-10-24, and they come
in three shapes.

**The roots.** `ProposalItemStatus` and `ProposalItemKind` are sealed
interfaces, `SpotlightShape` is one too, and `LogbookEntry` is a sealed
interface over a note or a time tracking row. The compiler judges an interface
by nothing at all, so every class that holds one is unstable with it. The
entry reaches the implementations, and the class above becomes stable by
itself: `ProposalItem` holds two such interfaces, and it needed both roots.

**The leaves that hold a plain `List`.** `AiProposal` holds its changes in one;
`MenuNode.SubMenu` holds its children in one. Both are rebuilt on every read
and never mutated afterwards — the promise `Task` makes with its
`List<TagId>`, and the reason `Task` is in the contract.

**The parameter types themselves.** `AiTestResult` and `SelectorTemplate` are
sealed interfaces; `ThemeMode` and `LocalDataDisposition` are sealed classes.
The compiler judges none of them, and each is the type of a parameter.

Measured on 2026-10-24, on the shared module and its 526 composables: the
report of the compiler went from **1561 stable / 346 runtime-unstable / 3
unstable** to **1583 / 324 / 3**. Twenty-two parameters moved from
runtime-unstable to known-stable:

- `node: MenuNode` ×2, `node: Action` ×3 and `node: SubMenu` ×3 — the menu of
  the desktop shell;
- `proposal: AiProposal` ×1 — a proposed change;
- `entries: ImmutableList<LogbookEntry>` ×1 — the logbook of a task;
- `aiTestResult: AiTestResult` ×2 and `result: AiTestResult` ×1 — the probe of
  the AI provider settings;
- `template: SelectorTemplate` ×2 and `pendingTemplate: SelectorTemplate?` ×1
  — the agenda selector;
- `themeMode: ThemeMode` ×1, `themeMode: ThemeMode?` ×1 and `selected:
  ThemeMode` ×1 — the theme;
- `disposition: LocalDataDisposition?` ×2 and `selected: LocalDataDisposition?`
  ×1 — what happens to the local data when an account is claimed.

The three roots did not move a parameter of their own, and that is expected:
they are not parameter types. What they changed is above them — the properties
of `ProposalItem` and of `SpotlightStep` are compile-time stable now, where
they were runtime-unstable before, and the class above them is compared by
`equals` instead of by identity.

### The register of debt is a ratchet

The register names the composables that still take a raw collection, and under
each of them the parameters that still carry one. The number of those
parameters is the debt: it is the number every conversation about this work
quotes, and a number nobody writes down is a number nobody reads.

So the size is written down — in `config/compose/stability_debt_baseline.conf`
— and the gate compares it with the register. It fails when they disagree in
**either** direction: when the register grew, because the debt may not grow
without somebody deciding to raise the bar; and when the register shrank,
because a recorded size that stays above the real one is a number the next
reader believes and that is wrong.

Raising the bar is allowed. It is a decision somebody has to make on purpose,
in the same commit that moves the debt, and the diff then shows the movement
of the debt and the decision together.

### The identifiers did not need an entry, and the measurement says so

`TaskId`, `NoteId`, `TagId`, `TagGroupId` and `ProposalItemId` are
`@JvmInline value class`es over a `String`. A value class is inlined into its
use, so the compiler sees through it to the type underneath — here a `String`,
which is stable. That is the same shape as a `LocalDate` parameter only
superficially: `LocalDate` is not a value class at all, it is a final class
from another module, and the compiler cannot see inside it.

The plan was to list the identifiers — five lines, the cheapest change in the
whole contract — and to unblock the ten raw collection parameters whose
element is an identifier.

**The measurement said no.** The report was taken twice, with the five lines
and without them: **1535 stable, 372 runtime-unstable and 4 unstable across
728 composables**, both times. The identifiers are already stable, and adding
them to the contract changed nothing.

They are stable because the compiler *does* see through these value classes:
`TaskId` wraps a `String`, and a `String` is stable, so the verdict the
compiler records for `TaskId` is a stable one — and a recorded verdict is
honoured for a type argument, exactly as the contract entry would have been.
`ProjectWithCounts` needed an entry for the opposite reason: the compiler
judged it *unstable* and stuck to that.

So the five lines were not added. The ten parameters whose element is an
identifier unlock by the conversion itself, with nothing in the contract
changed — which is worth knowing, because the plan said otherwise and the
plan was what would have been carried out.

What the compiler sees through and what it does not is not something to guess
at: the identifiers are seen through, the date and time classes were not, and
the two differ in kind rather than in degree — a value class inlines, a final
class from another module does not. The report is what settles it, and it
settles it per type.

## Rationale

The measurement is what settled the design. Before the contract, the compiler
reported **1439 stable, 349 runtime-unstable and 59 unstable** parameters
across 712 composables. After the contract and the conversion of the hot
states, it reported **1522 stable, 322 runtime-unstable and 3 unstable**.

The 56 parameters that vanished from the unstable class were the date and time
classes; the 27 that vanished from the runtime class were the eleven
types the contract names besides them — `Task` (7), `Note` (7),
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

Those numbers are a measurement of a tree that keeps moving, so they are
dated rather than eternal. Re-measured on 2026-10-25, after this decision's
own second half (the false `@Immutable` removed, the register keyed by the
parameter), the same report reads **1535 stable, 372 runtime-unstable and 4
unstable** across 728 composables. Most of the movement is other people's
composables; the rest is ours — `CurrentProjectContent` and the settings state
lost their false `@Immutable` and moved from the stable class into the runtime
one, which is exactly what those annotations were hiding. Seven parameters
moved, because the settings state is read by seven composables.

The 82 raw collection parameters did **not** move: the fresh report counts the
runtime class by type and finds the same 62 `List`, 11 `Set`, 8 `Map` and one
`MutableList` — which is the register, counted from the report rather than
estimated.

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
  makes the contract stale. The gate on the contract file, the gate on the
  register of debt, and the detekt rules on new raw collection parameters and
  on a false `@Immutable` are what stand in its place.

## Links

- `config/compose/stability_config.conf` — the contract itself.
- `docs/architecture/compose-stability.md` — what is stable, what is not, and
  how to write a new composable.
- `scripts/check-compose-stability-config.py` — the gate on the contract.
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoUnstableCollectionParamRule.kt`
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoMutableCollectionCastRule.kt`
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/ComposeCollectionExemptions.kt`
  — the register of the raw collection parameters that are still there, keyed by
  the parameter.
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoUnstableCollectionParamRule.kt`
  — the rule that reads the register in both directions.
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoFalseImmutableRule.kt`
  — the rule that keeps a false `@Immutable` from coming back.
- `scripts/check-compose-stability-config.py` — the gate that finds the entries
  whose composable no longer exists.
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/ComposeStabilityContractTest.kt`
  — the behavioural proof.
- `docs/decisions/2026-09-26-konsist-architecture-tests.md` — the allowlist
  entry whose reason this decision changes.

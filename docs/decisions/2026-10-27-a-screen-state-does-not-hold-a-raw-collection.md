---
title: "A screen state does not hold a raw collection"
date: 2026-10-27
status: accepted
tags: ["compose", "performance", "architecture", "tooling"]
---

# A screen state does not hold a raw collection

## Context

The Compose stability work has three rules and two registers, and they all ask
the same question from one side: **what does a composable receive?**

- `NoUnstableCollectionParam` — the parameter of a `@Composable` may not be a
  raw `List` / `Set` / `Map`;
- `NoMutableCollectionCast` — no `as MutableList`;
- `NoFalseImmutable` — no `@Immutable` on a class that declares a `var` or a
  raw collection;
- `ComposeCollectionExemptions.kt` — the register of the composables that
  still take one (empty: the debt is paid);
- `config/compose/stability_config.conf` — the contract of the types the
  compiler is told are stable.

Every one of them looks at the **parameter** of a composable, or at the
**type** a contract names. None of them looks at what a state *holds*.

That is a hole, and it is not a small one. A screen that receives
`ArchiveUiState` passes `NoUnstableCollectionParam`: the parameter is a
state, not a collection. But the state itself carries `List<Task>`, and the
promise the state makes about its own contents is not enforceable — `List` is
an interface, so the state cannot promise that the instance it holds will not
change, and the value was built on the wrong side of the boundary: the screen
that renders it is where the collection was assembled.

The rule that does look at the composable's own body —
`NoCollectionMaterializationInComposable` (added 2026-10-26) — catches the
materialization, but it cannot see a collection that was built *earlier* and
handed to the state.

## Idea

Ask the other half of the question, in the place where the answer lives: **the
state that owns the value holds the persistent kind**.

A screen state is not a leaf. The rule of this repository is that the leaf is
stable and the screen is not, and the screen state is deliberately compared by
identity. That is the right trade for the state *object*. It is not a licence
for the state to carry a raw collection: the collection inside it is handed to
rows and sections, and those are the leaves whose comparison the whole
contract is about.

## Decision

A new gate, `scripts/check-screen-state-collections.py`, and a new register,
`config/compose/screen_state_collection_debt.conf`.

**What counts as a screen state.** A declaration is a screen state when it is
a state type of a feature:

- its name ends with `UiState` or `State` — the sealed hierarchy a screen
  reads (`AgendaUiState`, `TagsUiState`, `TaskChildrenState`); or
- it is declared inside such a type — the members of the hierarchy
  (`ArchiveUiState.Content`, `TagGroupsUiState.Content`).

The states of the core (`SyncState`, `EphemeralState`) are out of scope: they
are the state of a repository and of a settings bundle, not of a screen that
renders them. The scope is written down rather than implied, because a rule
whose scope can be argued is a rule that gets argued with.

**What the gate reports.** A raw collection a screen state holds. The
persistent kind (`ImmutableList` / `ImmutableSet` / `ImmutableMap`) is the
only collection a state may hold.

**The register and the ratchet.** The register names the debts — one entry per
property — and records its own size. The debt may not grow without somebody
deciding to raise the bar, and a shrink has to be recorded. The gate reads the
register from both sides: a raw collection the register does not name is a new
debt, and a registered entry the code no longer has is a debt that was paid
and whose record did not move with it.

**Why a Python gate and not a detekt rule.** The detekt rules in this
repository read the source of a declaration they visit. A screen state is
not visited by any rule — it is a *type*, and a type is a parameter of a
composable or a member of a sealed hierarchy. Deciding that needs the whole
tree, and a rule that needs the whole tree is a gate.

## Rationale

The alternative — extending `NoUnstableCollectionParam` to look inside the
state — cannot work: the rule has no type resolution, and the state it would
have to look inside is usually a *different* file from the composable that
receives it.

The alternative to the register — no register at all, and convert everything
at once — is the one this repository already refused once, for the register of
raw collection parameters. Each conversion is a signature change plus every
call site, and each one changes what the compiler compares. The debt is named
first and paid afterwards, one area at a time, with a measurement after each
payment. That is the rhythm this work already has, and the register is what
makes the number everyone quotes mean something.

The recorded size is in the same file as the entries, and not in a second
file, because one gate reads both and they move together: a diff that showed
the entries changing and the number not changing would be a diff nobody
reads.

## Consequences

- The gate is blocking and runs in the static gates.
- The debt it names is **27 properties** in ten feature areas: agenda, ai,
  archive, attachments, backup, calendarSync, nav, tags, tasks, timetracking.
- The waves that pay it are separate pull requests, one area at a time, each
  with its own measurement and its own lowering of the recorded size.
- A new raw collection in a screen state is a finding from the day the gate
  exists; the register is the only way to add one, and the number in it is
  what makes that a decision rather than an accident.

## Links

- `docs/architecture/compose-stability.md` — the contract, the three rules and
  the register of raw collection parameters.
- `docs/decisions/2026-10-22-compose-stability-contract.md` — why the
  contract is a file.
- `scripts/check-compose-stability-config.py` — the companion gate.

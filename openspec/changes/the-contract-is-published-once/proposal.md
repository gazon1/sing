# the-contract-is-published-once

Issue: — · Related: `docs/decisions/2026-10-21-the-contract-is-published-once.md`

Status: **accepted**
Tags: **text field contract registry documentation**

## What

The text-field contract is published **once**: in the tables of the
`singularity-todo-text-field-states` skill. The arch tests read those tables instead of
holding their own copy.

Today the contract is written twice — once where a developer reads it (the skill's
inventory and rewrite tables) and once inside the test that checks it (a 35-row map in
the shape convention test, a 15-row list in the rewrite contract test). The two copies
are compared, which catches a divergence, but only the copy in the test is checked
against the code and only the copy in the skill is read.

## Why

The duplication was not a design choice — it was a symptom. The test needed the file
that **builds the source**, the skill's rewrite table had no such column, so the test
kept its own copy of the table. The gap is the reason the two copies existed; adding the
column makes the second copy unnecessary.

A registry that is written twice is a registry that can disagree with itself, and the
copy that rots is the one nobody looks at: a stale row in the test's copy is a contract
for a file that has not been bridged for a year.

## The shape of the fix

- The rewrite table gains an **Owner** column — the file that builds the source. The one
  row whose owner is the field site itself is the GenUI renderer, whose owner is a
  server; the row says so by naming the site as its own owner.
- `TextFieldShapeConventionTest` holds no registry: its classification *is* the skill's
  inventory table.
- `TextFieldRewriteContractTest` holds no registry: its rows *are* the skill's rewrite
  table.
- A new check comes with it: a row naming a file that no longer owns a bridged field
  fails, like a row naming a file that no longer owns any field.
- The tables are found by their **header**, so a row of another table is never read as a
  row of these, and a table whose header changed fails loudly instead of quietly reading
  nothing.

## What not to do

- Do not bring the registries back into the tests. A second copy is what this change
  removes.
- Do not read "the first table that looks right". Read the table by its header.
- Do not make the Owner column optional: a row with no owner is a contract with nobody,
  and the "the owner declares a source of that kind" check must fail on it.

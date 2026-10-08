# Design — androidapp-debug-lint-policy

## The decision

Lint `src/debug` with the production rule set and a baseline carrying four
named, intentional suppressions. Fix the twelve formatting findings rather than
baselining them.

## Why not the alternatives

**Exempt by source set** is the cheapest and is defensible — `src/debug` is
developer tooling, not shipped code. It is rejected because the exemption is
unbounded in both directions: nothing enforces a *style* in `src/debug` either,
so a debug activity that never compiles, or that drifts from the production APIs
it exercises, produces no signal at all. The finding count that motivates this
work would have to be rediscovered by hand every time a debug file is added.

**Baseline all 16** is rejected because it collapses two different claims into
one. Four suppressions say "this rule is wrong for this kind of code"; twelve
say "this code has not been written yet". After the merge both read as
`ID: Suppress` in the same file with the same weight, and the next reviewer has
no way to tell which is which.

**A narrower rule set for `src/debug`** is the most precise option and is
deferred. It needs a rule-by-rule judgement about what "wrong for tooling" means
for every rule that fires, and this repository has four data points. Adopting it
at four data points means the policy is derived from the cases that happen to
exist, which is how a source set ends up governed by whatever was written first.
Policy 2 produces exactly the same four suppressions and grows into policy 3
when the count justifies it.

## What the suppressions have to say

Each of the four needs a reason that survives the next person, and each reason
is about the *tool*, not about this file:

- `NoRunBlocking` — a debug seeder runs once, at launch, and must finish before
  the app is usable. There is no UI to show progress on.
- `NoDirectClockSystem` (×4) — seed data is dated by real time so the developer
  sees items that look current. Injecting a clock here would make the seed data
  stale, which defeats it.
- `TooGenericExceptionCaught` — the seeder must not take the app down with it.
  A partial seed is more useful than no seed and a crash.

A suppression whose reason is "this is debug code" is not a suppression; it is
the exemption with extra steps, and it will be copied into production files
where it is not true.

## Blast radius

`androidApp` is the only module in this repository with a `src/debug` source
set today. The policy is written as a rule about debug source sets rather than
about this module, so the next module with one inherits a decision instead of
reopening it. If a second module grows a `src/debug` tree, the same three
policies apply and the same reasoning decides it.

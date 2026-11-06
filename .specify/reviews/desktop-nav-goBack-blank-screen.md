---
change: desktop-nav-goBack-blank-screen
verdict: pass
date: 2026-11-06
pr: 2608
reviewed: 901c43b8b79275c8f0087b9e604dfe960aac9846
documents: sha256:577e0d0fcd6e08a9b23b3aaf429eb78dad438015f5d7948422f9a108275d888f
---

## What was reviewed

The contract of the change `desktop-nav-goBack-blank-screen` — its proposal and
its spec delta `specs/desktop-navigation/spec.md` — as the digest above records
them.

## Why this verdict is re-issued

The previous verdict carried the digest `sha256:a94cf336…` of the contract as it
stood before PR #2608 (`901c43b8b`, "chore(tooling): add rumdl markdown
checks") touched it. The contract moved, and a contract that moves is
re-issued, not re-read.

The move was made by the markdown linter, not by the change. PR #2608 added an
empty line after every `#### Scenario:` heading — four lines in
`specs/desktop-navigation/spec.md` — and fixed one heading in the proposal: the
line `#45 records a proposed red-border debug overlay` became `# 45 records a
proposed red-border debug overlay`, because a line that starts with a hash and
a digit is read as a heading. No requirement was added, removed or reworded,
and the sentence about the debug overlay says the same thing as it said before.
The claim is checkable:

    git diff 901c43b8b^ 901c43b8b -- openspec/changes/desktop-nav-goBack-blank-screen

is five added blank lines, one reworded heading marker, and nothing else.

## What the review found

Nothing that needed fixing. The requirement the contract states — that a back
navigation which leaves the navigation tree empty is diagnosed rather than
shown as a blank screen — is unchanged by the move, and the scenarios that
spell it out are the same scenarios in the same order.

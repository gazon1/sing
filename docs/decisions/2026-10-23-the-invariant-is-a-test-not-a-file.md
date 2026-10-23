---
title: "The invariant is a test, not a file"
date: 2026-10-23
status: accepted
tags: [gates, ai, write-path, testing]
---

# The invariant is a test, not a file

## Context

For a while the repository kept a list of the tools that write the user's data
directly: `config/docs/ai-direct-writes.txt`. It had one entry —
`CreateTagTool` — with the reason it wrote. A gate,
`scripts/check-ai-write-tools.py`, read the list and compared it with the
source: a tool that writes and is not on the list fails the gate, and a tool on
the list that no longer writes fails it too.

The idea was sound: a rule that is written down and not applied is a rule that
is true in the document and false in the product. But the shape was wrong.

**The list was a promise about the code.** It said which tools write, and the
gate checked that the code matched the promise. When `create_tag` was moved onto
the proposal path, the list had to be edited in the same commit — and if it was
not, the gate would fail for a reason that had nothing to do with the change
(the entry was stale), or pass for a reason that had nothing to do with the
code (the list was updated and the tool still wrote).

**The list could only ever be as good as the last edit.** Nothing connected
"this tool writes" to the code except the gate, and the gate could only say
that the *file* was right. It could not say that the behaviour was.

## Idea

If the invariant is about what the code does, check what the code does.

## Decision

**The file registry and its gate are gone. The invariant is a test.**

`WriteToolsOnlyProposeTest` checks the invariant two ways:

- **By behaviour.** Every write tool is called against fakes, and the test
  asserts that the user's data is unchanged and that a proposal was stored.
- **By source.** The test scans the tools' own source and asserts that none of
  them reaches a write repository — the proposal repository is the only
  exception, and the exception is named in the test rather than in a list
  somewhere else.

The check is now a statement about the code, and a new write tool fails it
without anybody having to remember to update a file.

## Rationale

A list of exceptions is a second thing to keep in step with the code, and the
thing that has to be kept in step is the thing that changes most often. When
the exception list has one entry left, the list is not earning its keep — it is
a place where a decision is recorded, and the decision it recorded ("tags
cannot be proposed, the vocabulary has no tag kinds") is no longer true.

The behaviour check is the one that matters: it asks the tool to do the thing
and looks at the result. The source scan is the belt to that pair of braces —
it catches a write that the behaviour check could not reach, and it says which
repository is allowed to be reached.

## Consequences

- **One fewer file, one fewer gate, one fewer thing to forget.** The registry
  of gates is shorter, and the invariant is still enforced — by something that
  measures rather than by something that compares two documents.
- **The surface contract version moved to 3.** `create_tag`'s answer changed
  shape: the `tagId` field is gone — it named a tag that did not exist yet —
  and `proposalCreated` and `proposalId` are there instead. A removed field is
  observable to a client, so the version says so.
- **The test is a better place for the decision.** When the next write tool
  appears, the person adding it reads the test, sees what the tools are
  allowed to do, and writes the tool accordingly.

## Links

- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/tools/WriteToolsOnlyProposeTest.kt`
  — the invariant.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/McpToolCatalog.kt`
  — the contract version and what it promises.
- `docs/decisions/2026-10-22-the-write-path-has-one-shape.md` — the decision
  that put eight tools on the proposal path and left `create_tag` out.

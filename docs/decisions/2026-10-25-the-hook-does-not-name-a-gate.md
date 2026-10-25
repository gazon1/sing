---
title: "The hook does not name a gate: the gates it runs are declared once"
date: 2026-10-25
status: accepted
tags: [gates, tooling, pre-push]
---

## Context

The pre-push hook ran nine gates. Each of them was a block of bash: the
script, its arguments, the condition under which the gate runs, and the
message the hook prints when it fails. The blocks were in the hook, and
nowhere else — so the hook was the only place a gate existed.

The registry already declared the list of scripts the hook runs
(`PRE_PUSH_SCRIPTS`), and the test fixture that drives the hook through a real
push already read that list to know which scripts to stub. The gate that
compares the hook with the registry (`scripts/check-hook-gates.py`) already
checked that the two agree in both directions. So the machinery for "one
declaration" was in place, and the hook was not using it: the blocks were a
third copy of a thing two other copies already agreed on.

The cost of the third copy was not that the copies disagreed — the gate
caught that. The cost was that adding a gate meant writing code, in a bash
script, in the middle of a thousand lines of bash. A gate that is cheap to
declare and expensive to declare *in the hook* is a gate that stays undeclared
until someone is willing to write the block.

The hook also read the tree rules from the registry already — the three rules
that are cheap, that CI enforces, and that a contributor would otherwise meet
only as a red CI run. That loop was the shape the answer took, and it was the
only part of the hook that used it.

## Idea

A gate is a declaration: a script, its arguments, the condition under which it
runs, and what it says when it fails. None of that needs a language to be
written in. The hook should read the gates from the declaration, and the
declaration should be the only place a gate is written down.

## Decision

1. **The gates the hook runs are declared in `sgate.gatekit.pre_push`.** Each
   step carries the label the hook prints, the script, its arguments, which
   copy of the script runs (the pushed tree's or this checkout's), which copy
   of the gate CLI is on `PYTHONPATH`, when the step runs, what it says when
   it fails, and whether its output is shown.

2. **The hook reads the declaration and loops over it.** The hook names no
   gate. What it still names is the machinery — the scripts it calls with
   logic of their own, such as the runner of the gate suite's own tests — and
   the delegate that decides the detekt verdict.

3. **The declaration is a module that imports nothing.** The hook reads it by
   path, because the tree being pushed may carry the file and nothing else of
   the gate package. A declaration that could only be read by importing its
   neighbours could not be read where it is needed. The registry re-exports
   it, so every reader that already imported `PRE_PUSH_SCRIPTS` from the
   registry keeps doing so.

4. **`PRE_PUSH_SCRIPTS` is derived from the steps.** The list of scripts the
   hook runs is no longer written down; it is what the steps and the
   machinery add up to. A gate that is added to the declaration is a gate the
   hook runs and a gate the fixture stubs.

5. **The gate compares the hook with the declaration in the new direction.**
   It used to check that the hook called every script the registry declared.
   Now it checks that the hook names no gate at all, and that it reads the
   declaration — a hook that ran no gate and said OK turned "not checked" into
   "checked and green".

   The rule it used to enforce is not dropped: it is enforced by the
   declaration instead of by the gate's reading of the hook's text. The hook
   tells the declaration which gates it answered for, and the declaration says
   whether that is all of them. A hook that answered for eleven of twelve
   gates fails the push, and the message names the gate it did not answer
   for.

6. **The hook says which gates it answered for, and a gate it skipped is an
   answer.** A gate that ran and a gate that was skipped are both decisions,
   and both are named. What is not an answer is a gate the hook never
   reached: that is the gap the declaration closes.

7. **A table that cannot be read fails the push.** The hook reads the
   declaration before the loop, and an empty table is a failure with a
   message, not a push that runs nothing and passes.

8. **A step may carry several conditions, and all of them have to hold.** The
   tree rules keep the two skips the base hook had: a tree that does not have
   the rule does not have the rule, and a machine whose gate CLI cannot run is
   not stopped by a rule it cannot execute. The second skip was a block of
   bash around the whole loop, and a loop that reads its steps from a table
   cannot be wrapped in a block — so the condition belongs to the step.

## Rationale

**Why the declaration is data and not code.** The blocks the hook carried were
bash because bash is what the hook is written in. That was the accident, not
a decision: a message is a sentence, an argument is a word, and a condition
is a condition. Written as data, they can be checked — the gate reads the
same declaration the hook reads, so a step that says something the hook does
not do is a difference somebody can see.

**Why the copy of the script is part of the declaration.** The gates differ in
which copy they run: the promise checks run the pushed tree's copy of the
checker, because each checker derives its repository root from its own file
location and the copy in the tree is the copy that knows about the tree. The
wait budgets run this checkout's copy, against the tree. A declaration that
did not say which copy runs would have lost that, and the difference is the
difference between checking the pushed tree and checking the worktree.

**Why the declaration imports nothing.** The hook reads it by path, in a tree
that carries the file and nothing else of the package. The alternative —
importing it — would have made the declaration unreadable in exactly the
place it is needed, and the failure would have been the hook's fail-closed
path rather than an import error.

**Why the gate's direction changed.** The old rule — "the hook calls every
script the registry declares" — was a rule about the hook's text. When the
hook stopped naming its gates, there was no text left to compare, and the rule
had nothing to say. The rule that says something now is the opposite one: the
hook names no gate, and it reads the declaration. A gate written into the
hook is now the drift, and the gate says so.

**Why the old rule is not simply dropped.** The old rule was the only thing
that made "the hook runs the gates the registry declares" true. Without the
text, the rule has nothing to compare — but the thing it protected does not
stop being true because the check moved. The hook runs the gates by label,
and a loop that skips one looks exactly like a loop that does not: the hook
says "OK" about a tree it never looked at. So the check moves to where the
knowledge is: the declaration knows how many gates there are, the hook says
which ones it answered for, and the declaration says whether that is all of
them. The gate that checks the hook now checks that the hook does this — a
hook that stopped saying what it answered for is a hook nobody can check.

**Why the prose moved with the code.** The comment that said why the wait
budgets gate exists, and why the tree rules are in the hook at all, described
the gates. Moving the gates without the prose would have left a reader with a
list and no reasons — and the reasons are the part that says whether a gate
should still exist.

## Consequences

- Adding a pre-push gate is one entry in one file. The hook, the fixture that
  stubs it, and the list of scripts the registry publishes all follow from it.
- The hook is about forty lines shorter, and the part that changed is the part
  that used to be copied nine times.
- The declaration is a second thing to read for someone who wants to know
  what the hook judges. That is a cost, and it is paid in one place instead
  of nine.
- The hook names the declaration by path. If the declaration moves, the hook
  fails closed with a message that says the list could not be read — and the
  gate compares the path in the hook with the path the registry declares, so
  the move is caught before a push.
- The tree rules are steps now, like every other gate. The loop that read
  them separately is gone, and the rules print their own names, which they
  already did.
- The hook answers for every gate it ran and every gate it skipped, and the
  declaration checks the answer. A gate that is added to the declaration and
  never run is a push that fails, and the message says which gate.
- A machine without the gate CLI installed is not stopped by the tree rules:
  the skip the base hook had around the whole loop is now a condition on the
  step, and the reason the base hook had it still holds.

## Links

- ADR `2026-10-22-the-gates-are-this-checkout.md` — the gates belong to the
  checkout, and the hook judges the tree that is being pushed
- `config/hooks/pre-push.json` — the record of what the hook judges and what
  only CI judges
- `scripts/src/sgate/gatekit/pre_push.py` — the declaration
- `scripts/check-hook-gates.py` — the gate that compares the hook with the
  declaration
- `scripts/tests/test_pre_push_hook.py` — the test that drives the hook
  through a real push

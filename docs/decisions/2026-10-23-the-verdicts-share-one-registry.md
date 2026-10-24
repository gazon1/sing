---
title: "The verdicts share one registry, and the pointer between them is what they agree on"
date: 2026-10-23
status: accepted
tags: [review, gates, tooling, architecture]
---

# The verdicts share one registry, and the pointer between them is what they agree on

## Context

Two gates read the verdicts of this repository.

`scripts/check-review-verdicts.py` asks whether every **change** carries a
verdict: a file `.specify/reviews/<slug>.md` with the change, the verdict, the
date, the pull request, the commit the review was made against and the digest
of the contract the verdict speaks for.

`scripts/ci/check-agent-review.py` asks whether a **pull request** carries a
review: a comment on the pull request that opens with `<!-- agent-review -->`
and states `**Verdict:** pass` or `**Verdict:** fail`.

Between them they had two problems.

**The vocabulary was written down twice.** The frontmatter shape, the two
verdicts a file may carry and the pull request it names were implemented twice
— once in each gate — and a pull request number was read by finding digits in
the field. `https://github.com/gazon1/sing/pull/1028` was read as "1028" by
one reader and as "1" by another, because `gazon1` carries a digit of its own.
Two implementations of one format drift apart, and the drift is invisible until
a verdict is read the wrong way.

**The first attempt at a rule between them was the wrong rule.** The pull
request gate asked whether the comment's verdict and the file's verdict are
equal. They are not two answers to one question: the comment is the pull
request's verdict and it is transient on purpose (the newest review decides,
and a later `pass` clears an earlier `fail`), while the file is the change's
verdict and it is a permanent record — a `fail` there is legal, is the
interesting case, and stays until the contract moves and the verdict is
re-issued. A gate that demanded equality would have demanded a state this
repository does not have: a pull request carrying a change whose review found
something could never have been green. PR 1028 carries three changes, one of
them with a `fail` in its verdict file, and it was a pull request that was
right to be green.

## Decision

**The vocabulary of a verdict lives in one module — `sgate.gatekit.verdicts` —
and both gates read it from there.** The frontmatter shape, the two verdicts,
the pull request a file names, the slug of a change and where its contract
lives are defined once, and the pull request gate reads them through a
bootstrap that points at the checkout it sits in.

**The two verdicts are not compared.** The pull request gate keeps the
comment's verdict as the pull request's state, and the change's verdict file
stays the change's record.

**What the two have to agree on is the pointer.** The file's `pr:` field names
the pull request whose review it records, and the pull request says which
changes it carries. Two things follow, and both are findings:

- a verdict file that names **this** pull request has to be about a change
  **this** pull request carries;
- a change whose **contract** this pull request moves has to carry a verdict
  that names **this** pull request — a contract that moves is re-issued, not
  re-read, and the re-issue names the pull request where the contract moved.
  The changes in the baseline carry no verdict file at all, and the gate that
  asks for one is `check-review-verdicts.py`; this one asks about the
  pointers that exist.

Both halves are about the pointers that name *this* pull request. The pull
requests the other verdicts name are not read: a pointer at a pull request
that does not exist is a defect of that verdict's own change, and a check
that answered for other pull requests would need a request per file in the
registry.

The second half is the one with teeth, and it is the one the repository's own
doctrine already implied: the verdict is re-issued in the same diff as the
contract that moved, so the re-issue belongs to that pull request.

**The checklist is not the contract.** `tasks.md` moves while the work is
done, and ticking a box is not a change of what the change requires. A pull
request that only moves a checklist does not have to re-issue the verdict, and
a verdict that names an earlier pull request stays valid.

## Consequences

- The pull request gate reddens on a stale pointer and on a re-issue that
  names the wrong pull request, and it says which of the two it found.
- A verdict file's `fail` no longer keeps a pull request red — that was the
  wrong rule, and it was also the rule that would have made PR 1028
  unmergeable.
- The change's verdict gate now asks the pull request field to name a pull
  request, instead of asking it to be non-empty.
- One module answers "what is a verdict", and a second implementation of it
  cannot appear without somebody going around it deliberately.

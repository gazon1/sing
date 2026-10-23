## What

<!-- What the change does, in two or three sentences. -->

## Why

<!-- The defect, the gap, or the question the change answers. What was wrong
     before, and what is true after. -->

## How

<!-- The shape of the change: the files, the decision, the contract that
     changed and the contract that did not. -->

## Verification

<!-- What was run, and what it said. A test that passed, a gate that went
     green, a device the change was tried on. -->

## Risk

<!-- What can break, and who notices if it does. -->

---

## Checklist

- [ ] The change is covered by a test that fails without it.
- [ ] The gates pass: `./check.sh`, `just docs-audit`, `just trace-coverage`.
- [ ] The change is reviewed by an agent that did not write it, and the review
      is written down on this pull request as a comment that **opens** with
      `<!-- agent-review -->` and states a verdict — `**Verdict:** pass` or
      `**Verdict:** fail`, and nothing else on that line. The `Agent review`
      job checks it, and the CI gate waits for it — a pull request without the
      review does not go green.
- [ ] If the review said `fail`, this pull request says what was done about
      the findings, and a later review says `pass` again.
- [ ] After the review is posted, the gate is counted again — the
      `agent-reviewed` label, or a push.

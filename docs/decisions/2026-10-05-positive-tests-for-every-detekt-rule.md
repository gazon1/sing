---
title: Positive tests for every custom detekt rule, and the PSI traps that hide no-ops
date: 2026-10-05
status: accepted
tags: [detekt, tests, tooling, process]
---

## Context

`2026-10-05-no-direct-dispatchers-rule-was-a-no-op` found one custom detekt rule that
was registered, packaged, given a config block, and referenced by a backlog entry — and
could not report a finding for any input. The obvious next question is whether there are
more.

Seven of the seventeen custom rules had no test file at all:

`KDocEnforcementRules` (2 rules), `MviViewModelRulesProvider` (5 rules),
`NoRealDelayInTestRule`, `NoStateInRule`, `NoStaticProfileAwareCurrentUserRule`,
`PassThroughUseCaseRule`, `UserScopedRepositoryRulesProvider` (`ProhibitUserIdInObserve`).

For those, "does this rule work?" had never been asked. Answering it took a smoke test
per rule feeding one canonical violating snippet and requiring a finding.

## Idea

1. Read each rule and judge from the source whether it can fire.
2. Write a positive test per rule and see which fail.
3. For each failure, determine whether the rule is broken or the fixture is wrong.

Option 1 is what produced the previous miss. Reading `NoDirectDispatchersRule` had looked
correct; the unsatisfiable guard was not apparent without knowing that `IO` is a property
and therefore has a non-call selector. Option 3 is the part that matters: the first
instinct on a failing test is to blame the fixture, and three of the four "broken" rules
here were in fact working.

## Decision

Add `RuleFiresSmokeTest`: one positive test per previously-untested rule, resolved
through its `RuleSetProvider` by name. Resolving via the provider also verifies the
name→rule wiring, which is a second failure mode — a rule registered under a name detekt
never asks for is as dormant as one that cannot fire.

**Nine of the ten untested rules fire correctly.** Three fixture bugs, one real no-op:

| Rule | Verdict |
|---|---|
| `ViewModelMustHaveKDoc`, `RepositoryInterfaceMustHaveKDoc`, `MviViewModelExt` | working — fixture used the wrong `compileContentForTest` overload |
| `NoRealDelayInTest`, `NoStateIn`, `NoViewModelScopeInProduction` | working |
| `PassThroughUseCase` | working — fixture used a constructor `val`; `resolveReceiverType` reads `KtProperty` from `declarations` |
| `ProhibitUserIdInObserve` | working — class must end with `Repository`, and `…Impl` does not |
| `MviViewModelExt` (2nd attempt) | working — needs explicit type references |
| **`NoStaticProfileAwareCurrentUser`** | **no-op — fixed** |

### The second no-op

The rule cast the receiver to `KtNameReferenceExpression` and compared its text to a
dotted FQN:

```kotlin
val receiver = expression.receiverExpression as? KtNameReferenceExpression ?: return
if (receiver.getReferencedName() != "com.singularity.todo...ProfileAwareCurrentUser") return
```

A bare identifier's text never contains dots, so it can never equal a dotted FQN. The two
spellings that *would* match — fully-qualified, and `.Companion` — both have a
`KtDotQualifiedExpression` receiver and returned even earlier. All three ways of writing
the banned access were excluded by the rule's own guards. Measured by printing the PSI:

```
'ProfileAwareCurrentUser.current'                  recv=KtNameReferenceExpression  text='ProfileAwareCurrentUser'
'ProfileAwareCurrentUser.Companion.scopedUserId'  recv=KtDotQualifiedExpression
'com...ProfileAwareCurrentUser.current'            recv=KtDotQualifiedExpression
```

Fixed by matching the receiver's written text with `.Companion` stripped, so bare and
qualified forms are both covered, with the decision extracted to a pure
`NoStaticProfileAwareCurrentUserPolicy` for the same testability reason as the dispatch
rule. `:shared:detekt` still reports 0 findings: the 8 real occurrences of the pattern in
the tree are all inside KDoc comments.

## The trap that cost the most time

`compileContentForTest` has two overloads, and the obvious one lies:

```kotlin
compileContentForTest(code, "com.example")            // KtFile.declarations == [KtScript]
compileContentForTest(code, Path.of("Fixture.kt"))    // KtFile.declarations == [KtClass]
```

The string overload wraps the content in a `KtScript`, so `root.declarations` is
`[KtScript]` and no `KtClass` is ever seen. **Every rule that iterates
`root.declarations`** — both KDoc rules, `MviViewModelExt`, `ProhibitUserIdInObserve` —
reports nothing under that overload.

The failure mode is asymmetric, and that is what makes it dangerous. A *positive* test
fails loudly, which is merely annoying. A *negative* test passes vacuously, because "no
findings" is exactly what a mis-compiled fixture produces — indistinguishable from a rule
that correctly ignores the input. Three of the four rules I first suspected of being
broken were working all along; had I written the "clean" tests first, I would have
recorded three more no-ops as fact.

`compileContentForTest(code, Path)` also **discards the directory component**:
`virtualFilePath` is `/X.kt` for any input. A path-scoped rule is therefore untestable
through the PSI layer at all, which is why both fixed rules have their decision logic
extracted into a pure policy object.

## Rationale

The generalisable lesson is narrow and worth stating precisely: **a rule with no positive
test is an unverified claim that it works, and a rule with only negative tests is
indistinguishable from a rule that never runs.** Both were true here — 7 rules had no
tests at all, and 2 of the 4 tests that existed for `NoDirectDispatchers` were negative
assertions that passed against a completely broken rule.

This is the same shape as the documentation-gate work in
`2026-10-05-doc-gates-must-parse-structure`: a check answering a narrower question than
the one being asked. `check-skill-frontmatter.sh` asked "is `description:` present?"
instead of "is this frontmatter valid?". The detekt analogue is "does this rule return no
findings on my fixture?" instead of "does this rule detect its target?".

The smoke tests are deliberately minimal — one question each, "can it fire?" — and that
is a real limitation, not a finished job. They are the cheapest possible signal that
catches the total-failure class, and they are not a substitute for branch coverage. See
Consequences.

## Consequences

- `:detekt-rules:test` goes from 73 to 91 tests, 0 failures. Two no-op rules are now real
  gates: `NoDirectDispatchers` and `NoStaticProfileAwareCurrentUser`, the latter guarding
  cross-profile reads.
- `NoDirectClockSystemRule`'s `isAllowedPath` was extracted as `internal` specifically so
  it could be unit-tested, and then never was. Now covered, including an assertion that
  both whitelisted files still exist in the tree — a whitelist entry pointing at a renamed
  file is a silent hole. Its `isAllowedPath` also did not normalise its own input, so it
  returned `false` for a Windows path; the conversion moved inside the function, because a
  function that answers "is this path allowed?" should answer it for a path.
- `:shared:detekt` reports 0 findings with both rules actually executing.
- Two rules have inherent blind spots that the tests now document rather than hide:
  `MviViewModelExt` reads `prop.typeReference`, so a ViewModel using type inference
  (`private val s = MutableStateFlow(...)`) escapes it entirely; and
  `NoStaticProfileAwareCurrentUser` matches on the written simple name, so a same-named
  class in another package would match too. Both are the price of a syntactic check with
  no type resolution. Neither is a bug; both are limits a reader should know.
- **Branch coverage for the 12 previously-untested rules is still owed.** The smoke tests
  answer one question per rule. Everything else — allowlists, exemptions, negative cases
  — is uncovered, and `PassThroughUseCaseRule` in particular has non-obvious guards
  (`operator`, `private`, `LlmUseCase`, `clock`, `tool` receivers) that no test exercises.
- The module now has two documented PSI traps (`compileContentForTest` overloads, and the
  discarded directory). Both are recorded in the test files themselves, because a trap
  that lives only in an ADR is a trap the next agent re-discovers by losing an hour.

## Links

- `2026-10-05-no-direct-dispatchers-rule-was-a-no-op` — the first no-op, and why
  registration and packaging are not operation
- `2026-10-05-doc-gates-must-parse-structure` — the same failure mode in the doc gates
- `singularity-todo-detekt-rules-authoring` skill — registration and service-file wiring
- `singularity-todo-test-tag-strategy` — the module's test conventions

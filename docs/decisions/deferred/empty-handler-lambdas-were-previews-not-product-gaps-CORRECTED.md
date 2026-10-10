---
title: "Empty Handler Lambdas Were Previews Not Product Gaps Corrected"
date: 2000-01-01
status: RESOLVED
tags: ["deferred"]
---

**Status: RESOLVED (2026-10-04).** The false claim was corrected in place and the empty lambdas in preview helpers were given the project's `noopClick`. No live product defect was ever found. The remaining sweep is tracked in #61.
**Found in:** 2026-10-04 by `NoEmptyOnClickLambda`, which was made able to fire
and reported 31 sites.

**First claim, and why it was wrong.** I wrote that these were product gaps —
"a dead back button and a dead Create backup button in BackupScreen, an
unclickable TaskCard in ArchiveScreen" — and filed them as work to be wired. That
was inferred from the finding *messages*, which name the composable, not from
reading where the lambda actually sits. On reading the files:

- `BackupScreen.kt` — the production composable takes `onBack: () -> Unit` and
  wires it: `IconButton(onClick = onBack, … testTag(BACKUP_TOP_BAR_BACK))`. The
  "Create backup" and navigation buttons are wired to real handlers. The three
  empty lambdas are inside `private fun BackupScreenContentPreview(state)`.
- `ArchiveScreen.kt` — same: the production `LazyColumn` wires
  `TaskCard(onClick = { navigator.navigate(TasksGraph(Detail(task.id.value))) })`.
  The empty `onClick` is in the `ArchiveContentPreview` helper.

**No live product defect was found.** Every one of the 12 `shared` findings is a
preview helper, a test builder, or a documented-intentional case.

**What was actually wrong, and is fixed in the same change:**

1. Preview functions are named `*Preview` and use the project's `PreviewThemed`
   wrapper, but the ones holding empty lambdas carry **no `@Preview` annotation**.
   `NoEmptyOnClickLambda.isPreviewContext` only recognises an `@Preview`
   annotation, a `preview` filename, or a `/preview/` directory — so the rule
   flagged the project's own previews. All 82 `@Preview` uses elsewhere show the
   annotation is available and simply was not applied here.
2. The prescribed migration was never followed: the rule's KDoc says preview code
   should use `noopClick` from `core/ui/preview/PreviewSamples.kt`. That constant
   exists and had zero uses at these sites.

**Resolved 2026-10-04:** every preview / test-builder site now passes `noopClick`,
and `DetailMetaChip`'s `onClick ?: {}` — which is a deliberate nullable API with
the chip disabled when null, documented on the parameter — carries a
`@Suppress("NoEmptyOnClickLambda")` explaining exactly that.

**Lesson, which is the real content here:** a lint finding names a *symbol*, not
a *situation*. I read "BackupScreen.kt" and "onClick" and constructed a product
defect that did not exist, then wrote it down with a user-visible symptom
attached. The cost of that mistake is a backlog entry that would have sent
someone to "fix" already-working code. A finding whose remediation is product
behaviour is exactly the kind that must be read in place before it is recorded.

---
title: "Two Rulesets Were Vacuous 52 Violations Were Invisible"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Found in:** 2026-10-04 verifiability change, the moment
`NoDirectDispatchers` and `NoEmptyOnClickLambda` were made able to fire.
`find-unwired-surfaces` and the detekt report both said "0 findings" for rules
whose KDoc promised coverage; neither was true.

**Status: OPEN**

**Tracked as:** #452, #32 (closed)

**Symptom:** making the rules effective surfaced **52 pre-existing violations**
that no gate had ever seen:

| Rule | shared | desktopApp | total |
|---|---|---|---|
| `NoDirectDispatchers` | 19 | 2 | 21 |
| `NoEmptyOnClickLambda` | 20 | 11 | 31 |

All were baselined in the same change so the build returns to green, and
`check-baseline-ratchet.py` now prevents the counts from growing again.

**Already ruled out:** not false positives from the widened detection. The
`Dispatchers.X` sites are direct references in production code (the rule's
target); the empty lambdas are genuine `onDismiss`/`onClick` placeholders.

**Try next, and treat as two separate pieces of work:**

1. **Dispatchers (21 sites).** Each needs a `CoroutineDispatcher` constructor
   parameter plus a Koin binding change, so it is not a mechanical edit — a
   blind constructor rewrite would break the DI graph that
   `koin-compiler-plugin` validates. Do them one module at a time, running
   `:mcp-server:compileKotlin` (the DI-graph gate) after each. Note the
   existing `FileLogWriter` path whitelist still works and must not be widened.
2. **Empty handler lambdas (31 sites).** The `onDismiss` cluster in
   `WhatsNewScreen` and `ContextMenuHost` suggests sheets/dialogs are given a
   no-op dismiss rather than a real one — often a genuine wiring gap, not just
   style. Check whether each is a preview-only placeholder before changing it;
   the rule already exempts `@Preview` and `*preview*` files, so everything it
   reports is production code.

**Do not** blanket-suppress these to make the count drop. That is the move that
produced this entry.

---

---
title: "The Unwritten Property Detector Cannot See A Ksp Expression"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN — blocked on an API boundary, re-verified 2026-10-07**

**Tracking:** tracked here rather than as a GitHub issue because the work is a decision
about a build dependency, not a product commitment — and because the next attempt is option 1
in "Try next" below, which is self-contained: add `kotlin-compiler-embeddable`, map
`KtExpression` onto the existing `UnwrittenPropertyAnalysis`, and see whether the gate's
output is worth a `--require` floor. That is a single afternoon with a known failure mode
(the full-callback surface is large and will need filtering), not a queue position.

**Found in:** 2026-10-07, while wiring `tools/unwritten-properties/` to a real
symbol processor. The pure analysis (`UnwrittenPropertyAnalysis.findNeverWritten`) and
its 13 tests are done and passing; the KSP adapter is not, and the reason is structural
rather than a missing dependency.

**Already ruled out — measured, not inferred.** `KSExpression`, `KSCallExpression` and
`KSPropertyAccessExpression` are **absent from every KSP jar in the local Gradle cache**,
verified by listing the class entries of `symbol-processing-api-2.3.11.jar`,
`symbol-processing-common-deps-2.3.11.jar` and every other `com.google.devtools.ksp`
artifact present. What `symbol-processing-api` 2.3.11 ships is declarations only:

```
KSAnnotated KSAnnotation KSCallableReference KSClassDeclaration KSClassifierReference
KSDeclaration KSDeclarationContainer KSFile KSFunction KSFunctionDeclaration
KSModifierListOwner KSName KSNode KSPropertyDeclaration KSPropertyAccessor
KSPropertyGetter KSPropertySetter KSReferenceElement KSType KSTypeAlias
KSTypeArgument KSTypeParameter KSTypeReference KSValueArgument KSValueParameter
KSVisitor KSVisitorVoid
```

**Why this is the boundary and not a gap.** Detecting a never-written property means
finding *references* — a property is written by an assignment or an `apply { }`, both of
which are expressions. KSP's supported API exposes the declaration tree, not the
expression tree, so "is this property ever written" is not expressible in the supported
surface. This is the same wall ADR `2026-10-07-reading-a-state-property-is-not-writing-one`
names from the other side: that ADR proves detekt cannot answer the question because it
visits one file at a time, and this entry says KSP cannot either, for a different reason.

**Why it is still worth doing rather than deleting.** The question is real — ADR
`2026-10-07-a-default-argument-that-is-wrong-for-every-caller` found 15 of 16 call sites
carrying a wrong tag by exactly this reasoning, and a never-written `isSupported` field
shipped once already. The detection has value; only the *route* is blocked.

**Try next, in this order.**

1. **Kotlin compiler analysis API directly** (`org.jetbrains.kotlin:kotlin-compiler-embeddable`,
   `KtExpression`). It has the expression tree, so the analysis maps directly onto
   `UnwrittenPropertyAnalysis`. Cost: an embeddable-compiler dependency and a processor
   that is no longer KMP-shaped. Check `check-dependency-usage.py` before declaring it —
   the gate will flag an artifact whose packages it cannot see used.
2. **A detekt rule over one file at a time, plus the never-written list maintained by
   review.** Honest, cheap, and it cannot be automated; it is strictly worse than option 1
   and strictly better than nothing.
3. **Leave it.** `tools/unwritten-properties/` stays a pure analysis with its tests, not
   wired into `:shared`. This is the current state and it is defensible: the ADR
   `2026-10-07-two-of-three-background-jobs-were-not-buildable-yet` records that a gate
   failing the build on every real finding needs human review, and that was true of the
   wiring independently of the API gap.

**Not to do:** write the adapter against `KSPropertyDeclaration` only. That sees
declarations, and a property is never *declared* again — it would report every property in
the codebase as never-written, which is a green gate asserting something false.

---

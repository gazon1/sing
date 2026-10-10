---
title: "A Viewmodel Scope And Its Reporter Can Be Different Ports"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "scope-reporter-agreement"]
---

**Status: CLOSED** (2026-10-05) — fixed, and the count above was wrong twice. See the correction.

**Tracked as:** [#143](https://github.com/gazon1/sing/issues/143) ·
`openspec/changes/scope-reporter-agreement/`

**Found in:** 2026-10-05, the sweep that followed the crash-reporting migration — asking what
structural gap the migration left, rather than what it fixed.

**Symptom.** `MviViewModel`'s default scope is derived from the ViewModel's own reporting port,
so the common case cannot diverge. The default is bypassed when a component supplies a scope
explicitly, and then the port `catchTo` reports to and the port the scope's failure handler
reports to are two independent arguments that nothing correlates.

**The count was wrong, and the rule found the difference.** The first count said two sites. It was
four, and the two extra ones were found by the rule written to close this, not by reading:

- `SearchViewModel` — classified here as "consistent by construction" because its *secondary*
  constructor derives the scope from the reporter. The secondary is what Koin resolves, so the
  binding is fine. The **primary** still required a scope, so any caller reaching it directly
  chose one independently of the reporter. A reviewer reading only the binding — which is what I
  did — sees nothing wrong.
- `SettingsViewModel` — constructor correct, and its **binding** replaced the derivation with a
  graph-supplied `scope = get()`. Correct class, divergent wiring, invisible to a constructor-only
  check.

Both are the shape a class looks safe in. The general lesson is the one #135 already records about
matching less than intended: a check that covers the case you happened to look at is
indistinguishable from one that covers the case you did not.

**Fix.** Option (a) from the issue — the scope default moved onto the primary constructor in both
ViewModels, and the three bindings stopped passing one. One destination by construction, nothing to
correlate. The `factory { reportingScope(get()) }` in `CoreDiModule` stays: `SyncRunner` and
`SyncRepositoryImpl` hold no reporter and legitimately take a scope from the graph.

**The rule that keeps it.** `NoDivergentScopeAndReporter`, third rule in
`no-unreported-failure-path`, two findings — the constructor's scope default, and a binding passing
both arguments. The second is not redundant: it is what caught `SettingsViewModel`, whose
constructor was already correct. Proven on the real tree by planting the old `scope = get()` into
the settings binding and confirming `:shared:detekt` went red, then green again on revert.

**Try next.** Nothing. Recorded because the *count* is the reusable part, and because the next
person auditing this class of gap will be tempted to stop at the binding.

---

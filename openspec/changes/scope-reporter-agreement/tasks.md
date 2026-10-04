# Tasks — scope-reporter-agreement

> **Implemented 2026-10-05. Not archived** — the last two tasks under *Close out* are why, and the
> second one is the reason the count in the proposal was wrong.
>
> Option (a) was taken. The rule found two more sites than the audit did — see *The count was
> wrong* below — which is the reusable finding and the reason the audit text was left in place
> rather than rewritten.

## Decide

- [x] **Pick the shape, and record why.** Option (a): make the disagreement unrepresentable. The
      scope default moved onto the *primary* constructor, so every constructor of the class
      inherits it and there is no second argument to correlate. Chosen over a graph test because it
      is the smaller surface and because it matches what most of the audited components already did.
- [x] **shared/ — derive the scope from the reporter.** `SyncViewModel` and `CalendarSyncViewModel`
      now default `scope` to `reportingScope(crashReporter)`.
- [x] **shared/ — update the bindings.** All three lost their `scope =` argument.
      `CoreDiModule.kt:219-220`, `CalendarSyncDiModule.kt:64-65`, and the settings binding.
- [x] **shared/ — the `factory { reportingScope(get()) }` stays.** It still has two consumers,
      `SyncRunner` and `SyncRepositoryImpl`, and both hold no reporting port. That is the case the
      factory exists for, not an orphan.

### The count was wrong, and the rule is what found it

The audit said two sites. It was four:

- **`SearchViewModel`** — classified as consistent because its *secondary* constructor derives the
  scope. The secondary is what Koin resolves, so the binding was fine; the **primary** still
  required a scope, so any caller reaching it directly chose one independently of the reporter.
- **`SettingsViewModel`** — constructor correct, binding wrong: it replaced the derivation with a
  graph-supplied `scope = get()`.

Both are shapes a class looks safe in when you read the binding, which is what the audit did. The
rule reported the second one immediately and the first one on the first `:shared:detekt` run after
it was written.

## Verify

- [x] **The rule.** `NoDivergentScopeAndReporter`, third rule in `no-unreported-failure-path`, two
      findings: the constructor's scope default, and a binding passing both arguments. The second
      is not redundant with the first — it is what caught `SettingsViewModel`, whose constructor
      was already correct. 13 positive tests, each paired with the legal shape that differs by one
      thing.
- [x] **Proven on the real tree.** Planted the old `scope = get()` into the settings binding:
      `:shared:detekt` went red naming the file and line. Reverted: green. The same run also
      reported `SearchViewModel`, which is how the fourth site was found.
- [x] **`CrashReportingWiringTest` and `NoUnreportedFailurePathRuleTest` stayed green unmodified.**
      A binding quietly losing its reporter is what those two exist to catch, and nothing had to be
      relaxed to make this change.

## Close out

- [x] **Amend `2026-10-05-background-failure-handler-and-the-guard-it-behind.md`** with the choice
      and, more usefully, with what the two existing rules structurally cannot see: correlating two
      sibling arguments is not a name-resolution question. That is why the third rule removes the
      possibility instead of comparing the two.
- [ ] **Move REQ-7/REQ-8 into `openspec/specs/crash-reporting/`.** Blocked on one thing: REQ-8's
      second scenario is a requirement that the check can be *shown* to fail, and the demonstration
      above was a manual run rather than something the build performs. Archiving now would put a
      guarantee in the spec that a future change could remove without noticing — which is the
      condition `failure-visibility` is still open for, and the reason the two changes are in the
      same state for the same reason.
- [ ] Note the interaction with #135. #135 is "a rule with no positive control can be added"; this
      is "a rule cannot see the disagreement that matters here". A future attempt to close this
      with a comparison rather than a removal should re-read #135's correction about checks that
      match less than intended — the two extra sites were both cases the audit did not look at.

## Already consistent — do not re-audit

Named so the next person does not re-count them. The eleven `slot/` classes hold no reporting port
and take the coordinator's work context; `TaskDetailCoordinator` defaults to the derived context.
Neither can disagree, and the requirement above is written to be satisfied by them already.

**Do not add `SearchViewModel` to this list.** It was here once, and it was wrong.

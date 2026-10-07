# Tasks — a-dropped-result-is-reported-where-it-happens

- [x] Write the rule and its three controls before trusting any finding.
- [x] Key the resolver by declaring type. The first version used a global name map and
      reported six sites, all of which were correct code — recorded in the proposal,
      because "the rule said so" is exactly the wrong reason to change working code.
- [x] Control each of the three measured false-positive shapes: a KDoc example, a
      multi-line chain, a call nested in a consuming wrapper's lambda.
- [x] Control the resolver's own failure mode: "no Result-returning declaration found"
      is a failure, not a pass. A rule whose resolver stopped matching looks exactly like
      a codebase that stopped violating.
- [x] Fix `DecomposeAndCreateTool.kt:107` — the eighth AI tool after the seven in
      `7775342b`, and the worst of them, because it creates in a loop.
- [x] Fix `BackupImporter.kt:140` — a `try`/`catch` written against a throwing contract on
      a `Result`-returning method, so `restoredCount` counted a write that never happened.
- [x] Extract `SourceScan` so `SyncedWriteEnqueuesTest` and this rule stop carrying one
      copy each of brace matching, file walking and comment stripping. Two copies of a
      comment stripper already meant two sets of bugs in it.
- [x] Write the three blind spots into the rule rather than leaving them for a reader:
      an inferred receiver type, reflection, and a failure dropped in a loop where the
      value is used.
- [ ] Close the backlog entry `dropped-result-guard`.
- [ ] Derive `selfReportingMethods()` instead of writing it down. Both obvious
      implementations were measured and both are wrong — see the proposal. Needs
      statement-level extraction; do not ship a hand list that looks derived.
- [ ] A rule for the third blind spot: a failure dropped where the value is used —
      `map { … repo.create(x) … }` keeping the id and dropping the failure. Two real
      defects had that shape (`DecomposeAndCreateTool`, `BackupImporter`) and neither was
      visible to a rule about dropped `Result`s, because by then there is no `Result` left.
- [ ] Move `SyncedWriteEnqueuesTest` onto `SourceScan`. It was written first and still has
      its own walker; the two are the same kind of rule and should not be two codebases.
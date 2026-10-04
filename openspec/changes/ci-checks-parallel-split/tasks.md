# Tasks — ci-checks-parallel-split

- [ ] Write down the leaf boundary before touching ci.yml: which steps move, and
      which floor or comparison each of them depends on. Every later review
      question is "what was this step's coupling", so the answer belongs in the
      proposal rather than in a reviewer's head.
- [ ] Design 2 first — move `Check executed test counts` into the leaf that runs
      `:shared:jvmTest` and `Check coverage floors` into the kover job. Keep the
      aggregator's `jq -e 'all(.result == "success")'` assertion and its
      `if: always()`; both are load-bearing and were verified against success,
      failure and cancelled inputs before.
- [ ] Use design 1 for anything the aggregator still needs stamped: publish
      `RUN_STARTED` as a job output and pass it explicitly to the consumer.
- [ ] Leave the flake step whole or take it whole. It reads this run's
      `shared/build/test-results/jvmTest` against the previous run's
      `junit-results` artifact; splitting either half of that comparison is the
      defect that caused the withdrawal.
- [ ] Keep `Retry environment` in the job that reads `retry.maxAttempts` —
      `DesktopAppHarness` is the only reader, and the desktop leaf is its home.
      `maxAttempts` stays absent on the non-PR branch: setting it to 0 means
      `repeat(0)`, which skips every test body, and that bug failed all 45
      desktopApp UI tests on every push.
- [ ] Measure before and after on the same commit, and record both. The 24.25m
      and 9.2m figures came from run 37209255590 and run 37212487694; a change
      with no second number is a change nobody can evaluate.
- [ ] Count the runner minutes before and after. Nine jobs each pay their own
      checkout, JDK and Gradle bootstrap, and a private repository on a free plan
      pays for concurrency in wall-clock it does not get back.
- [ ] Restore `:detekt-rules:test` and `:detekt-rules:detekt` as their own leaf
      or fold them into `test-and-check` deliberately. They are currently in the
      monolith; leaving them out of the split is how they were nearly lost in the
      rebase.
- [ ] If the design 3 route is ever taken, it needs its own proposal: a stored
      flake baseline cannot see a passed-here/failed-there flake, which is the
      entire reason the cross-run comparison exists.

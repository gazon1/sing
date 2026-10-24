# ==============================================================================
# 🧱 CI — the same four tiers the workflow runs, locally
# ==============================================================================
#
# The pull request now carries four jobs, and each of them answers one
# question. `check.sh` answers all of them at once, which is the right thing
# before a push and the wrong thing while you are still finding out whether
# the change compiles. These recipes run one tier each, so the local loop can
# answer the same question the CI job answers — and be compared with it.
#
# Every recipe mirrors the job it is named after, and says which one:
#
#   ci::quick     -> the `quick` job      (the affected tests)
#   ci::tests     -> the `tests` job      (the suite, no coverage agent)
#   ci::detekt    -> the `detekt` job     (the eight modules CI judges)
#   ci::coverage  -> the `coverage` job   (the suite under the agent, and the floors)
#
# A recipe that drifts from its job is a recipe that answers a question
# nobody asked, so each one names the workflow step it mirrors. If the
# workflow changes, the recipe that mirrors it changes with it — in the same
# diff, where a reviewer sees both.
#
# `./gw`, not `./gradlew`: the wrapper reaches this worktree's private Gradle
# home. A bare `./gradlew` raises a daemon in the shared `~/.gradle`, where
# any worktree's `--stop` can kill it.

[group('ci')]
[doc('The fast signal: the test classes this change can break')]
[working-directory(justfile_directory())]
quick:
    #!/bin/bash
    set -euo pipefail
    # Mirrors the `quick` job (`.github/workflows/ci.yml`, step "Run the
    # affected tests"). The job runs the same tool with the same population;
    # the job adds `-Pcoverage.enabled=false`, which the map's own invocation
    # does not need — the map selects classes, it does not run a suite.
    #
    # `--population fast` names the population the repository declares in
    # `config/test-populations.properties`. The fast half is the contract
    # (the requirement R5 of the change this mirrors), so the fast half is
    # what runs here too.
    python3 scripts/test-impact-map.py --run --population fast --gradle ./gw

[group('ci')]
[doc('The suite without the coverage agent — the verdict')]
[working-directory(justfile_directory())]
tests:
    #!/bin/bash
    set -euo pipefail
    # Mirrors the `tests` job (`.github/workflows/ci.yml`, step "Gradle
    # (tests)"). Same tasks, same flags, minus what is CI's: the retry flags
    # and the step logging are the runner's business.
    #
    # `-Pcoverage.enabled=false` is the point of the tier: the pull-request
    # path carries no instrumented suite, and a local run that measured
    # coverage would answer a question the pull request does not ask.
    #
    # The population defaults to `fast` — the pull request's population. Pass
    # another one to run what the schedule runs.
    population="${POPULATION:-fast}"
    ./gw --continue --no-parallel \
        :mcp-server:jar \
        :shared:verifyToolSurface \
        :shared:jvmTest \
        :shared:testAndroidHostTest \
        :desktopApp:test \
        :mcp-server:test \
        :androidApp:test \
        :detekt-rules:test \
        :tools:unwritten-properties:test \
        -Ptest.population="$population" \
        -Pcoverage.enabled=false

[group('ci')]
[doc('Detekt for the eight modules CI judges')]
[working-directory(justfile_directory())]
detekt:
    #!/bin/bash
    set -euo pipefail
    # Mirrors the `detekt` job (`.github/workflows/ci.yml`, step "Gradle
    # (detekt)"). The eight modules are the ones
    # `config/hooks/pre-push.json` names, and the gate
    # `scripts/check-hook-gates.py` compares that record with this file in
    # both directions — so a module added here and not there is a finding,
    # and a module added there and not here is a finding too.
    #
    # The auto-fix is NOT here: the job's first step runs
    # `scripts/ci/detekt-auto-fix.sh`, which commits and pushes a correction
    # branch — a CI action with a branch of its own. Locally the correction is
    # the pre-push hook's `--auto-correct` run.
    ./gw --continue --no-parallel \
        :shared:detekt \
        :desktopApp:detekt \
        :androidApp:detekt \
        :mcp-server:detekt \
        :adr-cli:detekt \
        :adr-core:detekt \
        :core-base:detekt \
        :detekt-rules:detekt \
        -Pcoverage.enabled=false

[group('ci')]
[doc('The suite under the coverage agent, and the floors — the CI coverage job')]
[working-directory(justfile_directory())]
coverage:
    #!/bin/bash
    set -euo pipefail
    # Mirrors the `coverage` job (`.github/workflows/ci.yml`, steps "Gradle
    # (coverage)", "Coverage floors", "Coverage table" and "Coverage report").
    #
    # Not `just coverage` — that one (`tests::coverage`) renders the report
    # from whatever a previous run left behind, and runs no suite. This one
    # takes the measurement and judges it against the floor.
    #
    # `-Pcoverage.enabled=true` is the explicit opt-in, and the population
    # defaults to `full` — the population the floors are declared against
    # (`FULL_*` in `settings.gradle.kts`). The job reads the population the
    # `changes` job published; the trunk publishes `full`.
    #
    # The report is rendered from ONE aggregate: `koverReport` pulls the test
    # tasks in, and naming them again would be a second list that has to stay
    # in step with kover's. `:mcp-server:jar` is required in the same
    # invocation — the end-to-end test skips itself when the jar is absent,
    # which is a silent pass and not a failure.
    population="${POPULATION:-full}"
    ./gw --continue --no-parallel \
        :mcp-server:jar \
        :shared:verifyToolSurface \
        koverReport \
        koverHtml \
        :detekt-rules:test \
        -Ptest.population="$population" \
        -Pcoverage.enabled=true
    # The floor, checked against the measurement the run above produced.
    ./gw :koverVerify -Ptest.population="$population"
    # The table and the log: advisory, and rendered from the same numbers.
    ./gw :koverMarkdownTable -Ptest.population="$population" || true
    ./gw -q :koverLog -Ptest.population="$population" || true

# ==============================================================================
# 🧪 TESTS — common, jvm, android-host, shared, check, clean
# ==============================================================================

SKIP_ADB := env_var_or_default("SKIP_ADB", "0")

[group('tests')]
[doc('Run commonMain tests (pure Kotlin, no platform)')]
[working-directory(justfile_directory())]
common:
    #!/bin/bash
    set -euo pipefail
    ./gw :shared:commonTest

[group('tests')]
[doc('Run only the checks this change makes worth running')]
[working-directory(justfile_directory())]
affected:
    #!/bin/bash
    set -euo pipefail
    # The loop costs about thirteen minutes, and one step of it costs seven. The
    # measurement is published (docs/testing/gate-costs.md), so the question it was
    # taken to answer is the obvious one: what does this change require?
    #
    # The answer is computed from the module graph the build files declare — a
    # change in :core-base reaches :adr-core, then :shared, then the four modules
    # that compile against it — and not guessed. See scripts/changed-modules.py.
    #
    # This is a *lane*, not a gate: it runs a subset of the checks, and the full
    # loop is still what CI runs and what `just tests::check` runs.
    TASKS=()
    while IFS= read -r line; do
        if [ -n "$line" ]; then
            TASKS+=("$line")
        fi
    done < <(python3 scripts/changed-modules.py)
    if [ ${#TASKS[@]} -eq 0 ]; then
        echo "nothing to run: this change touches no module that owns a check."
        exit 0
    fi
    GRADLE=()
    SHELL_TASKS=()
    SCRIPT_TESTS=0
    for task in "${TASKS[@]}"; do
        case "$task" in
            scripts/tests) SCRIPT_TESTS=1 ;;
            scripts/*) SHELL_TASKS+=("$task") ;;
            *) GRADLE+=("$task") ;;
        esac
    done
    echo "affected: ${GRADLE[*]:-none} ${SHELL_TASKS[*]:-none}"
    if [ ${#GRADLE[@]} -gt 0 ]; then
        ./gw "${GRADLE[@]}" --quiet
    fi
    for task in ${SHELL_TASKS[@]+"${SHELL_TASKS[@]}"}; do
        bash "$task"
    done
    if [ "$SCRIPT_TESTS" = "1" ]; then
        python3 -m unittest discover -s scripts/tests
    fi

[group('tests')]
[doc('Run the tests this change can break — the map, computed from the tree')]
[working-directory(justfile_directory())]
impact:
    #!/bin/bash
    set -euo pipefail
    # `tests::affected` answers at module level; this one answers at class level.
    # The map is computed from the same tree the tests read: a change in a scope
    # maps the tests of that scope, and a change in a class maps the tests that
    # mention it. The map is a candidate set, not a verdict — it never says that
    # a test outside it is safe, only which ones are worth running first.
    # See scripts/test-impact-map.py and the ADR that explains the rules.
    #
    # `--population fast` names the population the repository declares in
    # `config/test-populations.properties`, instead of the map spelling a tag
    # list of its own — the same tool must not answer two different questions
    # in two places. `--gradle ./gw` reaches this repository's private Gradle
    # home; `./gradlew` would reach the shared `~/.gradle` that the wrapper
    # exists to avoid.
    python3 scripts/test-impact-map.py --run --population fast --gradle ./gw

[group('tests')]
[doc('Run jvmTest (Room + SQLite on JVM)')]
[working-directory(justfile_directory())]
jvm:
    #!/bin/bash
    set -euo pipefail
    ./gw :shared:jvmTest

[group('tests')]
[doc('Run the tests for the custom detekt rules')]
[working-directory(justfile_directory())]
detekt-rules:
    #!/bin/bash
    set -euo pipefail
    # Added 2026-10-05: this was never run by any recipe or CI job, which is how
    # NoDirectDispatchersRule's two failing tests survived — the rule could not fire at
    # all and nothing noticed. `:detekt-rules:test` is now also a CI step.
    ./gw :detekt-rules:test

[group('tests')]
[doc('Run the gate-script tests (docs/governance gates)')]
[working-directory(justfile_directory())]
gate-scripts:
    #!/bin/bash
    set -uo pipefail
    # The "ran 0 tests" guard matters: a file missing `unittest.main()` runs nothing and
    # exits 0, so it would report success while testing nothing.
    status=0
    for t in scripts/tests/test_*.py; do
        echo "--- $t"
        out=$(python3 "$t" 2>&1); rc=$?
        echo "$out" | tail -3
        if [ "$rc" -ne 0 ]; then
            echo "FAIL: $t exited $rc"
            status=1
            continue
        fi
        if ! echo "$out" | grep -qE '^Ran [1-9][0-9]* tests?'; then
            echo "FAIL: $t ran zero tests — is unittest.main() missing?"
            status=1
        fi
    done
    echo ""
    if [ "$status" -eq 0 ]; then
        echo "gate-scripts: OK"
    else
        echo "gate-scripts: FAILED"
    fi
    exit $status

[group('tests')]
[doc('Run testAndroidHostTest (Robolectric, no device needed)')]
[working-directory(justfile_directory())]
android-host:
    #!/bin/bash
    set -euo pipefail
    ./gw :shared:testAndroidHostTest

[group('tests')]
[doc('Run common + jvm + android-host tests')]
[working-directory(justfile_directory())]
shared:
    #!/bin/bash
    set -euo pipefail
    just tests::common
    just tests::jvm
    just tests::android-host

[group('tests')]
[doc('Run ./gw test (all modules)')]
[working-directory(justfile_directory())]
test:
    #!/bin/bash
    set -euo pipefail
    ./gw test

[group('tests')]
[doc('Run full check: tests + assembleDebug + lint (respects $SKIP_ADB)')]
[working-directory(justfile_directory())]
check:
    #!/bin/bash
    set -euo pipefail
    # check.sh already runs detekt (step [7/7]); lint here would run it a second time.
    SKIP_ADB={{SKIP_ADB}} ./check.sh

[group('tests')]
[doc('Run the affected lane: the tests this change can break, and the detekt of the files it touches')]
[working-directory(justfile_directory())]
check-affected:
    #!/bin/bash
    set -euo pipefail
    # A *lane*, not a smaller loop: every gate still runs, and `just tests::check`
    # is still the full one. The tests come from scripts/test-impact-map.py — the
    # same selection CI's "Affected tests" job asks — and the detekt from
    # scripts/lint-changed.py.
    SKIP_ADB={{SKIP_ADB}} ./check.sh --affected

[group('tests')]
[doc('Clean build artifacts')]
[working-directory(justfile_directory())]
clean:
    #!/bin/bash
    set -euo pipefail
    ./gw clean

# ---------------------------------------------------------------------------
# gate — the one command that means "the gates are green"
#
# Before this recipe, "I ran the gates" was a claim about which four commands
# the speaker remembered. The gates existed; the set did not. Everything below
# is already a gate somewhere else — this composes them and stops at the first
# red, so a partial run cannot be mistaken for a full one.
#
# Each step prints its own banner, because a gate that fails silently halfway
# through a 20-minute script is how a stale APK gets tested (see
# maestro-gate-can-test-a-stale-apk).
# ---------------------------------------------------------------------------

[group('tests')]
[doc('Run every gate in order: check.sh -> detekt -> agenda flows (SKIP_MAESTRO=1 to skip flows)')]
[working-directory(justfile_directory())]
gate:
    #!/usr/bin/env bash
    set -euo pipefail
    banner() { echo -e "\n\033[1;36m══ $* ══\033[0m\n"; }

    banner "1/3 check.sh (tests + Android assemble + docs)"
    SKIP_ADB={{SKIP_ADB}} ./check.sh

    # check.sh runs detekt as its own step, so this is a no-op re-run on a warm
    # build and a real check on a cold one. It is here because "I ran check.sh"
    # is not the same claim as "I ran detekt" when the two ever drift apart.
    banner "2/3 detekt"
    # `lint`, not a second copy of the task list. The inline version named two modules,
    # so :androidApp and :detekt-rules were never analysed by this gate — and it re-ran
    # check-detekt-registrations.sh, which `lint` already runs. One authority.
    just lint

    if [[ "${SKIP_MAESTRO:-0}" == "1" ]]; then
        banner "3/3 agenda flows — SKIPPED (SKIP_MAESTRO=1)"
        echo "Not run. A run without the flows does not gate the flows."
        exit 0
    fi

    banner "3/3 agenda flows on device"
    # `tests::gate-maestro`, for the same reason as any other recipe in this module:
    # an unqualified name does not resolve from `gate`. `just gate` completed two
    # green steps and then died here with "justfile does not contain recipe
    # `gate-maestro`", which reads like a Maestro problem and is a name-resolution one.
    just tests::gate-maestro agenda

    banner "all gates green"

# ---------------------------------------------------------------------------
# Lint / static analysis
# ktlint (formatting) is bundled via detekt-formatting plugin:
#   - detekt    = logic, complexity, naming, style, performance
#   - ktlint    = formatting, imports, whitespace (via detekt-formatting)
#   - detektFormat = auto-fix for both (run: just detekt-fix)
# ---------------------------------------------------------------------------

[group('lint')]
[doc('Run detekt analysis (report-only); use detekt-fix to auto-fix')]
[working-directory(justfile_directory())]
detekt:
    #!/bin/bash
    set -euo pipefail
    # Cheap, runs first. A duplicated rule registration fails the Gradle build
    # later with a YAML error pointing at the config rather than the real cause.
    ./scripts/check-detekt-registrations.sh
    # :detekt-rules is the module that enforces the project's rules, so it is
    # subject to them too — a `var` on a Rule instance had nothing checking it.
    # :androidApp is here because it was never linted at all: its source set
    # pointed at a directory that does not exist, and this recipe never named it.
    # :mcp-server is here because a module with a baseline is a module that can
    # silently grow that baseline without anybody noticing — and mcp-server has one.
    ./gw :shared:detekt :desktopApp:detekt :detekt-rules:detekt :androidApp:detekt :mcp-server:detekt

[group('lint')]
[doc('Correct what detekt can correct, then report what is left')]
[working-directory(justfile_directory())]
detekt-fix:
    #!/bin/bash
    set -euo pipefail
    # Two passes, in this order, and the order is the point.
    #
    # The first corrects; the second reports. A single report-only pass leaves the
    # author editing by hand what a formatter rule would have written, and a single
    # correcting pass hides what it could not fix behind an exit code that looks
    # like success.
    #
    # `--auto-correct` was believed inert here (ADR
    # 2026-10-09-detekt-auto-correct-inert-multi-module, measured 2026-10-09) and
    # the flag was dropped, which turned "inert" into "never asked" — two different
    # claims that read the same from the outside. It is not inert: on 2026-10-17 a
    # planted `"value is ${name} here"` in :shared was rewritten to `"value is $name
    # here"` by `./gradlew :shared:detekt --auto-correct --rerun-tasks`, and that
    # pass cleared the 36 findings that had made `main` red. See
    # ADR 2026-10-17-detekt-auto-correct-works-use-it.
    #
    # `--rerun-tasks` on the correcting pass only: without it the task can be
    # UP-TO-DATE from the verification pass that ran a moment ago, and a cached task
    # rewrites nothing.
    #
    # One invocation per module, and this is measured, not stylistic. With a planted
    # StringTemplate violation, `:shared:detekt :desktopApp:detekt --auto-correct`
    # REPORTED the finding and rewrote nothing; the same two tasks one invocation
    # each rewrote it, and `:desktopApp:detekt :shared:detekt` in a single
    # invocation rewrote it again. The flag reaches only the LAST detekt task of an
    # invocation, so a task list silently corrects one module and skips the rest —
    # which looks exactly like the inertness ADR 2026-10-09 recorded. Collapsing
    # this loop back into one call reintroduces that bug in a form nobody can see.
    for task in :shared:detekt :desktopApp:detekt :detekt-rules:detekt :androidApp:detekt :mcp-server:detekt; do
        ./gw "$task" --auto-correct --rerun-tasks --console=plain || true
    done
    ./gw :shared:detekt :desktopApp:detekt :detekt-rules:detekt :androidApp:detekt :mcp-server:detekt \
        --console=plain

[group('lint')]
[doc('Generate detekt baseline files (capture current violations)')]
[working-directory(justfile_directory())]
detekt-baseline:
    #!/bin/bash
    set -euo pipefail
    ./gw :shared:detektBaseline :desktopApp:detektBaseline :androidApp:detektBaseline :mcp-server:detektBaseline

[group('lint')]
[doc('Alias for detekt (lint = detekt in this project)')]
lint: detekt

[group('lint')]
[doc('Run detekt over the changed files only (the scoped lint)')]
[working-directory(justfile_directory())]
lint-changed:
    #!/bin/bash
    set -euo pipefail
    # The scoped lint: the same config, the same baseline, the same rules as the
    # module's own detekt task — over the changed files only.
    #
    # The whole module takes three to four minutes and answers a question about
    # every file in it, while a mechanical finding (a too-complex method, a long
    # method, a missing blank line) is a finding about the file that was just
    # written. This answers that question in seconds.
    #
    # The file list is computed from git: the merge base with the base branch
    # plus the working tree, so a staged-but-uncommitted change counts.
    python3 scripts/lint-changed.py "$@"


# ---------------------------------------------------------------------------
# Documentation audit
# ---------------------------------------------------------------------------

[group('docs')]
[doc('Audit docs: frontmatter, size budgets, dead refs, supersede chains, DIGEST')]
[working-directory(justfile_directory())]
docs-audit:
    #!/bin/bash
    set -uo pipefail
    status=0
    echo "=== Doc size budgets: removed 2026-10-19 ==="
    # The doc-size script and the `doc sizes are within budget` gate are gone.
    # The DIGEST budget still has an owner (the generator that prints it);
    # the AGENTS.md / SKILL.md / PROGRESS.md line caps do not have one any more.
    echo ""
    echo "=== pro/ licence boundary (no FSL or vendor code in the Apache-2.0 core) ==="
    # Source rules plus the rule's own control. The APK mode is deliberately NOT here:
    # it needs a build to have happened, and a build inside docs-audit is a check nobody
    # runs. CI runs it after assembleDebug, in both configurations.
    python3 scripts/check-pro-licence-boundary.py || status=1
    python3 scripts/check-pro-licence-boundary.py --self-test || status=1
    echo ""
    echo "=== Provenance registry (GPL/LGPL upstream named in production code) ==="
    # Two commands, and the second is the one that matters most. The first checks the
    # repository; the second plants a marked file in a temp dir and asserts the rule
    # catches it. A marker regex that quietly stops matching leaves the first green
    # and verifying nothing, which is the failure this repo has paid for twice.
    python3 scripts/check-provenance.py || status=1
    python3 scripts/check-provenance.py --self-test || status=1
    echo ""
    echo "=== DIGEST freshness ==="
    ./scripts/refresh-decisions-digest.sh
    echo ""
    echo "=== Source tree drift ==="
    ./scripts/print-source-tree.sh --output /tmp/source-tree.md
    echo "(source tree written to /tmp/source-tree.md for manual diff)"
    exit $status

[group('docs')]
[doc('Re-generate docs that are auto-derived from the tree (SKILLS-CATALOG, DIGEST)')]
[working-directory(justfile_directory())]
docs-regen:
    #!/bin/bash
    set -euo pipefail
    ./scripts/regen-skills-catalog.sh
    ./scripts/refresh-decisions-digest.sh
    echo "docs-regen: regenerated SKILLS-CATALOG.md, DIGEST.md and DIGEST-full.md"

[group('tests')]
[doc('Run the agent workflow evals (agent behaviour regression)')]
[working-directory(justfile_directory())]
tcheck-evals:
    #!/bin/bash
    set -euo pipefail
    python3 scripts/run-evals.py

# ---------------------------------------------------------------------------
# Coverage (kotlinx-kover)
# Aggregated across every module's test JVM — see settings.gradle.kts. Always go
# through the root `koverReport` / `koverHtml`: Kover only merges the test
# tasks that are already in the task graph, so a bare `:koverXmlReport` would
# report whatever a previous run left in the build directory.
# ---------------------------------------------------------------------------

[group('coverage')]
[doc('Generate the aggregated kover XML coverage report')]
[working-directory(justfile_directory())]
coverage:
    #!/bin/bash
    set -euo pipefail
    ./gw koverReport

[group('coverage')]
[doc('Generate the aggregated kover HTML coverage report')]
[working-directory(justfile_directory())]
coverage-html:
    #!/bin/bash
    set -euo pipefail
    ./gw koverHtml

[group('coverage')]
[doc('Print the kover markdown coverage table to stdout')]
[working-directory(justfile_directory())]
coverage-markdown:
    #!/bin/bash
    set -euo pipefail
    ./gw koverMarkdownTable

# ---------------------------------------------------------------------------
# check — run tests + lint (report-only initially)
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# ui-maestro — Maestro UI flows on a connected device/emulator
# ---------------------------------------------------------------------------
[group('tests')]
[doc('Run Maestro UI flows: just ui-maestro [flow] [tags] (SERIAL=<id> picks the device)')]
ui-maestro flow="Maestro/flows" tags="":
    #!/usr/bin/env bash
    set -euo pipefail
    SERIAL="${SERIAL:-}" FLOW="{{ flow }}" TAGS="{{ tags }}" \
        run-maestro

[group('tests')]
[doc('Maestro gate: run every flow carrying a tag (positional args!)')]
[working-directory(justfile_directory())]
gate-maestro tags="agenda" serial="":
    #!/usr/bin/env bash
    set -euo pipefail
    # The runner installs the APK itself, before the first flow: with the auth
    # bypass, against the pinned device, and with the recovery a dying device
    # needs. Installing here as well bought a second build — `SKIP_INSTALL=1`
    # was never read by the runner (it knows the `--skip-install` flag), so
    # every run paid for two installs and measured the second one.
    SERIAL="{{serial}}" TAGS="{{tags}}" \
        run-maestro


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
[doc('Run every gate in order: check.sh -> detekt -> coverage ratchet -> agenda flows (SKIP_MAESTRO=1 to skip flows)')]
[working-directory(justfile_directory())]
gate:
    #!/usr/bin/env bash
    set -euo pipefail
    banner() { echo -e "\n\033[1;36m══ $* ══\033[0m\n"; }

    banner "1/4 check.sh (tests + Android assemble + docs)"
    SKIP_ADB={{SKIP_ADB}} ./check.sh

    # check.sh runs detekt as its own step, so this is a no-op re-run on a warm
    # build and a real check on a cold one. It is here because "I ran check.sh"
    # is not the same claim as "I ran detekt" when the two ever drift apart.
    banner "2/4 detekt"
    ./scripts/check-detekt-registrations.sh
    ./gradlew :shared:detekt :desktopApp:detekt

    banner "3/4 coverage ratchet"
    just coverage-ratchet

    if [[ "${SKIP_MAESTRO:-0}" == "1" ]]; then
        banner "4/4 agenda flows — SKIPPED (SKIP_MAESTRO=1)"
        echo "Not run. A run without the flows does not gate the flows."
        exit 0
    fi

    banner "4/4 agenda flows on device"
    just gate-maestro agenda

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
    ./gw :shared:detekt :desktopApp:detekt :detekt-rules:detekt :androidApp:detekt

[group('lint')]
[doc('Auto-fix detekt style rules + ktlint formatting violations')]
[working-directory(justfile_directory())]
detekt-fix:
    #!/bin/bash
    set -euo pipefail
    # detekt 2.x merges formatting into the main task with autoCorrect=true in ktlint config
    ./gw :shared:detekt :desktopApp:detekt :detekt-rules:detekt :androidApp:detekt --auto-correct

[group('lint')]
[doc('Generate detekt baseline files (capture current violations)')]
[working-directory(justfile_directory())]
detekt-baseline:
    #!/bin/bash
    set -euo pipefail
    ./gw :shared:detektBaseline :desktopApp:detektBaseline :androidApp:detektBaseline

[group('lint')]
[doc('Alias for detekt (lint = detekt in this project)')]
lint: detekt

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
    echo "=== Skill frontmatter (name + description required) ==="
    ./scripts/check-skill-frontmatter.sh || status=1
    echo ""
    echo "=== Doc size budgets (AGENTS.md, SKILL.md, description, DIGEST.md) ==="
    python3 scripts/check-doc-sizes.py || status=1
    echo ""
    echo "=== Dead file references in docs, skills and KDoc ==="
    python3 scripts/check-doc-dead-refs.py || status=1
    echo ""
    echo "=== Dangling ADR references (slugs that no longer resolve) ==="
    python3 scripts/check-adr-references.py || status=1
    echo ""
    echo "=== ADR status policy (vocabulary, supersede chains, stale open) ==="
    python3 scripts/check_adr_status.py || status=1
    echo ""
    echo "=== ADR frontmatter normalization (dry run) ==="
    ./scripts/normalize-adr-frontmatter.sh --dry-run || status=1
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
    echo "docs-regen: regenerated SKILLS-CATALOG.md and DIGEST.md"

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
[doc('Coverage ratchet for feature/agenda: fail if line coverage drops below the baseline')]
[working-directory(justfile_directory())]
coverage-ratchet *args="":
    #!/bin/bash
    set -euo pipefail
    # Kover merges binary reports incrementally, so a report produced after other
    # runs carries coverage from tests outside the current filter. The floors are
    # only reproducible from a clean state, hence the wipe.
    rm -rf shared/build/kover shared/build/reports/kover
    # `-Pkover.jvmTest=true` instruments jvmTest, which is off by default. That
    # flag is a speed choice, not a safety one: the OOM that originally justified
    # disabling instrumentation was misattributed (2026-09-27-write-layer-soundness.md
    # ledger #11), and a full instrumented run was measured on 2026-10-04 at 9m27s
    # with no OOM. It stays off by default so ./check.sh does not pay those minutes
    # on every run.
    ./gradlew :shared:jvmTest :shared:koverXmlReport \
        -Pkover.jvmTest=true \
        --console=plain
    python3 scripts/coverage-ratchet.py {{args}}

# ---------------------------------------------------------------------------
# check — run tests + lint (report-only initially)
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# ui-maestro — Maestro UI flows on a connected device/emulator
# ---------------------------------------------------------------------------
[group('tests')]
[doc('Run Maestro UI flows: just ui-maestro [flow] [tags] [serial]')]
ui-maestro flow="Maestro/flows" tags="":
    #!/usr/bin/env bash
    set -euo pipefail
    SERIAL="${SERIAL:-}" FLOW="{{ flow }}" TAGS="{{ tags }}" \
        bash scripts/run-maestro.sh

[group('tests')]
[doc('Maestro gate: install the APK, then run every flow carrying a tag (positional args!)')]
[working-directory(justfile_directory())]
gate-maestro tags="agenda" serial="":
    #!/usr/bin/env bash
    set -euo pipefail
    # Install first, by construction. The trap this avoids: passing
    # SKIP_INSTALL=1 to save a minute, the emulator dying mid-run, the
    # relaunched device holding a snapshot from before your fix — and the
    # journeys going green against a binary that does not contain it.
    if [[ -n "{{serial}}" ]]; then
        ./gradlew :androidApp:installDebug -Pandroid.device="{{serial}}" --quiet
    else
        ./gradlew :androidApp:installDebug --quiet
    fi
    SERIAL="{{serial}}" TAGS="{{tags}}" SKIP_INSTALL=1 \
        bash scripts/run-maestro.sh

[group('tests')]
[doc('Report detekt-rule line coverage (which rules are under-tested)')]
[working-directory(justfile_directory())]
kover-rules:
    #!/bin/bash
    set -euo pipefail
    # Added 2026-10-05 alongside the kover plugin in detekt-rules. Before this the
    # rule-coverage debt was prose in deferred-backlog.md with nothing able to verify it.
    ./gw :detekt-rules:koverXmlReport
    python3 scripts/detekt-rule-coverage.py

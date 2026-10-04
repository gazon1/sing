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
    ./gradlew :shared:commonTest

[group('tests')]
[doc('Run jvmTest (Room + SQLite on JVM)')]
[working-directory(justfile_directory())]
jvm:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :shared:jvmTest

[group('tests')]
[doc('Run testAndroidHostTest (Robolectric, no device needed)')]
[working-directory(justfile_directory())]
android-host:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :shared:testAndroidHostTest

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
[doc('Run ./gradlew test (all modules)')]
[working-directory(justfile_directory())]
test:
    #!/bin/bash
    set -euo pipefail
    ./gradlew test

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
    ./gradlew clean

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
    ./gradlew :shared:detekt :desktopApp:detekt

[group('lint')]
[doc('Auto-fix detekt style rules + ktlint formatting violations')]
[working-directory(justfile_directory())]
detekt-fix:
    #!/bin/bash
    set -euo pipefail
    # detekt 2.x merges formatting into the main task with autoCorrect=true in ktlint config
    ./gradlew :shared:detekt :desktopApp:detekt --auto-correct

[group('lint')]
[doc('Generate detekt baseline files (capture current violations)')]
[working-directory(justfile_directory())]
detekt-baseline:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :shared:detektBaseline :desktopApp:detektBaseline

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
    python3 scripts/check-doc-sizes.py --warn-only || status=1
    echo ""
    echo "=== Dead file references in docs, skills and KDoc ==="
    python3 scripts/check-doc-dead-refs.py || status=1
    echo ""
    echo "=== ADR frontmatter normalization (dry run) ==="
    ./scripts/normalize-adr-frontmatter.sh --dry-run 2>/dev/null || true
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
# Measures shared (commonMain + jvmMain + androidMain) + desktopApp
# ---------------------------------------------------------------------------

[group('coverage')]
[doc('Generate kover XML coverage reports')]
[working-directory(justfile_directory())]
coverage:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :shared:koverXmlReport :desktopApp:koverXmlReport

[group('coverage')]
[doc('Generate kover HTML coverage reports')]
[working-directory(justfile_directory())]
coverage-html:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :shared:koverHtmlReport :desktopApp:koverHtmlReport

[group('coverage')]
[doc('Coverage ratchet for feature/agenda: fail if line coverage drops below the baseline')]
[working-directory(justfile_directory())]
coverage-ratchet *args="":
    #!/bin/bash
    set -euo pipefail
    # Kover merges binary reports incrementally, so a report produced after other
    # runs carries coverage from tests outside the current filter. The baseline is
    # only reproducible from a clean state, hence the wipe. The alternative —
    # comparing against whatever the last run happened to leave — is not a gate.
    rm -rf shared/build/kover shared/build/reports/kover
    # `-Pkover.jvmTest=true` instruments jvmTest, which is off by default (see the
    # kover block in shared/build.gradle.kts). `-Pcoverage.tests` narrows the run:
    # the whole suite under instrumentation is both slow and memory-hungry, and
    # `--tests` on the command line is rejected when koverXmlReport is in the same
    # invocation.
    ./gradlew :shared:jvmTest :shared:koverXmlReport \
        -Pkover.jvmTest=true \
        -Pcoverage.tests="com.singularity.todo.feature.agenda.**" \
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

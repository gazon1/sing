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
    ./gradlew koverReport

[group('coverage')]
[doc('Generate the aggregated kover HTML coverage report')]
[working-directory(justfile_directory())]
coverage-html:
    #!/bin/bash
    set -euo pipefail
    ./gradlew koverHtml

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

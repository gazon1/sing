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
    SKIP_ADB={{SKIP_ADB}} ./check.sh
    just lint

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
    ./gradlew :shared:detekt :desktopApp:detekt

[group('lint')]
[doc('Auto-fix detekt style rules + ktlint formatting violations')]
[working-directory(justfile_directory())]
detekt-fix:
    #!/bin/bash
    set -euo pipefail
    # detekt 2.x merges formatting into the main task with autoCorrect=true in ktlint config
    ./gradlew :shared:detekt :desktopApp:detekt

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

# ---------------------------------------------------------------------------
# check — run tests + lint (report-only initially)
# ---------------------------------------------------------------------------

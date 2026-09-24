#!/usr/bin/env bash
# check-kdoc-coverage.sh — verify KDoc coverage for contract-bearing types.
#
# Categories enforced:
#   expect_actual  — expect/actual declarations
#   repository     — *Repository interfaces
#   viewmodel      — *ViewModel classes
#
# For each category, counts files with class-level KDoc and reports %.
# Exits 0 if coverage >= BASELINE%, otherwise exits 1 (warning mode, no gate).
#
# Usage:
#   ./scripts/check-kdoc-coverage.sh              # full check, exit 0/1
#   ./scripts/check-kdoc-coverage.sh --quick      # staged-files only

set -uo pipefail  # -e removed: failures are warnings, not gates

SRC="shared/src/commonMain/kotlin/com/singularity/todo"
ANDROID_SRC="shared/src/androidMain/kotlin/com/singularity/todo"
JVVM_SRC="shared/src/jvmMain/kotlin/com/singularity/todo"

# Baselines (% of files that must have class-level KDoc)
BASELINE_EXPECT_ACTUAL=80
BASELINE_REPOSITORY=90
BASELINE_VIEWMODEL=50

KDOC_RE='^\s*/\*\*'  # leading KDoc comment

count_kdoc() {
    local dir="$1"
    local pattern="$2"
    find "$dir" -name "$pattern" -type f 2>/dev/null | while read -r f; do
        # Look for class-level KDoc: /** within first 10 lines, before class/interface declaration
        head -15 "$f" | grep -q "$KDOC_RE" && echo "$f"
    done | wc -l
}

count_total() {
    local dir="$1"
    local pattern="$2"
    find "$dir" -name "$pattern" -type f 2>/dev/null | wc -l
}

check_category() {
    local label="$1"
    local dir="$2"
    local pattern="$3"
    local baseline="$4"

    total=$(count_total "$dir" "$pattern")
    with_kdoc=$(count_kdoc "$dir" "$pattern")
    pct=0
    if [ "$total" -gt 0 ]; then
        pct=$(( with_kdoc * 100 / total ))
    fi

    status="PASS"
    if [ "$pct" -lt "$baseline" ]; then
        status="FAIL"
    fi

    echo "[$status] $label: $with_kdoc/$total files with KDoc ($pct%, baseline $baseline%)"
    [ "$status" == "FAIL" ] && failed=1 || true
}

echo "=== KDoc Coverage Check ==="
failed=0
check_category "expect_actual" "$SRC" '*Port.kt' "$BASELINE_EXPECT_ACTUAL" || failed=1
check_category "repository" "$SRC" '*Repository.kt' "$BASELINE_REPOSITORY" || failed=1
check_category "viewmodel" "$SRC" '*ViewModel.kt' "$BASELINE_VIEWMODEL" || failed=1
# androidMain actuals
check_category "android_actual" "$ANDROID_SRC" '*.kt' 50 || failed=1
# jvmMain actuals
check_category "jvm_actual" "$JVVM_SRC" '*.kt' 50 || failed=1

echo ""
if [ "$failed" -eq 1 ]; then
    echo "Some categories below baseline — this is a warning, not a build gate."
fi

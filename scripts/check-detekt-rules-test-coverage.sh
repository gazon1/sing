#!/usr/bin/env bash
# check-detekt-rules-test-coverage.sh
# Blocks CI when a new detekt rule in detekt-rules/src/main/ has no corresponding test
# in detekt-rules/src/test/.  A rule "has a test" when any test file contains the rule's
# class name as a callable expression (positive control — instantiation or direct reference).
#
# Exits 0: all rules have tests   |  Exits 1: one or more rules are untested.

set -euo pipefail

MAIN_SRC="detekt-rules/src/main/kotlin/com/singularity/todo/detekt"
TEST_SRC="detekt-rules/src/test/kotlin/com/singularity/todo/detekt"

# ── 1. Collect rule class names from main source ──────────────────────────────
# A "rule class" = class that directly extends `Rule(`, but not a RuleSetProvider.
# We match the class declaration line to distinguish rules from providers.

# "Rule class" = class whose primary constructor extends Rule(.
# File-level match (not provider method that creates Rule instances):
RULE_CLASSES=$(grep -lE "^class \w+\(config: Config\) : Rule\(" "$MAIN_SRC"/*.kt 2>/dev/null || true)

if [[ -z "$RULE_CLASSES" ]]; then
    echo "check-detekt-rules-test-coverage: no rule classes found in $MAIN_SRC — nothing to check"
    exit 0
fi

# ── 2. For each rule class, check it appears in a test file ───────────────────
# We look for the bare class name (not the full package) as a positive reference:
# instantiation:  SomeRule(TestConfig())
# direct usage:   SomeRule(
# This filters out comments, strings, and suppress annotations.

UNTESTED=()
for src_file in $RULE_CLASSES; do
    # Extract the rule class name (strip package path and .kt extension)
    rule_name=$(basename "$src_file" .kt)

    # Skip a file only when it is a pure RuleSetProvider (no Rule class in the same file).
    # If a file contains BOTH a Rule and a Provider (e.g. NoStaticProfileAwareCurrentUser*
    # both declare NoStaticProfileAwareCurrentUserRule and NoStaticProfileAwareCurrentUserProvider),
    # the Rule still needs its test coverage checked.
    provider_line=$(grep -n "^class.*RuleSetProvider" "$src_file" 2>/dev/null || true)
    rule_line=$(grep -n "^class.*(config: Config) : Rule(" "$src_file" 2>/dev/null || true)
    if [[ -n "$provider_line" && -z "$rule_line" ]]; then
        continue  # pure provider file, no Rule in this file
    fi

    # Look for the rule name as a positive reference in test sources.
    # Use grep -l first for efficiency; require it appears as a call/dispatch.
    # Positive patterns:  SomeRule(   | new SomeRule(   | SomeRule.
    # We exclude suppress annotations and comments.
    found=$(grep -lE "^[^//]*\b${rule_name}\b[<(]" "$TEST_SRC"/*.kt 2>/dev/null || true)

    if [[ -z "$found" ]]; then
        UNTESTED+=("$rule_name")
    fi
done

# ── 3. Report ─────────────────────────────────────────────────────────────────
if [[ ${#UNTESTED[@]} -eq 0 ]]; then
    echo "check-detekt-rules-test-coverage: ALL RULE CLASSES HAVE TESTS  ✓"
    echo "  Checked: $(echo $RULE_CLASSES | wc -w) rule files"
    exit 0
else
    echo "check-detekt-rules-test-coverage: UNTESTED RULES FOUND  ✗"
    echo ""
    echo "The following rule classes have no corresponding test in $TEST_SRC/:"
    for r in "${UNTESTED[@]}"; do
        echo "  - $r"
    done
    echo ""
    echo "Every rule class must have at least one positive control test that"
    echo "instantiates or directly references it.  Add a smoke test in"
    echo "RuleFiresSmokeTest.kt or a dedicated *RuleTest.kt file."
    exit 1
fi

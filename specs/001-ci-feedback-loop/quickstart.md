# Quickstart: CI Feedback Loop Acceleration

**Feature**: `001-ci-feedback-loop`
**Date**: 2026-10-12

## What this document is

Runnable validation scenarios that prove each FR works end-to-end. Each scenario describes prerequisites, commands to run, and the expected outcome. No implementation code or full test suites — only commands and assertions.

---

## Scenario 0 — sgate CLI migration (prerequisite for all other scenarios)

**Prerequisite**: All gates in `static-gates.sh` use `sgate <command>` form.

**Validate**:
```bash
# List all gates via sgate
PYTHONPATH=scripts/src python3 -m sgate gates --json | python3 -c "
import json, sys
gates = json.load(sys.stdin)
python_gates = [g for g in gates if g['command'].startswith('python3 scripts/')]
print(f'Gates still using python3 scripts/: {len(python_gates)}')
for g in python_gates:
    print(f'  - {g[\"name\"]}: {g[\"command\"]}')
"

# Expected: 0 gates using raw python3 scripts/... form
```

**Expected outcome**: All gates use `sgate <command>` form. Zero output from the validation script above.

---

## Scenario 1 — Per-test GitHub annotation from JUnit XML

**FR**: FR-001 (JUnit XML → GitHub annotation per failing test)

**Prerequisites**:
- A local Gradle test run that produces JUnit XML
- GitHub CLI (`gh`) authenticated
- A GitHub repository with CI set up

**Setup**:
```bash
# Run a single failing test to produce JUnit XML
./gradlew :shared:jvmTest --tests "com.singularity.SomeTest.someFailingMethod" 2>&1 | tail -5
```

**Validate**:
```bash
# Parse the JUnit XML and check that script produces annotation lines
python3 scripts/junit-annotations.py \
  --xml shared/build/test-results/jvmTest/TEST-*.xml \
  --output-format dry-run
# Expected: prints ::error lines for each failing test case with Class.method and message
```

**Expected outcome**: Script outputs `::error title=<Class.method>::<message>` lines for each `<testcase>` with a `<failure>` child. Exit code 0.

---

## Scenario 2 — HTML report artifact upload

**FR**: FR-002 (HTML test reports as CI artifacts)

**Prerequisites**:
- CI run completed with test reports generated

**Validate**:
```bash
# Check that HTML reports exist in the expected Gradle output locations
find shared/build/reports/tests/test -name "index.html" | head -3
find desktopApp/build/reports/tests/test -name "index.html" | head -3
# Expected: paths exist

# In CI run artifact (after a CI run):
gh run view <run-id> --json artifacts --jq '.artifacts[] | select(.name == "html-reports")'
# Expected: artifact named "html-reports" contains **/reports/tests/test/**
```

**Expected outcome**: `html-reports` artifact exists in CI and contains test HTML reports.

---

## Scenario 3 — Path-filtered PR gating

**FR**: FR-003 (path filters on PR trigger)

**Prerequisites**:
- A GitHub PR open on the repository

**Validate**:
```bash
# Open a PR that only touches scripts/**
gh pr create --title "chore: update ci script" --body "." \
  --head "$(git branch --show-current)" \
  --base main \
  --draft
git commit --allow-empty -m "chore: update ci script" -m "---" -m "docs: update ci doc"
git push

# Check which jobs ran in CI
gh run list --workflow ci.yml --limit 5 --json name,status,conclusion
# Expected: only "Static gates" job ran; "JVM tests" and "Android" jobs did not run
```

**Expected outcome**: CI shows only `static` job ran for a `scripts/**`-only PR (under 5 minutes). `tests` and `android` jobs are skipped.

---

## Scenario 4 — Preflight environment validation

**FR**: FR-004 (preflight composite action, path-gated)

**Prerequisites**:
- `.github/actions/setup-repo-tools/action.yml` exists
- `singularity-adr` not on PATH (simulate by renaming it)

**Validate locally**:
```bash
# Simulate missing tool
export PATH="${PATH/$(which singularity-adr)/}"

# Run preflight check
bash .github/actions/setup-repo-tools/action.yml

# Expected: exits 1 within 5 seconds with message about singularity-adr not found
# Actual: echo "::error title=Preflight failed::singularity-adr not found on PATH"
```

**Validate in CI**:
```bash
# Open a PR touching only scripts/**
# CI preflight step should run and pass (or fail if tools missing)
gh run view <run-id> --json steps --jq '.steps[] | select(.name == "Preflight environment")'
# Expected: step exists, status completed, conclusion success
```

**Expected outcome**: Preflight runs for `scripts/**` PRs, fails fast with tool name, skips for `shared/src/**/*.kt` PRs.

---

## Scenario 5 — Desktop test retry and visible retry logging

**FR**: FR-005 (retry + retry-pass annotation)

**Prerequisites**:
- Desktop test suite runs with retry configured
- A deliberately flaky test or a known flaky test (e.g., `TagsRenameUiTest`)

**Validate**:
```bash
# Run desktop tests with retry
./gradlew :desktopApp:test -Dretry.maxAttempts=2 2>&1 | grep -i "retry\|passed_on_retry\|TagsRename"

# Check JUnit XML for duplicate entries (both failed attempts)
grep -c "testcase.*TagsRenameUiTest" desktopApp/build/test-results/test/*.xml
# If 2: both attempts recorded (persistent failure)
# If 1: passed on retry (single final attempt)

# Check CI output for retry annotation
gh run view <run-id> --json steps --jq '.steps[] | select(.name | contains("retry"))'
# Expected: step log contains "Passed on retry: N test(s)" warning annotation
```

**Expected outcome**: Transient failure → check green with retry warning annotation. Persistent failure → screenshot artifact uploaded.

---

## Scenario 6 — Full CI gate on main branch

**FR**: All FRs — integrated end-to-end

**Prerequisites**:
- All changes merged to `main`

**Validate**:
```bash
# Trigger nightly CI
gh workflow run ci.yml --ref main

# Wait for completion
gh run watch

# Check all required checks passed
gh run view <run-id> --json status,conclusion,requiredChecks
# Expected: status completed, conclusion success, all required checks passed
```

**Expected outcome**: Full matrix CI runs on `main`, completes in expected time, all gates pass.

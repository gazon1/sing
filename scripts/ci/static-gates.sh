#!/usr/bin/env bash
# Single registry of every gate that needs no JVM and no build output.
#
# Every gate runs concurrently.  Results are collected after all workers finish,
# so one push shows ALL problems at once — not just the first one.
#
# `ci.yml`'s `static` job and `check.sh` both call this file, so a gate cannot
# exist in one place and be forgotten in the other.
#
#   gate <blocking|advisory> <name> <command...>
#
# `advisory` is the only way to be non-blocking.  `|| true` is not an alternative:
# Part D of scripts/check-gate-wiring.py rejects it.
set -uo pipefail
cd "$(dirname "$0")/../.."

# ─── Temp dir for all results ────────────────────────────────────────────────
RESULTS_DIR=$(mktemp -d)
trap 'rm -rf "$RESULTS_DIR"' EXIT

# ─── Shared state (written by background workers, read at the end) ───────────
declare -a ALL_NAMES=()     # all gate display names (order of declaration)
declare -A GATE_MODE=()     # name → blocking|advisory
declare -A GATE_RC=()        # name → exit code
declare -A GATE_OUT=()       # name → output file path

# ─── Gate helper ─────────────────────────────────────────────────────────────
# Each invocation starts one background subshell and records its name/mode.
# Output goes to a temp file; exit code is written there too.
gate() {
  local mode=$1 name=$2
  shift 2
  local slug
  slug=$(echo "$name" | sed 's/[^a-zA-Z0-9]/_/g' | tr '[:upper:]' '[:lower:]')

  local out_file="${RESULTS_DIR}/${slug}.out"
  local rc_file="${RESULTS_DIR}/${slug}.rc"

  ALL_NAMES+=("$name")
  GATE_MODE["$name"]=$mode
  GATE_OUT["$name"]=$out_file

  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::group::$name"
  fi

  # Run in subshell so set -e does not kill the driver script.
  # `set +e` inside ensures the command's rc is captured, not propagated.
  (
    set +e
    "$@" >"$out_file" 2>&1
    local rc=$?
    echo "$rc" >"$rc_file"
  ) &

  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::endgroup::"
  fi
}

# ─── Wait and report ────────────────────────────────────────────────────────
collect_and_report() {
  local -a FAILED=() WARNED=() PASSED=()

  # Wait for every background subshell to finish.
  # `wait` without arguments waits for all current children.
  while [[ $(jobs -r | wc -l) -gt 0 ]]; do
    sleep 0.1
  done

  # Now read every result file.
  for name in "${ALL_NAMES[@]}"; do
    local slug
    slug=$(echo "$name" | sed 's/[^a-zA-Z0-9]/_/g' | tr '[:upper:]' '[:lower:]')
    local rc_file="${RESULTS_DIR}/${slug}.rc"
    local out_file="${GATE_OUT["$name"]}"
    local mode=${GATE_MODE["$name"]}

    local rc=0
    if [[ -f "$rc_file" ]]; then
      rc=$(cat "$rc_file")
    fi

    if ((rc == 0)); then
      PASSED+=("$name")
    elif [[ $mode == blocking ]]; then
      FAILED+=("$name")
      if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
        echo "::error title=Gate failed::$name (exit $rc)"
      fi
      # Show failure output for blocking gates
      if [[ -f "$out_file" ]]; then
        echo "--- $name (exit $rc) ---"
        cat "$out_file" | head -20
        echo
      fi
    else
      WARNED+=("$name")
      if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
        echo "::warning title=Advisory gate failed::$name"
      fi
    fi
  done

  echo
  echo "gates: ${#PASSED[@]} passed, ${#FAILED[@]} failed, ${#WARNED[@]} advisory-failed"
  for n in "${FAILED[@]}"; do echo "  ❌ $n"; done
  for n in "${WARNED[@]}"; do echo "  ⚠️  $n (advisory)"; done

  if [[ -n ${GITHUB_STEP_SUMMARY:-} ]]; then
    {
      echo "### Static gates"
      echo "${#PASSED[@]} passed · ${#FAILED[@]} failed · ${#WARNED[@]} advisory"
      for n in "${FAILED[@]}"; do echo "- ❌ $n"; done
      for n in "${WARNED[@]}"; do echo "- ⚠️ $n (advisory)"; done
    } >>"$GITHUB_STEP_SUMMARY"
  fi

  ((${#FAILED[@]} == 0))
}

# ─── Gate helpers ────────────────────────────────────────────────────────────
# Multi-command gates defined as exported functions so they can be passed
# as the command to `gate`.  Each must be `export -f` so bash -c sees it.

digest_and_sizes() {
  python3 scripts/refresh-decisions-digest.py && python3 scripts/check-doc-sizes.py
}
export -f digest_and_sizes

digest_no_double_suffix() {
  local n
  n=$(grep -c "_(from " docs/decisions/DIGEST.md 2>/dev/null || echo 0)
  if ((n > 100)); then
    echo "DIGEST.md has $n '_(from ' occurrences — double-suffix bug"
    return 1
  fi
}
export -f digest_no_double_suffix

adr_dry_run() {
  ./scripts/normalize-adr-frontmatter.sh
}
export -f adr_dry_run

traceability() {
  PYTHONPATH=infra/kiwi python3 -m traceability "$@"
}
export -f traceability

# ─── Registry ────────────────────────────────────────────────────────────────

# Build hygiene
gate blocking "version catalog has no literals" python3 scripts/build-version-catalog-gate.py --quiet .
gate blocking "gate scripts' own unit tests" python3 -m unittest discover -s scripts/tests
gate blocking "test-task external inputs declared" python3 scripts/check-test-task-inputs.py
gate blocking "declared dependencies are used" python3 scripts/check-dependency-usage.py
gate blocking "room schema integrity" python3 scripts/check-room-schema-integrity.py
gate advisory "unwired surfaces" python3 scripts/find-unwired-surfaces.py --quiet
gate blocking "unwired backlog refs" python3 scripts/check-unwired-backlog-refs.py
gate blocking "settings read by a feature" python3 scripts/check-dead-settings.py --quiet
gate blocking "YAML has no duplicate keys" python3 scripts/check-yaml-duplicate-keys.py --quiet

# Lint-rule governance
gate blocking "detekt rules config is current" python3 scripts/gen-detekt-rules-config.py --check
gate blocking "detekt rule inventory is current" python3 scripts/gen-detekt-rule-table.py --check
gate blocking "detekt rule registry" ./scripts/check-detekt-registrations.sh
gate blocking "detekt baseline ratchet" python3 scripts/check-baseline-ratchet.py
gate blocking "lint rules are declared decisions" python3 scripts/check-rule-intent.py
gate blocking "file-level suppressions explain themselves" python3 scripts/check-suppression-intent.py
# detekt-rules branch coverage floor: 60% (below 2026-10-10 measured 65.1%).
# This gate runs in ci.yml's `tests` job (where Gradle is available), not here.
# See gate "detekt-rule branch coverage" and "kover verify" in ci.yml.

# Scenario traceability
gate blocking "scenario specs valid" traceability validate
gate blocking "coverage matrix current" traceability coverage --check
gate blocking "coverage holes did not grow" python3 scripts/check-traceability-ratchet.py
gate advisory "kiwi inventory did not regress" python3 scripts/check-kiwi-inventory-ratchet.py

# Server schema
gate blocking "supabase schema is self-consistent" python3 scripts/check-supabase-schema-integrity.py
gate blocking "sync allowlist seed matches contract" python3 scripts/check-sync-allowlist-regenerated.py

# Maestro
gate blocking "maestro selectors valid" python3 scripts/ci/validate-maestro-selectors.py

# Docs / agent-facing text
gate advisory "skills catalog current" ./scripts/regen-skills-catalog.sh --check
gate blocking "skill frontmatter" ./scripts/check-skill-frontmatter.sh
gate blocking "decisions digest within budget" digest_and_sizes
gate blocking "digest has no double suffix" digest_no_double_suffix
gate advisory "no dead doc refs" python3 scripts/check-doc-dead-refs.py
gate blocking "no dead skill symbols" python3 scripts/check-doc-dead-refs.py --skill-symbols
gate advisory "publication hygiene" python3 scripts/check-publication-hygiene.py
gate blocking "publication hygiene self-test" python3 scripts/check-publication-hygiene.py --self-test
gate blocking "README claims" python3 scripts/check-readme-claims.py
gate blocking "README claims self-test" python3 scripts/check-readme-claims.py --self-test

# NOT HERE: scripts/check-gate-wiring.py
# See the ADR for this gate and the note in scripts/check-rebase-compiles.py.
#
# It is tempting to call the meta-gate from the registry, and it is wrong. Part B
# proves `check-test-runs.py` can fail by sabotaging config/docs/test-runs-baseline.txt
# and re-running it — and that gate reads JUnit XML which only exists once the test
# tasks have run. The registry is static by definition: in CI's `static` job there is
# no build output at all. Calling it from here reproduces #206 exactly, failing with
# "already fails on a clean tree" — a true statement that reads as a false alarm.
#
# A gate that proves another gate works inherits that gate's preconditions and runs
# them at its own time. So the meta-gate stays where the results are: after the tests,
# in check.sh and in ci.yml's `tests` job.
#
# ADR reference integrity and status policy — these make ADR deletion safe (#116).
gate blocking "ADR references are valid" python3 scripts/check-adr-references.py
gate blocking "ADR status policy" python3 scripts/check_adr_status.py

# Five consumers discovered docs/decisions/ five different ways and had already
# drifted apart — three could not see deferred/, which is how the status gate
# reported "all statuses in vocabulary" while 129 files used a vocabulary of their
# own. The rules live in config/docs/adr-corpus.json; this gate keeps every consumer
# reading them from there, and keeps the Kotlin fallback equal to the JSON (#520).
gate blocking "ADR corpus rules are shared" python3 scripts/check-adr-config-sync.py

# Advisory — each needs an exit plan, or it is just a quieter blocking gate.
# Exit plan: run ./scripts/normalize-adr-frontmatter.sh --apply once, commit,
# then flip ADR frontmatter drift to blocking.
gate advisory "ADR frontmatter drift" adr_dry_run
gate advisory "openspec stale" python3 scripts/check-openspec-stale.py
gate advisory "requirement identifiers are unique" python3 scripts/check-req-id-uniqueness.py

# AppTracer
gate blocking "apptracer upload policy encoded" python3 scripts/check-apptracer-policy.py

# Backlog
gate advisory "backlog-issue-refs" python3 scripts/check-backlog-issue-refs.py

# ─── Collect all results and report ─────────────────────────────────────────
collect_and_report

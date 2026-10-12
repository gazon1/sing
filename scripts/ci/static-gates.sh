#!/usr/bin/env bash
# Pure registry: every gate that needs no JVM and no build output.
#
# IMPORTANT: this file is NO LONGER executed as a script by CI.
# - CI's `static` job calls: sgate run-all
# - check.sh calls: sgate run-all
# - sgate run-all READS this file to get the gate registry, then executes gates.
#
# The gate() function below still works for local use:
#   bash scripts/ci/static-gates.sh --verbose   (runs all gates locally)
#
# A gate cannot exist in one place and be forgotten here — both sgate run-all
# and check.sh consume the same registry.
#
#   gate <blocking|advisory> <name> <command...>
#
# `advisory` is the only way to be non-blocking.  `|| true` is not an alternative:
# Part D of scripts/check-gate-wiring.py rejects it.
set -uo pipefail
# Use BASH_SOURCE[0] (not $0) so cd works correctly both when this file is:
#   1. executed directly  ($0 = /path/to/static-gates.sh)
#   2. sourced           ($0 = bash, BASH_SOURCE[0] = /path/to/static-gates.sh)
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

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
      # Show failure output for blocking gates.  Full output, not a window:
      # unittest's epilogue (stderr) can sit thousands of lines above the
      # block-buffered stdout flush that ends the file, and a head/tail
      # window hid the failing test names in CI (#563).  The 5000-line cap
      # is a runaway guard, not a diagnostic cut.
      if [[ -f "$out_file" ]]; then
        local total_lines
        total_lines=$(wc -l <"$out_file")
        echo "--- $name (exit $rc) ---"
        if ((total_lines > 5000)); then
          head -100 "$out_file"
          echo "… ($((total_lines - 500)) lines omitted) …"
          tail -400 "$out_file"
        else
          cat "$out_file"
        fi
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
# All gate calls are INSIDE main() so that sourcing this file (to call one of the
# helper functions: digest_and_sizes, digest_no_double_suffix, adr_dry_run,
# traceability) does NOT execute all gates as a side-effect.
# The `|| [[ $? == 0 ]]` guard at the end of this file ensures main() is NOT called
# when sourced — only when the script runs as a command.
main() {
# Build hygiene
gate blocking "version catalog has no literals" python3 scripts/build-version-catalog-gate.py --quiet .
# -v: a failure prints its name the moment it happens, and a run that dies
# without an epilogue (CI, 2026-10-11) still shows the last test it reached —
# dots-only output ends mid-stream with no way to tell where.
# The sentinel proves whether the interpreter reached normal termination;
# unittest's epilogue goes to stderr while script print()s sit in the
# block-buffered stdout, so the file's last lines can be stale stdout alone.
gate blocking "gate scripts' own unit tests" bash -c 'python3 -m unittest discover -s scripts/tests -v; rc=$?; echo "__unittest_rc=$rc"; exit $rc'
gate blocking "test-task external inputs declared" python3 scripts/check-test-task-inputs.py
gate blocking "declared dependencies are used" python3 scripts/check-dependency-usage.py
gate blocking "room schema integrity" python3 scripts/check-room-schema-integrity.py
# A gate that dies instead of reporting is indistinguishable from a gate that
# passes, for every consumer that reads only the exit code. This found a real
# one on its first run: check-kiwi-inventory-ratchet.py crashed with
# "NoneType has no attribute loader" and had been dead since #430 (#529).
# A gate that probes all registered gates must not probe itself — doing so would
# launch 37 gates, one of which launches 37 more, and the first version of this
# gate died of its own timeout (#529). The `# no-self-probe` comment marks gates
# that must not be probed; the filter is applied in check-gate-liveness.py, not
# in the filename heuristic that previously lived there (#534).
gate blocking "gates are alive" python3 scripts/check-gate-liveness.py  # no-self-probe
# A closed decision is a record of what was decided. Its body must stop changing;
# a new decision supersedes it instead of rewriting it (#528).
gate blocking "closed ADRs keep their body" python3 scripts/check-adr-immutable.py
# A skill is a hand-written description of code nobody diffs against it, so it is
# always the stale one and always reads like documentation rather than like a bug.
# The "ADR tools" section is generated from AdrSurface.kt and McpToolCatalog.kt;
# this compares the committed text with what the code says today (#526).
gate blocking "skills describe the tools that exist" python3 scripts/check-skill-drift.py
# An absolute core.hooksPath is correct in exactly one checkout and silently wrong in
# every worktree, and git config does not travel with the repo (#519).
gate advisory "git hooks path is per-worktree" python3 scripts/check-hooks-path.py --quiet
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

# Scenario traceability (Kiwi)
# traceability validate/coverage require PYTHONPATH=infra/kiwi python3 -m traceability.
# Call via sgate CLI so KIWI_REQUIRED logic returns exit 4 (skip) when kiwi is absent,
# instead of a hard exit 127 that would falsely fail the blocking gate.
gate blocking "scenario specs valid" sgate scenario_specs_valid
gate blocking "coverage matrix current" sgate coverage_matrix_current
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
# #553 switched this gate to `singularity-adr validate` (the canonical CLI
# interface), but the static job is deliberately JVM-free (~1 min, no Gradle):
# the installed CLI is not on a CI runner's PATH, so the gate died with exit
# 127 and dragged "gates are alive" down with it (1 unrunnable).  Both surfaces
# are kept where they can actually run: the Python gate here (the same code
# the CLI wraps, per #553), the CLI itself in adr-gate.yml's "ADR validation"
# step, which installs the distribution and runs on every docs/decisions PR.
gate blocking "ADR status policy" python3 scripts/check_adr_status.py

# Five consumers discovered docs/decisions/ five different ways and had already
# drifted apart — three could not see deferred/, which is how the status gate
# reported "all statuses in vocabulary" while 129 files used a vocabulary of their
# own. The rules live in config/docs/adr-corpus.json; this gate keeps every consumer
# reading them from there, and keeps the Kotlin fallback equal to the JSON (#520).
gate blocking "ADR corpus rules are shared" python3 scripts/check-adr-config-sync.py

# `:adr-cli` shipped 127 MB and 0.30 s of startup to read markdown files, because
# `adr-cli/build.gradle.kts` depended on `:shared` — Compose, Room, SQLite, Koin and
# Koog. The rules moved to `:adr-core`; this keeps them there, transitively, so a
# one-line dependency does not put the application runtime back on a 4.9 MB
# distribution with every test still green.
gate blocking "light ADR modules stay light" python3 scripts/check-adr-module-boundary.py --quiet

# Advisory — each needs an exit plan, or it is just a quieter blocking gate.
# Exit plan: run ./scripts/normalize-adr-frontmatter.sh --apply once, commit,
# then flip ADR frontmatter drift to blocking.
gate advisory "ADR frontmatter drift" sgate adr_frontmatter_drift
gate advisory "openspec stale" python3 scripts/check-openspec-stale.py
gate advisory "spec-kit issue registry current" python3 scripts/check-speckit-issue-registry.py
gate advisory "requirement identifiers are unique" python3 scripts/check-req-id-uniqueness.py

# AppTracer
gate blocking "apptracer upload policy encoded" python3 scripts/check-apptracer-policy.py

# Backlog
gate advisory "backlog-issue-refs" sgate backlog_issue_refs

# ─── Collect all results and report ─────────────────────────────────────────
collect_and_report
}  # end main()

# Guard: run main() ONLY when this script is executed directly (not sourced).
# When sourced (e.g. by sgate run-all to call helper functions like
# digest_no_double_suffix), only function definitions are loaded.
# STATIC_GATES_SOURCED=1 signals "load only, don't run gates".
if [[ "${STATIC_GATES_SOURCED:-}" != "1" ]]; then
  main "$@"
fi

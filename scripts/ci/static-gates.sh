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



traceability() {
  python3 -m traceability "$@"
}
export -f traceability

# ─── Registry ────────────────────────────────────────────────────────────────
# All gate calls are INSIDE main() so that sourcing this file (to call one of the
# helper functions: digest_no_double_suffix, adr_dry_run,
# traceability) does NOT execute all gates as a side-effect.
# The `|| [[ $? == 0 ]]` guard at the end of this file ensures main() is NOT called
# when sourced — only when the script runs as a command.
main() {
# Build hygiene
gate blocking "version catalog has no literals" sgate version_catalog_has_no_literals
# -v: a failure prints its name the moment it happens, and a run that dies
# without an epilogue (CI, 2026-10-11) still shows the last test it reached —
# dots-only output ends mid-stream with no way to tell where.
# The sentinel proves whether the interpreter reached normal termination;
# unittest's epilogue goes to stderr while script print()s sit in the
# block-buffered stdout, so the file's last lines can be stale stdout alone.
gate blocking "gate scripts' own unit tests" bash -c 'python3 -m unittest discover -s scripts/tests -v; rc=$?; echo "__unittest_rc=$rc"; exit $rc'
gate blocking "test-task external inputs declared" sgate test_task_external_inputs_declared
gate blocking "declared dependencies are used" sgate declared_dependencies_are_used
gate blocking "room schema integrity" sgate room_schema_integrity
# A gate that dies instead of reporting is indistinguishable from a gate that
# passes, for every consumer that reads only the exit code. This found a real
# one on its first run: check-kiwi-inventory-ratchet.py crashed with
# "NoneType has no attribute loader" and had been dead since #430 (#529).
# A gate that probes all registered gates must not probe itself — doing so would
# launch 37 gates, one of which launches 37 more, and the first version of this
# gate died of its own timeout (#529). The `# no-self-probe` comment marks gates
# that must not be probed; the filter is applied in check-gate-liveness.py, not
# in the filename heuristic that previously lived there (#534).
gate blocking "gates are alive" sgate gates_are_alive  # no-self-probe
# A skill is a hand-written description of code nobody diffs against it, so it is
# always the stale one and always reads like documentation rather than like a bug.
# The "ADR tools" section is generated from AdrSurface.kt and McpToolCatalog.kt;
# this compares the committed text with what the code says today (#526).
gate blocking "skills describe the tools that exist" sgate skills_describe_the_tools_that_exist
# An absolute core.hooksPath is correct in exactly one checkout and silently wrong in
# every worktree, and git config does not travel with the repo (#519).
gate advisory "git hooks path is per-worktree" sgate git_hooks_path_is_per_worktree
gate advisory "unwired surfaces" sgate unwired_surfaces
gate blocking "settings read by a feature" sgate settings_read_by_a_feature

# Workflow + YAML lint, two real tools over the corpus the gates above only
# parse. actionlint checks GitHub Actions *semantics* — expressions, action
# inputs, and script-injection: on 2026-10-15 it flagged adr-gate.yml for
# splicing the attacker-controlled PR title straight into a `run:` block
# (`TITLE="${{ github.event.pull_request.title }}"`), which no in-repo gate
# caught. yamllint re-parses every tracked YAML with ruamel, a stricter parser
# than the PyYAML the duplicate-key gate uses, so a defect one parser accepts
# silently still fails here. Its config (.yamllint.yml) is structure-only: the
# default profile was 213/220 line-length noise, and a gate that is red on the
# day it lands is a gate nobody reads. Both binaries must be on PATH — CI's
# static job installs them (pinned: actionlint 1.7.7, yamllint 1.38.0) through
# .github/actions/setup-python-tools; a missing binary is a loud 127, not a
# skip, because an unreachable gate is worse than an absent one.
#
# shellcheck is named explicitly, and its absence is a failure. actionlint
# DISABLES its shellcheck integration when the binary is not found, without
# saying so — so the gate's verdict silently depended on the image: green on a
# developer machine with no shellcheck, red on a runner that has one, same code.
# `command -v` turns that invisibility into a message. One line, because the
# registry parser is a single-line regex: a gate command split across lines is
# read as a command ending in an unterminated quote.
gate blocking "GitHub workflows are valid" bash -c 'command -v shellcheck >/dev/null || { echo "shellcheck is not on PATH; actionlint would silently skip its shellcheck pass and lint less than CI does" >&2; exit 1; }; actionlint -shellcheck="$(command -v shellcheck)"'
gate blocking "YAML is structurally valid" bash -c "git ls-files -z -- '*.yml' '*.yaml' | xargs -0 -r yamllint -c .yamllint.yml"

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

# Lint-rule governance
gate blocking "detekt rules config is current" sgate detekt_rules_config_is_current --check
gate blocking "detekt rule inventory is current" sgate detekt_rule_inventory_is_current --check
gate blocking "detekt rule registry" ./scripts/check-detekt-registrations.sh
gate blocking "detekt baseline ratchet" sgate detekt_baseline_ratchet
gate blocking "lint rules are declared decisions" sgate lint_rules_are_declared_decisions
gate blocking "file-level suppressions explain themselves" sgate file_level_suppressions_explain_themselves
# detekt-rules branch coverage floor: 60% (below 2026-10-10 measured 65.1%).
# This gate runs in ci.yml's `tests` job (where Gradle is available), not here:
# it is the Gradle task `:detekt-rules:koverVerify`, named by ci.yml's
# "Kover verify (detekt-rules)" step and by check.sh step 9b. The floor itself
# lives in detekt-rules/build.gradle.kts (DETEKT_RULE_BRANCH_FLOOR) as a Kover
# verify rule; Part C of check-gate-wiring.py holds it to a usable value.

# Scenario traceability
# traceability validate/coverage resolve the installed `traceability` package from
# scripts/src/ (installed by `pip install -e scripts/src/`), so there is no
# PYTHONPATH to set and no stand to start.
gate blocking "scenario specs valid" sgate scenario_specs_valid
gate blocking "coverage matrix current" sgate coverage_matrix_current
gate blocking "coverage holes did not grow" sgate coverage_holes_did_not_grow

# Server schema
gate blocking "supabase schema is self-consistent" sgate supabase_schema_is_self_consistent
gate blocking "sync allowlist seed matches contract" sgate sync_allowlist_seed_matches_contract

# Maestro
gate blocking "maestro selectors valid" sgate maestro_selectors_valid

# AppTracer

# Gate-script conventions. Advisory because the corpus is knowingly red: every
# legacy check-*.py still uses argparse (R2.1), and migrating 35 scripts is a
# separate sweep. Registered rather than deleted so the debt is visible and
# Part H of check-gate-wiring.py stops reporting check-script-conventions.py
# as an unreachable gate — a script nobody invokes reports success forever.
gate advisory "script conventions" sgate conventions

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

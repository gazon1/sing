#!/usr/bin/env bash
# Single registry of every gate that needs no JVM and no build output.
#
# `ci.yml`'s `static` job and `check.sh` both call this file, so a gate cannot
# exist in one place and be forgotten in the other. Adding a gate = adding one
# `gate` line below, and it is then enforced in both environments.
#
# Unlike a chain of workflow steps, every gate runs even if an earlier one fails,
# so one push shows all the problems instead of the first one.
#
#   gate <blocking|advisory> <name> <command...>
#
# `advisory` is the only way to be non-blocking, and it shows up as a warning
# annotation and in the summary. `|| true` is not an alternative: Part D of
# scripts/check-gate-wiring.py rejects it here, so a gate cannot be quietly
# softened by pasting an idiom instead of declaring the mode.
set -o pipefail
cd "$(dirname "$0")/../.."

PASSED=() FAILED=() WARNED=()
in_ci="${GITHUB_ACTIONS:-}"

gate() {
  local mode=$1 name=$2 rc=0
  shift 2
  [[ -n $in_ci ]] && echo "::group::$name"
  "$@" || rc=$?
  [[ -n $in_ci ]] && echo "::endgroup::"
  if ((rc == 0)); then
    PASSED+=("$name")
  elif [[ $mode == blocking ]]; then
    FAILED+=("$name")
    [[ -n $in_ci ]] && echo "::error title=Gate failed::$name (exit $rc)"
  else
    WARNED+=("$name")
    [[ -n $in_ci ]] && echo "::warning title=Advisory gate failed::$name"
  fi
}

# --- multi-command gates as functions (no `bash -c` quoting) -----------------
digest_and_sizes() {
  # DIGEST.md is generated and gitignored (.gitignore:119), so on a fresh
  # checkout it does not exist and a bare size check would skip it. Generate
  # first, then measure what the generator produces today — which is the thing
  # that can regress.
  #
  # ORDER MATTERS: `digest_no_double_suffix` below reads the file this writes.
  python3 scripts/refresh-decisions-digest.py && python3 scripts/check-doc-sizes.py
}
digest_no_double_suffix() {
  # `grep -c ... || echo 0` prints "0" twice when nothing matches; `|| true`
  # does not. It is kept local because Part D only bans `|| true` on gate
  # invocations, and here the point is to capture a count, not a verdict.
  local n
  n=$(grep -c "_(from " docs/decisions/DIGEST.md || true)
  if ((${n:-0} > 100)); then
    echo "DIGEST.md has $n '_(from ' occurrences — double-suffix bug"
    return 1
  fi
}
adr_dry_run() {
  # No `--dry-run` flag: the script execs its Python half with "$@" and is
  # already dry-run unless --apply is passed.
  ./scripts/normalize-adr-frontmatter.sh
}
traceability() { PYTHONPATH=infra/kiwi python3 -m traceability "$@"; }

# --- the registry ------------------------------------------------------------
# Build hygiene
gate blocking "version catalog has no literals" python3 scripts/build-version-catalog-gate.py --quiet .
gate blocking "gate scripts' own unit tests" python3 -m unittest discover -s scripts/tests
gate blocking "test-task external inputs declared" python3 scripts/check-test-task-inputs.py
gate blocking "declared dependencies are used" python3 scripts/check-dependency-usage.py
gate blocking "room schema integrity" python3 scripts/check-room-schema-integrity.py
# Advisory — finding code that is fully implemented but never called is useful
# signal but does not affect build correctness or test reliability.
gate advisory "unwired surfaces" python3 scripts/find-unwired-surfaces.py --quiet
gate blocking "unwired backlog refs" python3 scripts/check-unwired-backlog-refs.py
gate blocking "settings read by a feature" python3 scripts/check-dead-settings.py --quiet
# A YAML mapping that repeats a key is either a hard parse error (SnakeYAML) or
# silently last-one-wins (Go, hand-rolled readers). Which one you get depends on
# the consumer, and the consumer is whichever single tool parses that file — so
# nothing at edit time objects. Shipped as 1dbff67f: detekt-rules-module.yml had
# two `Filename:` keys and :detekt-rules:detekt stopped loading its config while
# :shared and :desktopApp, which have their own, kept reporting clean.
#
# This also replaces check.sh's inline "workflow YAML parses" step, which globbed
# `.github/workflows/*.yml` and used plain `safe_load` — so it missed a duplicate
# key (last-one-wins, reported clean) and covered 3 of the tree's 125 YAML files.
# Two checks of different strength on one file is the same defect one level down.
gate blocking "YAML has no duplicate keys" python3 scripts/check-yaml-duplicate-keys.py --quiet

# Lint-rule governance
gate blocking "detekt rules config is current" python3 scripts/gen-detekt-rules-config.py --check
gate blocking "detekt rule inventory is current" python3 scripts/gen-detekt-rule-table.py --check
gate blocking "detekt rule registry" ./scripts/check-detekt-registrations.sh
gate blocking "detekt baseline ratchet" python3 scripts/check-baseline-ratchet.py
gate blocking "lint rules are declared decisions" python3 scripts/check-rule-intent.py
gate blocking "file-level suppressions explain themselves" python3 scripts/check-suppression-intent.py

# Scenario traceability (static half; results are normalised in the tests job)
gate blocking "scenario specs valid" traceability validate
gate blocking "coverage matrix current" traceability coverage --check
gate blocking "coverage holes did not grow" python3 scripts/check-traceability-ratchet.py
# Advisory — Kiwi TCMS stand is currently unused (recipe commented out in .just/kiwi/mod.just).
# Demoted to advisory: owner gazon1, issue to be filed, review after reactivation.
gate advisory "kiwi inventory did not regress" python3 scripts/check-kiwi-inventory-ratchet.py

# Server schema. The live half of #221 needs credentials and stays manual; this is the
# half that can be a gate, and it is what makes the header's claim checkable at all.
gate blocking "supabase schema is self-consistent" python3 scripts/check-supabase-schema-integrity.py
# sync_field_allowlist_seed.sql must be regenerated whenever SyncContract.FIELD_ALLOWLIST changes.
gate blocking "sync allowlist seed matches contract" python3 scripts/check-sync-allowlist-regenerated.py

# Maestro selector validation — catches unknown id: selectors before they reach E2E CI.
# Uses the same Python script that E2E shard runs; advisory because Maestro YAML
# selector drift is a pre-existing issue not introduced by recent changes (#334 fix
# is in flight; larger MenuBottomSheet refactor #337 is separate).
gate blocking "maestro selectors valid" python3 scripts/ci/validate-maestro-selectors.py

# Docs / agent-facing text (formerly docs-audit.yml)
# Advisory — skills catalog drift is informational and does not affect builds.
# Flip to blocking once the catalog auto-regeneration is reliable.
gate advisory "skills catalog current" ./scripts/regen-skills-catalog.sh --check
gate blocking "skill frontmatter" ./scripts/check-skill-frontmatter.sh
gate blocking "decisions digest within budget" digest_and_sizes
gate blocking "digest has no double suffix" digest_no_double_suffix
# Advisory — dead doc references are informational and do not affect builds.
gate advisory "no dead doc refs" python3 scripts/check-doc-dead-refs.py
gate blocking "no dead skill symbols" python3 scripts/check-doc-dead-refs.py --skill-symbols
# Advisory — publication hygiene violations are informational and do not affect builds.
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
# Advisory — each needs an exit plan, or it is just a quieter blocking gate.
# Exit plan: run ./scripts/normalize-adr-frontmatter.sh --apply once, commit,
# then flip ADR frontmatter drift to blocking.
gate advisory "ADR frontmatter drift" adr_dry_run
gate advisory "openspec stale" python3 scripts/check-openspec-stale.py

# Advisory — exit plan recorded in the script's "Known instances" section.
# It is red today: `log-export-surface` and `add-log-export` both ADD REQ-LE-001..004, one
# requirement written twice in two folders. That pair is reconciled on its own; when it is,
# this becomes blocking and the `advisory` word goes with it.
gate advisory "requirement identifiers are unique" python3 scripts/check-req-id-uniqueness.py

# AppTracer (#387): release builds must not upload outside CI. The policy is encoded
# structurally in pro/build.gradle.kts (isCi gate) and documented in the ADR. This
# gate asserts the structure is present — a comment is not a gate.
gate blocking "apptracer upload policy encoded" python3 scripts/check-apptracer-policy.py

# Advisory — the backlog→issues gate. I1/I2 are blocking (cite to a non-existent issue,
# or open entry tracking a closed one). I4 (open issue missing Backlog: field) is
# advisory because the convention is new and existing open issues do not yet carry the field.
# When the 11 real I2 violations are triaged and the Backlog: field is added to open
# issues, this will be promoted to blocking.
gate advisory "backlog-issue-refs" python3 scripts/check-backlog-issue-refs.py

# --- report ------------------------------------------------------------------
echo
echo "gates: ${#PASSED[@]} passed, ${#FAILED[@]} failed, ${#WARNED[@]} advisory-failed"

# Named on every surface, not only when GITHUB_STEP_SUMMARY exists. The step summary
# is the CI-only form of this same list; without the stdout half, a local run reports
# "2 failed" and says nothing about which two — so the next step is re-running the
# whole registry under GITHUB_ACTIONS=1 to find out. The names are the output; the
# summary file is where they also get rendered.
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
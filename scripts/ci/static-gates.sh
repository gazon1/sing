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
#
# THE REGISTRY BELOW IS GENERATED. The gate lines, and the prose that explains
# them, live in sgate/gate_manifest.py — one declaration of where every gate
# runs. The region between the generated markers is rendered from it:
#
#   sgate render-static-gates --write    # rewrite the region
#   sgate render-static-gates --check    # fail if it is stale (it is a gate)
#
# Edit the manifest, not this file. The prose moved with the lines it explains,
# because a comment that says why a gate exists belongs to the declaration of
# that gate, and a declaration kept in two places is two declarations.
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
    # The gate scripts import `sgate`, and `sgate` is installed as an editable
    # package — which, on a host with several worktrees, is whichever checkout
    # was installed last. A gate that read another checkout's registry would
    # compare this repository's shell registry with a foreign Python registry
    # and report drift that does not exist. The local `scripts/src` goes first
    # on the path, so every gate below reads *this* checkout's code.
    PYTHONPATH="${PWD}/scripts/src${PYTHONPATH:+:${PYTHONPATH}}" \
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

# The actionlint gate, as a named function.
#
# It is a function, not an inline `bash -c` snippet, because the Python registry
# has to name it too: a gate the CLI cannot dispatch is a gate that only one
# surface can run, and the two registries then disagree about what exists.
# `run_all` resolves a command token that names a function defined in this file
# by sourcing the file and calling it — the same way it resolves `traceability`.
github_workflows_valid() {
  command -v shellcheck >/dev/null || {
    echo "shellcheck is not on PATH; actionlint would silently skip its shellcheck pass and lint less than CI does" >&2
    exit 1
  }
  actionlint -shellcheck="$(command -v shellcheck)" .github/workflows/*.yml
}
export -f github_workflows_valid

# The yamllint gate, as a named function, for the same reason.
yaml_is_structurally_valid() {
  git ls-files -z -- '*.yml' '*.yaml' | xargs -0 -r yamllint -c .yamllint.yml
}
export -f yaml_is_structurally_valid

# ─── Registry ────────────────────────────────────────────────────────────────
# All gate calls are INSIDE main() so that sourcing this file (to call one of the
# helper functions: digest_no_double_suffix, adr_dry_run,
# traceability) does NOT execute all gates as a side-effect.
# The `|| [[ $? == 0 ]]` guard at the end of this file ensures main() is NOT called
# when sourced — only when the script runs as a command.
main() {
# ─── The tooling must serve this checkout ─────────────────────────────────────
# A gate run that measures ANOTHER tree is not a gate run that found nothing to
# report: it is green, quiet, and says nothing about the code in front of you.
#
# The `sgate` package is an editable install, so the venv on PATH decides which
# checkout's `scripts/src/sgate` the gate CLI is — and `sgate/repo.py` derives
# the repository root from its own file location, so a foreign CLI reads a
# foreign registry and measures a foreign tree.
#
# Measured on 2026-10-22: a run here reported "41 passed, 0 failed" while
# measuring another worktree, whose registry was three gates shorter than this
# checkout's — and the three that were missing were exactly the ones that
# failed in CI afterwards (the tag catalogue, the issue registry, the
# selectable-text coverage).
#
# So the check comes first, and it is a refusal rather than a warning: a run
# that cannot say which tree it measured has no verdict to give.
if ! python3 scripts/check-gate-tooling-origin.py; then
    echo "" >&2
    echo "the gate CLI does not serve this checkout — the run below would measure" >&2
    echo "another tree. Fix the venv (the message above names the command)." >&2
    return 1
fi
# >>> BEGIN GENERATED: sgate render-static-gates — do not edit by hand >>>
# The two gate registries against each other, first: everything below is
# dispatched through one of them, and a gate that exists in one and not the
# other is a gate that runs on no one — or one that runs without a name.
#
# This was a warning until 2026-10-20, because the drift predated it. The
# drift is paid: the shell file and _registry.py now carry the same names.
gate blocking "gate registries agree" python3 scripts/check-gate-registries.py --strict
# Build hygiene
gate blocking "version catalog has no literals" sgate version_catalog_has_no_literals
# The shape of the CI workflow's Gradle block: one gate for three checks.
#
#   - a test task whose inputs are declared;
#   - one test population per job (measured 2026-10-16, run 38933517902: a later
#     Gradle invocation re-ran :desktopApp:test *without* -Ptest.tags, whose
#     Gradle default is excludeTags("slow"), so the @Tag("slow") scenario classes
#     were skipped rather than executed and the traceability step reported a
#     target that had in fact been measured as producing nothing);
#   - a reader of build output runs after the invocation that rewrites it (the
#     traceability step failed that way on the same run, and the flake step would
#     have done it silently while being continue-on-error).
#
# One file, three properties, one CI row. Each check keeps its own script, unit
# tests and positive control; the wrapper runs all three and reports every
# failure, not the first.
gate blocking "ci workflow shape is declared" python3 scripts/check-ci-workflow-shape.py
# A waiver whose premise is prose rots silently. The one control waived from the
# clean-run guard promises that both surfaces run `:detekt-rules:koverVerify` a
# step later; rename the CI step or drop the task from it and the promise is
# false while the waiver keeps waiving. This checks the promise, not the pass.
gate blocking "waiver references resolve" sgate waiver_references_resolve
gate blocking "declared dependencies are used" sgate declared_dependencies_are_used
# A published number is a claim about a measurement, and the claim outlives
# the measurement it was read from. "2.82M instructions" survived a real
# measurement of the very report it came from, because the percentages beside it
# are a ratio, and a ratio is the same at every level of the report — so the
# wrong denominator looked right.
#
# The record is a file: the class count, the instruction total, the percentages
# and the day each was measured. This compares every class count, instruction
# total, published percentage and published day the repository publishes with it,
# and names the document, the line and both numbers when they differ. It reads a
# fixed list of documents — a number published anywhere else is a claim this gate
# does not check, and that limit is written down in the gate's own docstring.
gate blocking "published measurements match the record" python3 scripts/check-published-measurements.py
# The published page prices the CI loop from one run, and the criterion that
# retires that record is the workflow's jobs changing. Nothing checked it: the suite job
# was renamed from `JVM tests, detekt, coverage` to `JVM tests`, and the page kept
# naming the old job for a whole change — a record of a run does not notice that the
# workflow moved on.
#
# This compares every job the record names with the jobs the workflow declares, and
# names the ones that are gone. The comparison is one-sided on purpose: the record
# holds the jobs that RAN and the workflow declares the jobs that CAN run, so equality
# would be red forever. A matrix job is expanded before the comparison, because the
# runner shows `Android (free)` and `Android (pro)` where the workflow declares one
# job.
#
# What it does not see: a job renamed and renamed back, an edit that keeps every name
# and changes what a job does, and a job added to the trunk path — the set of jobs a
# trunk push runs depends on the paths the push touched. The limit is written down in
# the gate's own docstring.
gate blocking "the recorded CI jobs are still jobs" python3 scripts/check-recorded-ci-jobs.py
# The heap of a test fork was a literal string in five build scripts, and the
# heap of the Gradle daemon was a literal string in `gradle.properties`. The
# measurement behind them lived in an ADR that no build read and no gate held.
#
# This is the shape the coverage floor had before its declaration was moved to
# the place Kover reads it: a number that nobody can check is a number nobody
# enforces. The record is a file, the build reads it, and this compares every
# test task in the tree with it.
gate blocking "the memory budget is one record" python3 scripts/check-memory-budget.py
gate blocking "room schema integrity" sgate room_schema_integrity
# The skill that tells an agent which transformation to use for which text
# field carries a table of the transformations and the rule each one applies.
# Nothing held it to the code: a transformation could be added, renamed, or
# have its rule changed, and the table kept claiming the old thing.
#
# So the table is read and compared with the Kotlin: a transformation the code
# declares and the table does not name fails; a row that names a transformation
# the code does not declare fails; and the facts the row claims — the digit
# limit, the length bound, the range — are derived from the source and
# compared with what the row says. The rule sentence is checked as far as a
# sentence can be: it has to mention the fact, so a sentence that stops saying
# what the code does fails too.
gate blocking "the text-field transformation table is true" python3 scripts/check-text-field-transformations-table.py
# The platform reference is the inventory of the seam: which port is bound to
# which implementation on which platform. It said so itself — "nothing here is
# generated … no gate will notice" — and it had rotted: it still named a port
# that was deleted, and it did not name the twenty ports the seam has.
#
# So the reference is checked against the code: a port the platform modules
# bind and the table does not name fails; a row that names a port the code does
# not bind fails; and an implementation a row attributes to a platform has to
# exist in that platform's source set. The table's fourth column says which of
# its rows the gate checks, so a row that cannot be checked says so.
gate blocking "the platform reference ports are true" python3 scripts/check-platform-reference-ports.py
# The E2E suite isolates one flow from the next by wiping the app's content
# between flows, and the list of tables that wipe reaches is a list of
# exceptions. Exceptions rot silently: a table added to the Room schema is not a
# row in a document, so the document keeps claiming the wipe reaches the whole
# profile while the code has stopped reaching it — and the next flow finds the
# leftover row and blames the test.
#
# So the schema, the wipe's own list and the inventory document are held to
# each other. A table nobody decided about fails; a table the document names and
# the schema no longer has fails; a table the code wipes and the document calls
# "not profile content" fails.
gate blocking "profile reset inventory is true" python3 scripts/ci/check-profile-reset-inventory.py
# A gate that dies instead of reporting is indistinguishable from a gate that
# passes, for every consumer that reads only the exit code. This found a real
# one on its first run: check-kiwi-inventory-ratchet.py crashed with
# "NoneType has no attribute loader" and had been dead since #430 (#529).
#
# "Gates are alive" — the probe that runs every gate here once and reports the
# ones that die — is NOT in this registry any more. It was, and it cost this
# job a second full pass over the gate corpus: 63 seconds to prove that gates
# do not crash, on a job whose other gates take 13 seconds together. It now
# runs in the `tests` job of ci.yml, where the Gradle step has already
# warmed everything and 63 seconds lands in slack nobody waits on.
#
# Two things follow from it no longer being here. It probes the gates named in
# this file, so it no longer probes itself — a probe that probed itself would
# launch every gate, and every gate would launch the probe (#529). And the
# `# no-self-probe` marker it used to carry is gone with the line; the filter
# stays in check-gate-liveness.py for the gates that still need it (#534).
# A skill is a hand-written description of code nobody diffs against it, so it is
# always the stale one and always reads like documentation rather than like a bug.
# The "ADR tools" section is generated from AdrSurface.kt and McpToolCatalog.kt;
# this compares the committed text with what the code says today (#526).
# An absolute core.hooksPath is correct in exactly one checkout and silently wrong in
# every worktree, and git config does not travel with the repo (#519).
gate advisory "git hooks path is per-worktree" sgate git_hooks_path_is_per_worktree
# The pre-push hook judges three gates of forty-one and the detekt modules a
# change reaches; the CI judges every module. Both numbers were lines in a
# bash script, and nothing compared them with the other side — so "detekt" in
# the hook read as "detekt" over the whole tree, and a violation in an
# untouched module looked like it would be caught locally.
#
# The difference is written down — config/hooks/pre-push.json — and this gate
# checks that the code still matches the record, in both directions: a gate the
# record names and the hook does not run, a script the hook runs and the record
# does not name, a module the CI judges and the record does not list. The
# partiality is part of the record, because an unwritten partiality reads as a
# full one.
gate blocking "hook gates are declared once" sgate hook_gates_are_declared_once
gate blocking "unwired surfaces" sgate unwired_surfaces
gate blocking "settings read by a feature" sgate settings_read_by_a_feature
# The coverage report measures every module that is in it.
#
# The list of test tasks that feed the aggregated report is written by hand,
# in the root build script. A module added to the build is not added to that
# list, so its tests never run in the coverage job — and a module whose
# classes ARE in the denominator is then scored as 0% covered, which reads
# like a coverage problem and is a measurement one.
#
gate blocking "coverage task list" sgate coverage_task_list
# The coverage floor is declared where Kover reads it.
#
# The app coverage floors lived in the root build script for months, in a
# `plugins.withId("…kover")` block that never executed — the root project has
# no Kover extension to configure, so the floors were evaluated by no task.
# The declaration now lives in settings.gradle.kts, and this gate is what
# stops it from moving back.
#
gate blocking "coverage floor declaration" sgate coverage_floor_declaration
# The timeout covers the run it was set for.
#
# The instrumented coverage run takes 37 minutes on the development host and
# longer on a CI runner, and the job's timeout was a literal that nobody
# compared with that number. This gate reads the measured cost and the
# timeout and says so when the timeout cannot cover the measurement — before
# the job is killed at the moment it was about to report.
#
gate blocking "coverage timeout" sgate coverage_timeout
# The prose a person cannot copy.
#
# The inventory table in the selectable-text skill says which surfaces render
# their text with a widget that makes it selectable, and which are excluded on
# purpose. The blocking reader of that table is a JVM test, so it cannot be a
# line here — this gate is the half that needs no JVM: it finds the prose that
# no row covers.
#
# It was advisory while the inventory was open: it had to guess what "prose"
# means from the text of a call, and a guess is wrong in more ways than a
# comparison is. The inventory is closed now, so the remaining findings are
# decisions rather than gaps, and the empty list is the assertion.
gate blocking "selectable text coverage" sgate selectable_text_coverage
# The registry is the source of truth for a change, and nothing checked it.
#
# Four changes landed in main without an entry in `.specify/issues/`: the close
# rule consolidation and the three that followed it. Nothing failed — the specs
# validated, CI was green — and the registry simply did not mention them. A
# change the registry does not know about is a change the next session cannot
# find: the decision, the spec and the tasks are in the tree, but nothing points
# at them and nothing says they are this branch's work.
#
# The fix is one command, the same one that writes the record:
# `python3 scripts/openspec-import-all.py` — and `--replace` for a record that
# names another change, because without it the importer skips a record that
# already exists.
#
# The gate walks the changes and looks up their records; it does not walk the
# records, because most of the registry is history. What it checks is the
# direction that can still go wrong for work happening now: a change with no
# record, a record that names a different change, and two changes claiming one
# slug.
gate blocking "issue registry covers every change" python3 scripts/check-issue-registry.py --quiet
# A pointer that does not resolve is a stop that costs exactly what the note
# it came from was written to remove.
#
# The note that introduced this gate pointed `EXEMPT_VM_TEST` at the harness file;
# it lives in the conventions test that reads it. Nobody noticed for a session,
# because a wrong pointer does not fail — it costs one `ls` and then costs the
# reader the question the note was supposed to answer.
#
# The rule covers the pointers that are unambiguous: a backticked path inside the
# repository, with `{a,b}` and `...` resolved. A path that is relative to prose
# around it is not checked, because where it is relative to is not written down
# and the gate would be guessing.
gate blocking "AGENTS.md pointers resolve" python3 scripts/check-agents-pointers.py --quiet
# The review of a change has to be somewhere the next session can read.
#
# `AGENTS.md` says a change gets a review by a separate agent before the PR is
# opened, and it says it in one line. The review that came with the issue-registry
# gate found four real defects in a diff that had been written, reviewed and
# pushed — and nothing in the tree said so. The finding existed in the reviewer's
# answer, in a session that was about to end.
#
# Every open change carries its verdict in `.specify/reviews/<slug>.md`: the
# change, the verdict, the date, the pull request, the commit the review was
# made against, the digest of the change's contract documents, and what the
# review said. The digest is checked: a contract that moved after the verdict
# was written leaves a verdict that speaks for a change that is not there any
# more, and the gate says so. The changes that predate the rule are in
# `config/docs/review-verdicts-baseline.txt`,
# and that list only shrinks — accepting a new exception is an edit a reviewer
# can see, not a side effect of a maintenance command.
gate blocking "review verdicts cover every change" python3 scripts/check-review-verdicts.py --quiet
# The catalogue of the ids a Maestro flow may address.
#
# `Maestro/TAGS.md` is a golden file, and its reader is a JVM test — so a tag
# added today and a catalogue not rewritten are both green until someone runs
# the forty-minute suite. This gate is the same comparison without the JVM: it
# parses `TestTags.kt` and the generated section of the catalogue and says in a
# second whether they still agree.
#
# It reports; it does not rewrite. Regeneration needs the JVM
# (`-PupdateGoldens=true`), and the gate names that command when it finds a
# difference.
gate blocking "the tag catalogue matches the tags" sgate the_tag_catalogue_matches_the_tags
#
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
# static job installs them (pinned in config/toolchain-versions.env) through
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
gate blocking "GitHub workflows are valid" github_workflows_valid
gate blocking "YAML is structurally valid" yaml_is_structurally_valid
#
# Markdown links resolve. The gate is scripts/check-doc-links.sh, which wraps
# lychee; the reasoning for the flags it passes lives in that script's header.
#
# It is not a replacement for scripts/check-doc-dead-refs.py, which checks the
# *other* shape of reference — a path inside backticks, and a dated ADR slug
# cited without its `.md`. That script's PATH_RE matches backticks only, so
# Markdown links were never in its scope. This gate is coverage the repository
# did not have.
#
# It is not a replacement because of how the two resolve. lychee resolves a
# relative link against the file that contains it, which is what a Markdown
# reader's editor does. The script resolves against the repository root and
# falls back to matching on basename alone, so a link written one directory too
# high ("../AGENTS.md" from docs/decisions/) still lands on a file of that name
# and is reported as "drift" — a warning, never a failure. Fifteen such links
# sat in ADRs and skills on 2026-10-19, invisible to every gate. They were
# fixed, not accepted.
#
# The binary must be on PATH, for the same reason actionlint needs shellcheck:
# a gate that cannot run looks exactly like a gate that has nothing to report.
# CI installs it pinned (config/toolchain-versions.env holds the version; the
# PyPI wheel of the upstream Rust binary) through .github/actions/setup-python-tools.
gate blocking "doc links" sgate doc_links
#
# The navigation doc says what opens what, and it is pointed at from the nav3
# skill — so an agent that follows the pointer reads it. `doc links` above
# checks that the links in it resolve; it cannot see that a *claim* about
# behaviour has stopped being true, because the link is fine and the sentence
# is wrong.
#
# Two rows went false on 2026-10-22 and nothing noticed: the deep-link row for
# `singularity://view/{viewId}` still named `SavedAgendaEdit` after the code
# moved to `SavedAgendaResults`, and the platform row still said the desktop
# tasks stack pushes the `Detail` start onto the stack after the code changed
# to seed the stack with the requested route itself.
#
# The gate compares the doc against the code: the types a deep-link row names
# must be the types the matcher names, every link the code declares must have a
# row, and a claim that the tasks stack is pushed onto must agree with what the
# platform assemblers do.
gate blocking "navigation doc matches the code" sgate navigation_doc_matches_the_code
#
# The DI fixture table says what to hand a Koin definition that takes its
# arguments at runtime. The definition lives in `shared/.../core/di/`, the
# fixture in `desktopApp/.../jvmTest/`, and nothing kept them in step.
#
# On 2026-10-22 `TaskDetailCoordinator` was given a route instead of a
# `TaskId`, the definition was updated, and the fixture was not — so the graph
# could not be created. The test that catches it lives in the full desktop
# suite: an hour of wall clock away on a loaded host, at the very end of the
# work.
#
# This gate reads the same two facts from the sources and compares them without
# a JVM, so it runs in seconds, in the static gates and in the pre-push hook.
# It is a text comparison, so it is a floor and not a proof: it catches a
# definition and a fixture that disagree about which type they are talking
# about, and leaves a value built by a factory function to the runtime test.
gate blocking "DI fixture matches the definitions" sgate di_fixture_matches_the_definitions
#
# The other reference shape: a path inside backticks, and a dated ADR slug
# cited without its `.md`. It was registered in the Python registry with a
# positive control and a green run, and no gate line anywhere invoked it —
# `check-gate-wiring.py` counted it as "wired" because a *comment* in this
# file named the script, and a prose mention is not an invocation. A gate that
# runs on no one reports success forever.
#
# The script resolves against the repository root and falls back to matching on
# basename alone, so a path written one directory too high is reported as
# "drift" — a warning under the default flags, a failure under `--strict`.
# `--strict` is the flag CI uses: drift is a reference pointing at the wrong
# place, and a warning does not stop anything.
gate blocking "doc dead refs" python3 scripts/check-doc-dead-refs.py --strict
#
# The generated gate catalogue: which gates exist, and whether each one blocks.
#
# `docs/scripts/gate-registry.md` is generated from `_registry.GATES`. It had
# no freshness check anywhere — not in this file, not in check.sh — so it could
# drift silently. It drifted: the generator read the *command modules* directory
# rather than the registry, so the table listed private helpers (`_gate-runner`,
# `_parsers`, `_registry`, `_runner`) as gates, missed every gate whose command
# comes from a GateSpec, and reported `mode=unknown` for most rows.
#
# `--check` compares the file against what the generator produces now. The
# generator is the registry, so a gate that is added, removed or reclassified
# changes this table, and this gate is what says so.
gate blocking "gate registry is current" sgate render-registry --check
#
# The shell registry itself is generated. The gate lines below used to be
# written by hand in this file, next to the prose that explains them, and the
# Python registry carried the same names and modes — two declarations of one
# fact, kept in step by a check that compared them.
#
# Now there is one declaration: `sgate.gate_manifest.PLACEMENTS` says where each
# gate runs, and this file is rendered from it. `sgate render-static-gates
# --check` is the gate that says the file is current; after changing the
# manifest, run `sgate render-static-gates --write`.
gate blocking "the shell registry is current" sgate render-static-gates --check
# AGENTS.md is read by every session, in one go, and every line of it
# is paid for whether the session needs it or not. It was 517 lines; the rules
# that needed more room moved to docs/architecture/, and this gate keeps the
# file from growing back — a change that needs more room needs a document and
# a pointer, not a longer cheat sheet.
gate blocking "the cheat sheet stays a cheat sheet" python3 scripts/check-agents-size.py
# Git exports GIT_DIR (and GIT_WORK_TREE, GIT_INDEX_FILE,
# GIT_COMMON_DIR) to every hook it runs, and those variables override the
# directory a git command was pointed at. A digest computed that way is a
# digest of a tree nobody asked for — and a `git config` in a test's temporary
# repository lands in the shared config of the checkout, which is how
# core.sshCommand was once replaced by a test fixture.
#
# A script that reads a tree that is not its own checkout's root must remove
# those variables from the environment the command inherits.
gate blocking "a git command that reads a tree controls its environment" python3 scripts/check-git-environment.py
# The checkout has to be able to commit, to run its hooks, and to
# keep the SSH connection alive through a long pre-push hook. A bare checkout
# refuses every command, a hooks path that leads nowhere runs no hooks at all,
# and a lost keepalive kills the push after the hook said the tree was clean.
#
# The expected values are written down in config/checkout-invariants.json, and
# scripts/doctor.sh reads the same file to diagnose a checkout that drifted.
gate blocking "the checkout can work" python3 scripts/check-checkout-invariants.py
#
# What the verification loop costs.
#
# The loop grew by minutes at a time — a wave added an Android host test, another
# added a module to the detekt sweep — and nobody could say so, because nothing
# measured it. Now `sgate run-all` times every gate it runs and `check.sh` times
# every Gradle step it runs, and the result is published in
# docs/testing/gate-costs.md.
#
# This gate compares two committed files: the published page and the recorded
# baseline. That is a question a CI runner can answer honestly. The comparison with
# a *local* measurement is not one CI can answer, and it is not asked here — the
# local report names what got slower, and a CI runner has no local measurement to
# name.
gate blocking "the published gate costs are current" sgate gate-costs --check
#
# Every gate dispatched through `sgate` is called with `--json` by
# `sgate run-all`, and the document that lands on stdout is what a consumer
# reads. The contract is one shape: one JSON object, and nothing else.
#
# Nothing checked that, and the defect happened twice: the GitHub Actions
# annotation is written to stdout, because that is where Actions reads
# workflow commands from, so a gate whose stdout carried a document had two
# claimants for one channel; and four adapters rebuilt a verdict from prose
# instead of reading the verdict their producer already emitted. Both were
# found by reading the code, and both were found late.
gate blocking "gates speak the JSON contract" python3 scripts/check-gate-json-contract.py
#
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
# Lint-rule governance: one gate for six checks over one subject.
#
#   - the generated detekt.yml is current;
#   - the generated rule table is current;
#   - every rule has a registration;
#   - the baseline does not grow silently;
#   - a lint rule states the decision it implements;
#   - a suppression says why it is there.
#
# Six CI rows for one subject became one. Each check keeps its own script, its
# own unit tests and its own positive control — a control proves that a check
# can fail, and one control for six checks would prove only that something in
# them can fail.
gate blocking "detekt governance is consistent" python3 scripts/check-detekt-governance.py
# detekt-rules branch coverage floor: 60% (below 2026-10-15 measured 65.6%, 725/1105).
# This gate runs in ci.yml's `tests` job (where Gradle is available), not here:
# it is the Gradle task `:detekt-rules:koverVerify`, named by ci.yml's
# "Kover verify (detekt-rules)" step and by check.sh step 9b. The floor itself
# is declared in settings.gradle.kts (DETEKT_RULE_BRANCH_FLOOR), where every
# coverage floor of this repository lives, and bound by the Kover verify rule of
# detekt-rules/build.gradle.kts; Part C of check-gate-wiring.py holds it to a
# usable value.
#
# Scenario traceability
# traceability validate/coverage resolve the installed `traceability` package from
# scripts/src/ (installed by `pip install -e scripts/src/`), so there is no
# PYTHONPATH to set and no stand to start.
gate blocking "scenario specs valid" sgate scenario_specs_valid
gate blocking "coverage matrix current" sgate coverage_matrix_current
gate blocking "coverage holes did not grow" sgate coverage_holes_did_not_grow
#
# Server schema
gate blocking "supabase schema is self-consistent" sgate supabase_schema_is_self_consistent
gate blocking "sync allowlist seed matches contract" sgate sync_allowlist_seed_matches_contract
#
# Maestro
gate blocking "maestro selectors valid" sgate maestro_selectors_valid
# The flow catalogue: the flows this repository promises to have, written down.
# A flow deleted without a word leaves the suite smaller and every run green,
# because a suite reports the flows that are left.
gate blocking "maestro flow catalogue is current" sgate maestro_flow_catalogue_is_current
# The wait budgets: one written number for the screen both UI layers wait for.
# The instrumented suite and the Maestro flows cannot share a constant — separate
# runners — and they drifted: forty-one waits for the same shell carried five
# different timeouts, and the instrumented suite's 8 s is the reason
# `android-device-tests` was red on every run of `main`.
gate blocking "ui wait budgets are consistent" sgate ui_wait_budgets_are_consistent
#
# Source and documentation contracts. Both have a GateSpec in
# scripts/src/sgate/commands/_registry.py and a positive control in GATE_CONTROLS,
# but neither had a line here — so `sgate run-all`, which reads *this* file, never
# ran them. The scripts were green, the controls were registered, and nothing
# executed: a gate that runs on no one reports success forever. Part H of
# check-gate-wiring.py is what finally said so, once it stopped resolving
# `run-all` against the Python mirror instead of this registry.
gate blocking "the toolbox surface is declared once" python3 scripts/check-skill-drift.py
#
# Workflow semantics. actionlint reads a workflow as YAML plus GitHub's expression
# language; neither knows that in POSIX a pipeline's status is the *last* command's.
# Four CI steps piped, so their failures could not fail them.
gate blocking "workflow steps keep their exit status" sgate workflow_steps_keep_their_exit_status
#
# The `env` context holds the variables a workflow defines — not the runner's
# environment. `${{ env.ANDROID_HOME }}` expands to the empty string, silently,
# and a path built from it names nothing. That is not a theory: the Maestro
# shards failed a release cycle with "emulator binary not found in PATH"
# because the cache paths were written `${{ env.ANDROID_HOME }}/…`.
gate blocking "workflow env context resolves" sgate workflow_env_context_resolves
#
# Releases. `meta` proves a tag was on main with a green CI run; it says nothing
# about whether a release came out of it, nor whether the release that exists
# describes the assets it actually carries. `publish` had never run, so neither
# half had ever been checked by anything.
gate blocking "releases describe themselves" sgate releases_describe_themselves
#
# Provenance and licensing. Both were registered in `_registry.py` with a positive
# control and a sabotage that can fail, and neither had a line here — so `run-all`,
# which reads this file, never ran them. A control for a gate that never executes
# proves nothing about the gate.
gate blocking "pro licence boundary" python3 scripts/check-pro-licence-boundary.py
gate blocking "provenance" python3 scripts/check-provenance.py
#
# ADR references: a prose mention of a dated-ADR slug carries no path, so a moved or
# deleted decision leaves a dangling claim nothing else sees.
gate blocking "adr references are valid" python3 scripts/check-adr-references.py
#
# ─── Deliberately NOT here ─────────────────────────────────────────────────────
#
# Four more gates are registered in `_registry.py`, each with a positive control,
# and each used to be absent from this file. They are not runnable in CI, so
# registering them would trade "a gate that never runs" for "CI red for a reason
# unrelated to this change":
#
#     doc dead refs                    exit 1   flaky tests are acknowledged   exit 1
#     rebase compiles                  exit 1   test runs                      exit 1
#
# They are the repo's knowingly-red corpus, in the same shape as the
# `script conventions` entry below was before the Click migration landed.
# Each carries `needs_clean_run=False` or a
# documented exemption in `GATE_CONTROLS` for exactly this reason. `manifest-verify`
# now reports a registry entry with no gate line in this direction too, so the two
# lists cannot drift apart unnoticed again — which is how all eleven went missing.
#
# The `doc sizes are within budget`, `req id uniqueness`, `backlog issue refs` and
# `gate honesty` entries that used to stand here are gone: the first three gates
# were removed on 2026-10-19 and 2026-10-15, and the fourth — the one that planted
# a detekt violation in a throwaway worktree to prove the lint gate could fail —
# was removed on 2026-10-20, with its script, its prune helper and its `just`
# recipe. See the ADR of 2026-10-20.
#
# AppTracer
#
# REMOVED: "local branch is not behind origin/main" (bash scripts/ci/check-unmerged.sh)
#
# The premise was sound — rebasing onto a stale main creates divergent history that
# needs a force-push — but a runner cannot check it. CI checks out a detached commit
# with no tracking branch and no `origin/main` ref, so the gate failed every run on
# the runner's setup rather than on anyone's divergence:
#
#     check-unmerged: upstream 'origin/main' does not exist. Run 'git fetch' first.
#
# A gate that is always red for a reason unrelated to what it checks trains people to
# read past it, and it was the third blocking failure in `Static gates` on a run whose
# other two were unrelated. Divergence prevention is not lost: `.githooks/pre-rebase`
# implements the same rule locally, where an upstream ref genuinely exists, and does so
# on the ref about to be rebased onto rather than on a tracking branch CI never has.
#
# Gate-script conventions. Blocking now that the corpus is green: the last three
# argparse holdouts are migrated, and the check finds the argparse import by
# parsing the module rather than by looking for a substring — which is how it
# used to report itself as a violation while the corpus was already clean.
#
# It was advisory for as long as it was red on a clean tree, and a control
# cannot tell "caught the mutation" from "still failing the known violations".
# That is the whole reason it had no positive control; it has one now.
gate blocking "script conventions" sgate conventions
#
# The Compose stability contract — `config/compose/stability_config.conf` — is
# the only thing that tells the Compose compiler which types are stable. It is
# what makes a list row skip a recomposition when its task did not change.
#
# A name in that file which resolves to no type is not an error for the
# compiler: it matches nothing and is ignored. The contract then says less than
# it appears to say, and the only symptom is that rows keep recomposing — a
# performance fact with no error message attached to it. The second failure is
# quieter still: a contract that no module reads leaves every "stable" claim in
# the repository a claim about a file the compiler never saw.
#
# The gate reads the contract and the sources, and nothing else. It does not
# run the compiler and does not read the compiler's stability report: that
# report is a build artifact, and a gate that depends on build output is the
# fragility this repository already has a gate for.
gate blocking "compose stability contract" python3 scripts/check-compose-stability-config.py
# The server half of the Google OAuth exchange, under test.
#
# The function holds a client secret, redeems a one-time code and stores a
# refresh token, and it is the only half of that exchange that nobody could see
# working. Its three failures — a redirect URI that does not match, a code that
# was already spent, and a call to Google that never came back — used to reach
# the device as one answer: "the connection failed".
#
# The tool is Deno, and it is not on every machine: the gate is skipped where
# Deno is absent, and the skip says so rather than passing quietly.
gate blocking "the edge functions' own tests" deno test supabase/functions/google-oauth-exchange/
#
# A `hideKeyboard` is a BACK keypress when there is no keyboard to hide.
#
# Measured on a phone (2026-10-28): a flow's second `hideKeyboard` dismissed the
# editor dialog, and the run failed on an element the editor was holding. The
# editors are dialogs, so BACK closes them — the command that means "dismiss" and
# the command that means "hide the keyboard" are the same key.
#
# So a `hideKeyboard` follows a text input, one per text input, and never
# follows anything else.
gate blocking "maestro keyboard commands have something to hide" python3 scripts/check-maestro-keyboard-commands.py
#
# A flow that names a pixel is a claim about one display.
#
# Measured on a phone (2026-10-28): the flows that scrolled the settings rail
# by pixel never reached the trailing tabs, and the flows that tapped the menu
# sheet's last item tapped the system navigation bar instead. On the CI
# emulator they are green - a statement about the emulator that reads like a
# statement about the app.
#
# A swipe may be a share of the screen, or the element that scrolls. Both mean
# the same gesture on every display.
gate blocking "maestro flows do not name a pixel" python3 scripts/check-maestro-screen-geometry.py
# The grammar of a link has a home: TaskLinkSpec for a deep link, core/links/InternalLinks.kt for an internal one. A second copy of it is a link that opens nothing.
gate blocking "the link grammar has one home" sgate the_link_grammar_has_one_home
# The catalogue of the gate scripts, generated from their own docstrings. A catalogue that lost a tool is a catalogue that sends a reader to a tool that is not there.
gate blocking "the tool catalogue is current" sgate the_tool_catalogue_is_current
# <<< END GENERATED: sgate render-static-gates >>>

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

#!/usr/bin/env bash
# lint-autofix.sh — apply detekt's own corrections to the working tree.
#
# detekt rewrites the rules it knows how to fix (formatting, spacing, unused
# imports, and the ktlint rules bundled via the detekt-formatting plugin).
# ktlint is NOT a separate Gradle plugin in this project, so there is no
# `ktlintFormat` task — detekt --auto-correct is the single correction entry
# point and it already covers ktlint formatting.
#
# One Gradle invocation per module. This is not tidiness: `--auto-correct` is
# an option of the `detekt` *task*, and a task option given on the command line
# reaches only the last matching task of the invocation. `./gradlew detekt
# --auto-correct` therefore corrects one module and silently skips the rest —
# which is indistinguishable from "the flag does nothing" unless the files are
# read afterwards. See docs/decisions/2026-10-17-detekt-auto-correct-works-use-it.md.
#
# What this script does NOT do:
#
#   * It does not decide whether the tree is acceptable. `detekt` remains the
#     gate; this only rewrites what can be rewritten. A violation that no rule
#     can correct stays in the tree, and the lint gate still fails on it.
#   * It does not touch the detekt baseline. The baseline is debt, not
#     formatting.
#
# Exit code:
#
#   0 — the linters ran. Findings that no rule can correct are reported, not
#       raised: the caller decides what to do with them.
#   1 — a linter could not run at all (the task is missing, the build failed
#       before the task, Gradle could not start).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GRADLE=(--no-daemon --console=plain)

# Modules are discovered, not listed. `changed-modules.py --detekt-tasks` reads
# them from settings.gradle.kts (including conditional ones), so a module added
# to the build is corrected without anyone editing this file.
mapfile -t TASKS < <(python3 "$ROOT/scripts/changed-modules.py" --detekt-tasks)

if [[ ${#TASKS[@]} -eq 0 ]]; then
    echo "no detekt tasks found — nothing to correct" >&2
    exit 1
fi

MODULES=()
for t in "${TASKS[@]}"; do
    mod="${t%:detekt}"
    mod="${mod#:}"
    MODULES+=("$mod")
done

# run_task <module> <task> [extra gradle args...]
run_task() {
    local module=$1 task=$2
    shift 2
    local log rc=0
    log="$(mktemp)"
    ./gradlew "${GRADLE[@]}" ":$module:$task" "$@" >"$log" 2>&1 || rc=$?

    if ! grep -qE "^> Task :$module:$task( |$)" "$log"; then
        echo "  ✗ :$module:$task did not run (exit $rc)" >&2
        sed -n '1,40p' "$log" >&2
        rm -f "$log"
        return 1
    fi

    if ((rc != 0)); then
        echo "  · :$module:$task reported findings (exit $rc) — what it could correct is in the tree"
        grep -E "^\s+[A-Za-z][A-Za-z0-9]* - " "$log" | sed 's/^/      /' | head -20 || true
    else
        echo "  · :$module:$task — nothing to correct"
    fi
    rm -f "$log"
    return 0
}

echo "lint auto-fix: ${#MODULES[@]} module(s) — ${MODULES[*]}"

snapshot() {
    git ls-files -m -o --exclude-standard -z 2>/dev/null \
        | xargs -0 -r cksum 2>/dev/null \
        | sort
}

before="$(mktemp)"
after="$(mktemp)"
trap 'rm -f "$before" "$after"' EXIT
snapshot >"$before"

for module in "${MODULES[@]}"; do
    run_task "$module" detekt --auto-correct
done

snapshot >"$after"

corrected="$(comm -13 "$before" "$after" | cut -d' ' -f3- | sort)"

if [[ -z "$corrected" ]]; then
    echo "lint auto-fix: the linters changed nothing — the tree is as it was"
else
    mapfile -t corrected_files <<<"$corrected"
    echo
    echo "lint auto-fix: the linters corrected ${#corrected_files[@]} file(s):"
    for file in "${corrected_files[@]}"; do
        [[ -n "$file" ]] || continue
        echo "  · $file"
    done
    echo
    git --no-pager diff --stat -- "${corrected_files[@]}"
fi
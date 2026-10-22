#!/usr/bin/env bash
#
# scripts/gradle-slot.sh — run a command while holding one of N machine slots.
#
# WHY
#
# Every git worktree on this machine raises its own Gradle daemon, and three
# concurrent builds saturate the CPUs: /proc/pressure/cpu measured
# `some avg10=48.79` on 2026-10-22 — roughly half of the time, tasks were
# waiting for a CPU rather than running on one.
#
# WHAT THIS DOES NOT DO
#
# The slot does NOT limit memory. It is released when the build's client
# process exits; the Gradle daemon it started lives on for
# `org.gradle.daemon.idletimeout` afterwards. Memory is held by the heap, not
# by this semaphore — the heap budget lives in gradle.properties and in the
# `maxHeapSize` of each test task (ADR 2026-10-22-gradle-memory-budget).
#
# HOW THE SLOT IS HELD
#
# flock(2) locks the *open file description*, not the process. The lock file is
# opened on descriptor 9 and that descriptor stays open for the whole run of
# this shell, so the lock survives `flock` exiting — which is the whole point:
# the lock must outlive the `flock` invocation.
#
# The child is started with `9>&-` so the descriptor is NOT inherited. If the
# Gradle daemon inherited it, the daemon would hold the slot for its whole
# lifetime and the pool would leak slots one build at a time.
#
# EXIT CODES
#
#   0..96  the command's own exit code, passed through untouched
#   97     the wait for a free slot timed out — distinct from Gradle's 0/1
#          and from the "slot busy" 75, so no caller can confuse it with
#          either a failed build or a busy slot
#
set -euo pipefail

# Where the slot files live. Machine-wide, so every worktree shares one pool.
# `SINGULARITY_GRADLE_SLOTS_DIR=0` disables the pool entirely — that is the
# opt-out, and it is an explicit opt-out: the pool being off "because nobody
# set the variable" was the mistake this line exists to prevent.
slots_dir="${SINGULARITY_GRADLE_SLOTS_DIR:-${XDG_RUNTIME_DIR:-/tmp}/singularity-gradle-slots}"
slot_count="${SINGULARITY_GRADLE_SLOT_COUNT:-3}"
wait_secs="${SINGULARITY_GRADLE_SLOT_WAIT_SECS:-1800}"
readonly SLOT_TIMEOUT=97

# ── status ──────────────────────────────────────────────────────────────────
# Reports what the pool looks like right now: which slots are held, how many
# Gradle/Kotlin daemons are alive and how much RSS they hold. Read-only — it
# takes no slot and leaves the pool exactly as it found it.
if [[ "${1:-}" == "--status" ]]; then
    if [[ "$slots_dir" == "0" || "${GITHUB_ACTIONS:-}" == "true" ]]; then
        echo "gradle-slot: the pool is disabled (${slots_dir})"
    else
        mkdir -p "$slots_dir"
        held=0
        for i in $(seq 1 "$slot_count"); do
            if exec 9>"$slots_dir/slot-$i.lock"; then
                if flock --nonblock 9; then
                    echo "  slot-$i: free"
                else
                    echo "  slot-$i: HELD"
                    held=$(( held + 1 ))
                fi
                exec 9>&-
            fi
        done
        echo "gradle-slot: $held of $slot_count slots held"
    fi
    daemons="$(pgrep -f 'GradleDaemon' 2>/dev/null | wc -l || true)"
    kotlin="$(pgrep -f 'KotlinCompileDaemon' 2>/dev/null | wc -l || true)"
    rss=0
    while read -r pid; do
        [[ -n "$pid" ]] || continue
        value="$(awk '/^VmRSS:/ {print $2}' "/proc/$pid/status" 2>/dev/null || true)"
        [[ -n "$value" ]] || continue
        rss=$(( rss + value ))
    done < <(pgrep -f 'GradleDaemon|KotlinCompileDaemon' 2>/dev/null || true)
    echo "  Gradle daemons:  $daemons"
    echo "  Kotlin daemons:  $kotlin"
    echo "  JVM RSS:         $(( rss / 1024 )) MiB"
    exit 0
fi

# 1. A nested call: an outer process already holds a slot. Take a second one?
#    No — three parents would each hold a slot and wait for their children to
#    take one, and the pool would deadlock on itself.
if [[ "${SINGULARITY_GRADLE_SLOT_HELD:-}" == "1" ]]; then
    exec "$@"
fi

# 2. CI: one checkout per runner, but the jobs on it run in parallel — they
#    would fight over slots that exist for a different reason.
if [[ "$slots_dir" == "0" || "${GITHUB_ACTIONS:-}" == "true" ]]; then
    exec "$@"
fi

mkdir -p "$slots_dir"
deadline=$(( SECONDS + wait_secs ))
announced=0

while :; do
    for i in $(seq 1 "$slot_count"); do
        exec 9>"$slots_dir/slot-$i.lock"
        if flock --nonblock 9; then
            rc=0
            # The child's exit code IS the result. flock is not part of it: a
            # command that exits 75 (its own "busy" code) must not be mistaken
            # for "the slot was busy, run it again".
            SINGULARITY_GRADLE_SLOT_HELD=1 "$@" 9>&- || rc=$?
            exit "$rc"
        fi
        exec 9>&-
    done

    if (( SECONDS >= deadline )); then
        echo "gradle-slot: all $slot_count slots were busy for ${wait_secs}s — the build did not start" >&2
        exit "$SLOT_TIMEOUT"
    fi

    # One message, not one every five seconds: a per-iteration notice turns a
    # two-minute wait into a wall of identical lines.
    if (( announced == 0 )); then
        echo "gradle-slot: all $slot_count slots are busy, waiting for a free one (up to ${wait_secs}s)" >&2
        announced=1
    fi
    sleep 5
done

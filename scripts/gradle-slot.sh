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
# WHAT THE SLOT DOES NOT DO
#
# The slot is released when the build's client process exits; the Gradle daemon
# it started lives on for `org.gradle.daemon.idletimeout` afterwards. The heap
# budget lives in gradle.properties and in the `maxHeapSize` of each test task
# (ADR 2026-10-22-gradle-memory-budget).
#
# WHAT THE SLOT DOES ABOUT MEMORY
#
# The slot counts builds; the memory is what a build costs. One worktree costs
# a 6 GiB daemon plus 4 × 1.5 GiB of test forks — about 12 GiB — so three of
# them are 36 GiB on a 62 GiB host, before the emulator and the IDE. A fourth
# one does not fit, and a build that starts without room does not fail at the
# start: it fails halfway, when the forks cannot get their heap.
#
# So a free slot is only taken when the machine can still hold the build that
# is about to start. Below the threshold the pool waits, exactly as it waits
# for a busy slot. The threshold is the free memory a build needs before it
# starts, and it lives in the budget record beside the heaps it is derived
# from.
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

# The repository root, for the budget record the memory threshold is read from.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The free memory a build needs before it may start, in GiB. Read from the
# budget record, so the number lives beside the heaps it is derived from: a
# daemon of `gradle.daemon.heap` plus `test.forks.module.shared` forks of
# `test.heap.module.shared`, with room for the rest of the daemon.
#
# `SINGULARITY_GRADLE_MIN_FREE_GIB` overrides it for a machine that is not
# this one.
min_free_gib() {
    local recorded
    recorded="$(awk -F= '/^gradle\.slot\.min\.free\.gib=/ {print $2}' \
        "$root/config/docs/gradle-memory-budget.properties" 2>/dev/null | tr -d '[:space:]')"
    local value="${SINGULARITY_GRADLE_MIN_FREE_GIB:-${recorded:-16}}"
    # A threshold that is not a number would make the arithmetic below fail and
    # the pool wait forever. 16 GiB is the recorded default either way.
    [[ "$value" =~ ^[0-9]+$ ]] || value=16
    # A machine with less memory than the threshold cannot satisfy it: cap the
    # threshold at HALF of what the machine has. An 8 GiB laptop asked for
    # 16 GiB free would wait for memory that can never be free, and the wait
    # ends as a timeout that names slots — which is the wrong reason.
    #
    # Half, and not less: a quarter of the machine's memory would be reachable
    # on every machine and would quietly weaken the guard everywhere — a 32 GiB
    # box asked for 16 GiB free would have been let off with 8, and a build
    # that dies halfway for want of memory is exactly what this gate is for.
    # Half leaves every machine with 32 GiB or more at the recorded threshold,
    # and asks a smaller machine only for what it can actually give.
    local total
    total="$(mem_total_gib)"
    if [[ -n "$total" ]] && (( total > 0 )); then
        local cap=$(( total / 2 ))
        if (( cap < value )); then
            value=$cap
        fi
    fi
    # A threshold of zero would let a build start with no room at all, which is
    # the failure the gate exists to prevent.
    if (( value < 1 )); then
        value=1
    fi
    echo "$value"
}

# The file the memory is read from. Overridable so that the behaviour of this
# gate can be exercised against a machine that is not this one — the failure
# this script had (a memory reading that always came back as zero) is not
# reproducible on a host whose /proc/meminfo is readable.
meminfo_file="${SINGULARITY_MEMINFO_FILE:-/proc/meminfo}"

# The free memory of this machine, in GiB — or NOTHING when the machine does not
# say.
#
# An unknown amount is NOT zero. The first version of this read
# `/proc/meminfo` and turned every failure into `0`, and zero is below every
# threshold: on a machine whose memory the kernel does not report, the pool
# blocked every build and exited 97 with "the build did not start". A gate that
# cannot see the constraint must not enforce it.
mem_available_gib() {
    awk '/^MemAvailable:/ {printf "%d", $2/1048576; found=1; exit}
         END {if (!found) print ""}' "$meminfo_file" 2>/dev/null || true
}

# The total memory of this machine, in GiB — or nothing when it is unknown.
mem_total_gib() {
    awk '/^MemTotal:/ {printf "%d", $2/1048576; found=1; exit}
         END {if (!found) print ""}' "$meminfo_file" 2>/dev/null || true
}

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
        echo "  free memory:    $(mem_available_gib) GiB (a build needs $(min_free_gib) GiB)"
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
    # `9>&-` here too: the outer process still holds the lock descriptor, and a
    # child that inherits it keeps the slot busy for as long as the child runs —
    # the lock would be released when the grandchild exits, not when the slot's
    # own build does.
    exec "$@" 9>&-
fi

# 2. CI: one checkout per runner, but the jobs on it run in parallel — they
#    would fight over slots that exist for a different reason.
if [[ "$slots_dir" == "0" || "${GITHUB_ACTIONS:-}" == "true" ]]; then
    exec "$@"
fi

mkdir -p "$slots_dir"
deadline=$(( SECONDS + wait_secs ))
announced=0
need_free_gib="$(min_free_gib)"
available=""

while :; do
    memory_blocked=0
    for i in $(seq 1 "$slot_count"); do
        exec 9>"$slots_dir/slot-$i.lock"
        if flock --nonblock 9; then
            # A free slot is not a free machine. The build that is about to start
            # needs its daemon and its forks, and a build that starts without
            # room for them dies halfway instead of at the start.
            #
            # A memory reading the machine does not give is NOT a reading of
            # zero: an unknown amount is not a constraint, and a gate that
            # cannot see the constraint must not enforce it.
            available="$(mem_available_gib)"
            if [[ -n "$available" ]] && (( available < need_free_gib )); then
                exec 9>&-
                memory_blocked=1
                continue
            fi
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
        # The reason the pool waited is the reason the reader needs. A wait for
        # memory and a wait for a slot are different problems, and the message
        # used to name the slots in both cases — so a machine that had a free
        # slot and too little RAM was sent looking for a busy lock.
        if (( memory_blocked == 1 )); then
            echo "gradle-slot: a slot was free, but only ${available} GiB of memory was available and a build needs ${need_free_gib} GiB — the build did not start" >&2
        else
            echo "gradle-slot: all $slot_count slots were busy for ${wait_secs}s — the build did not start" >&2
        fi
        exit "$SLOT_TIMEOUT"
    fi

    # One message, not one every five seconds: a per-iteration notice turns a
    # two-minute wait into a wall of identical lines.
    if (( announced == 0 )); then
        if (( memory_blocked == 1 )); then
            echo "gradle-slot: a slot is free, but only ${available} GiB of memory is available and a build needs ${need_free_gib} GiB — waiting (up to ${wait_secs}s)" >&2
        else
            echo "gradle-slot: all $slot_count slots are busy, waiting for a free one (up to ${wait_secs}s)" >&2
        fi
        announced=1
    fi
    sleep 5
done

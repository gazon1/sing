#!/usr/bin/env bash
#
# scripts/gradle-stop-all.sh — stop the Gradle daemons of every worktree.
#
# WHY
#
# Each worktree has its own GRADLE_USER_HOME and therefore its own daemon
# registry (ADR 2026-10-04-gradle-daemon-isolation). That isolation is the
# point — but it also means there is no single command that stops all of them:
# `<wt>/gradlew --stop` only ever reaches that worktree's own registry.
#
# With 33 worktrees and a 3 GB daemon each, the daemons of worktrees nobody is
# working in are the largest single consumer of the machine's memory. Their
# idle timeout is what eventually reclaims them — this script does it now
# instead of in three hours.
#
# NO NETWORK
#
# The daemon's Gradle version is taken from the directory name
# `daemon/<version>/`, and the client binary is the one already downloaded in
# `wrapper/dists/`. The wrapper script is deliberately NOT used: it would try to
# resolve and download the distribution the branch's wrapper points at, and
# "stop the daemons" must not be an operation that downloads a Gradle.
#
# If a version's distribution is not on disk, that version is skipped with a
# warning — the daemon it belongs to cannot be stopped by this script, and
# pretending otherwise would be a lie the caller acts on.
#
# THE SHARED ~/.gradle IS NEVER TOUCHED
#
# It is not one of the worktree homes, and it is not in the list this script
# walks. A daemon there belongs to whoever ran a bare `./gradlew` (the
# pre-commit hook used to do exactly that); stopping it would kill a build
# somebody else is running.
#
# USAGE
#
#   scripts/gradle-stop-all.sh            # stop them
#   scripts/gradle-stop-all.sh --dry-run  # list what would be stopped
#   scripts/gradle-stop-all.sh --force    # do not ask for confirmation
#
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

DRY_RUN=0
FORCE=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        --force) FORCE=1 ;;
        -h|--help)
            sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "gradle-stop-all: unknown argument '$arg' (try --dry-run, --force)" >&2
            exit 2
            ;;
    esac
done

# RSS of every JVM that looks like a Gradle or Kotlin daemon, in MiB. A
# before/after pair is the only measurement that says what the script did.
rss_of_jvms() {
    local total=0
    local pid
    while read -r pid; do
        [[ -n "$pid" ]] || continue
        local rss
        rss="$(awk '/^VmRSS:/ {print $2}' "/proc/$pid/status" 2>/dev/null || true)"
        [[ -n "$rss" ]] || continue
        total=$(( total + rss ))
    done < <(pgrep -f 'GradleDaemon|KotlinCompileDaemon' 2>/dev/null || true)
    echo $(( total / 1024 ))
}

worktree_homes() {
    # Every worktree's private home, plus this checkout's own if it is not
    # already one of them (the main checkout has one too).
    local wt
    while read -r wt; do
        [[ -n "$wt" ]] || continue
        [[ -d "$wt/.gradle-user-home" ]] || continue
        echo "$wt/.gradle-user-home"
    done < <(git worktree list --porcelain 2>/dev/null | awk '/^worktree /{print $2}')
    if [[ -d "$root/.gradle-user-home" ]]; then
        echo "$root/.gradle-user-home"
    fi
}

plan=()
while read -r home; do
    [[ -n "$home" ]] || continue
    [[ -d "$home/daemon" ]] || continue
    for version_dir in "$home"/daemon/*/; do
        [[ -d "$version_dir" ]] || continue
        version="$(basename "$version_dir")"
        gradle_bin="$(ls -d "$home"/wrapper/dists/gradle-"$version"-bin/*/gradle-"$version"/bin/gradle 2>/dev/null | head -1 || true)"
        if [[ -z "$gradle_bin" ]]; then
            echo "gradle-stop-all: Gradle $version is not downloaded in $home — skipping it (no download is attempted)" >&2
            continue
        fi
        plan+=("$home"$'\t'"$version"$'\t'"$gradle_bin")
    done
done < <(worktree_homes)

if [[ ${#plan[@]} -eq 0 ]]; then
    echo "gradle-stop-all: no worktree daemon registry found — nothing to stop"
    exit 0
fi

echo "gradle-stop-all: ${#plan[@]} daemon registr(ies) found"
before="$(rss_of_jvms)"
for entry in "${plan[@]}"; do
    IFS=$'\t' read -r home version gradle_bin <<<"$entry"
    echo "  $home (Gradle $version)"
done

if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "gradle-stop-all: dry run — nothing was stopped"
    exit 0
fi

if [[ "$FORCE" -ne 1 ]]; then
    read -r -p "Stop these daemons? [y/N] " reply
    case "$reply" in
        y|Y|yes|YES) ;;
        *)
            echo "gradle-stop-all: cancelled"
            exit 0
            ;;
    esac
fi

failed=0
for entry in "${plan[@]}"; do
    IFS=$'\t' read -r home version gradle_bin <<<"$entry"
    # GRADLE_USER_HOME points the client at THIS worktree's registry, so
    # --stop reaches exactly the daemons of that worktree and no other.
    if GRADLE_USER_HOME="$home" "$gradle_bin" --stop >/dev/null 2>&1; then
        echo "  stopped: $home (Gradle $version)"
    else
        echo "  FAILED:   $home (Gradle $version)" >&2
        failed=1
    fi
done

sleep 2
after="$(rss_of_jvms)"
echo "gradle-stop-all: JVM RSS ${before} MiB -> ${after} MiB"
exit "$failed"

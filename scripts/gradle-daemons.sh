#!/usr/bin/env bash
#
# scripts/gradle-daemons.sh — which Gradle daemons are running, and where.
#
# `ps -eo pid,etime,args | grep GradleDaemon` prints ~40 KB of JVM command
# lines (every --add-opens, every jar, every path), and `pgrep -f` is worse:
# it matches the very shell command that does the grepping. So read the
# daemon's OWN /proc/<pid>/cmdline (NUL separated) and print one short line.
#
# Usage: scripts/gradle-daemons.sh
#
set -euo pipefail

DAEMON_CLASS="org.gradle.launcher.daemon.bootstrap.GradleDaemon"
TICKS="$(getconf CLK_TCK)"
UPTIME="$(awk '{print int($1)}' /proc/uptime)"

# Field 22 of /proc/<pid>/stat is the start time in ticks since boot; the comm
# field may contain spaces, so the pid/comm prefix is cut first.
age_of() {
    local ticks s
    ticks="$(sed 's/^.*) //' <"/proc/$1/stat" | awk '{print $20}')" || return 0
    s=$((UPTIME - ticks / TICKS))
    if ((s >= 3600)); then echo "$((s / 3600))h$((s % 3600 / 60))m"
    elif ((s >= 60)); then echo "$((s / 60))m"
    else echo "${s}s"; fi
}

daemons=0
skipped=0
for pid_dir in /proc/[0-9]*; do
    pid="${pid_dir#/proc/}"
    [[ -r "$pid_dir/cmdline" ]] || continue
    args=()
    while IFS= read -r -d '' a; do args+=("$a"); done <"$pid_dir/cmdline" 2>/dev/null || true
    ((${#args[@]} > 0)) || continue
    if ! printf '%s\n' "${args[@]}" | grep -qxF "$DAEMON_CLASS"; then
        if [[ "${args[0]}" == */java ]]; then skipped=$((skipped + 1)); fi
        continue
    fi
    daemons=$((daemons + 1))
    version="?"; cp_arg=""; guh_arg=""; cache_arg=""
    for ((i = 0; i < ${#args[@]}; i++)); do
        if [[ "${args[$i]}" == "$DAEMON_CLASS" && $((i + 1)) -lt ${#args[@]} ]]; then
            version="${args[$((i + 1))]}"
        elif [[ "${args[$i]}" == "-cp" ]]; then cp_arg="${args[$((i + 1))]:-}"
        elif [[ "${args[$i]}" == "--gradle-user-home" ]]; then guh_arg="${args[$((i + 1))]:-}"
        elif [[ "${args[$i]}" == "-Dorg.gradle.projectcachedir="* ]]; then cache_arg="${args[$i]#*=}"
        fi
    done
    # The worktree owns the private Gradle home: <worktree>/.gradle-user-home/...
    # A daemon on the GLOBAL Gradle home carries no worktree at all.
    worktree="?"
    if [[ -n "$cache_arg" ]]; then worktree="$(dirname "$cache_arg")"
    elif [[ "$cp_arg" == *"/.gradle-user-home/"* ]]; then worktree="${cp_arg%%/.gradle-user-home/*}"
    elif [[ -n "$guh_arg" && "${guh_arg%/*}" != "$guh_arg" ]]; then worktree="$(dirname "$guh_arg")"
    fi
    printf '%-14s %-7s gradle %-8s %s\n' "pid:$pid" "$(age_of "$pid")" "$version" "$worktree"
done

if ((daemons == 0)); then echo "no Gradle daemons"; else echo "total Gradle daemons: $daemons"; fi
echo "skipped non-daemon JVMs: $skipped"

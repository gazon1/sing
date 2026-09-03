#!/usr/bin/env bash
#
# ram-bench.sh — JVM/Linux RAM measurement for Singularity Todo desktop app.
#
# Usage:
#   ./scripts/ram-bench.sh [pid]
#
# If no PID is given, finds the process automatically via pgrep.
#
# Metrics measured:
#   - VmRSS  — resident set size (actual physical RAM used)
#   - VmPeak  — peak RSS since process start
#   - VmSwap  — swapped-out memory
#   - Threads  — number of OS threads
#   - jcmd GC.heap_info — JVM heap usage (used / max)
#   - jcmd VM.native_memory — JVM metaspace + native overhead
#
# Run after:
#   - cold start (wait 5s after launch)
#   - idle 30s on main screen
#   - after opening AI chat screen
#   - after creating 100 tasks (stress test)

set -euo pipefail

APP_NAME="singularity-todo"
PID="${1:-$(pgrep -f "$APP_NAME" | head -1)}"

if [[ -z "$PID" ]]; then
    echo "ERROR: Process '$APP_NAME' not found. Start the app first."
    exit 1
fi

echo "=============================================="
echo " RAM Benchmark — $(date '+%Y-%m-%d %H:%M:%S')"
echo " PID: $PID"
echo "=============================================="

# --- /proc/[pid]/status ---
echo ""
echo "--- /proc/$PID/status ---"
printf "  VmRSS:   "
awk '/VmRSS/{print $2 " kB"}' /proc/$PID/status

printf "  VmPeak:  "
awk '/VmPeak/{print $2 " kB"}' /proc/$PID/status

printf "  VmSwap:  "
awk '/VmSwap/{print $2 " kB"}' /proc/$PID/status

printf "  Threads: "
ls /proc/$PID/task | wc -l | tr -d ' '

# --- pmap (detailed mapping) ---
echo ""
echo "--- pmap RSS (top 5 by RSS) ---"
pmap -x "$PID" 2>/dev/null \
    | grep -v "^total" \
    | sort -k3 -nr \
    | head -5 \
    | while read -r addr rss dirty mapping; do
        printf "  %s  %s kB  %s\n" "$rss" "$dirty" "$mapping"
      done

# --- jcmd heap info ---
echo ""
echo "--- jcmd GC.heap_info ---"
if command -v jcmd &>/dev/null; then
    jcmd "$PID" GC.heap_info 2>/dev/null | sed 's/^/  /'
else
    echo "  (jcmd not found — install JDK to enable)"
fi

# --- jcmd native memory ---
echo ""
echo "--- jcmd VM.native_memory summary ---"
if command -v jcmd &>/dev/null; then
    jcmd "$PID" VM.native_memory summary 2>/dev/null \
        | grep -E "Total:|Metaspace:|Class loading:|Internal:" \
        | sed 's/^/  /' \
        || echo "  (Native memory tracking not enabled — run with -XX:NativeMemoryTracking=summary)"
else
    echo "  (jcmd not found)"
fi

echo ""
echo "=============================================="
echo " Done."
echo "=============================================="

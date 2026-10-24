#!/usr/bin/env bash
# acp-bridge-probe.sh — the application's ACP client, against a real agent bridge.
#
# The tests talk to a fake agent: ours, taught to answer what our client expects.
# This script puts the same client against a bridge — somebody else's implementation
# of the protocol — and prints what came back. What the bridge sends that the fake
# does not is the point: a notification our adapter has never seen, a capability
# shape our model does not have, a field our fake never sends.
#
# It is a script and not a test because it needs a real `npx`, a real network and a
# real bridge. A test that cannot run is a skipped test, and this repository does
# not allow skipped tests: a class that no population selects is a class that stops
# running, silently.
#
# Usage:
#   scripts/acp-bridge-probe.sh                       # the Claude bridge
#   scripts/acp-bridge-probe.sh npx -y @agentclientprotocol/codex-acp
#
# The exit code is the verdict: zero when the client completed the handshake against
# the real bridge, one when it did not.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root" || exit 1

export TMPDIR="${TMPDIR:-$HOME/.cache/pompano-tmp}"
mkdir -p "$TMPDIR"

log="$(mktemp "$TMPDIR/acp-bridge-probe.XXXXXX.log")"
command_file="$(mktemp "$TMPDIR/acp-bridge-command.XXXXXX")"
# The command file is scratch: it goes when the script does. The log stays — its
# path is printed, and a reader who wants the frames goes and reads it.
trap 'rm -f "$command_file"' EXIT

# The bridge's command line: what the caller said, or the Claude bridge. The
# caller's arguments stay arguments — they are not joined into a string and split
# again — and they travel into a file, one per line, because the daemon's
# environment is not this shell's.
if [ "$#" -gt 0 ]; then
  bridge_args=("$@")
else
  bridge_args=(npx -y @agentclientprotocol/claude-agent-acp)
fi

# `npx` is only required when the bridge *is* an npx invocation. A bridge that is
# installed some other way does not need node, and a script that refused it would
# be a script about npx rather than about the client.
if [ "${bridge_args[0]}" = "npx" ] && ! command -v npx >/dev/null 2>&1; then
  echo "acp-bridge-probe: node is not installed — the bridge is an 'npx' invocation, so there is nothing to drive." >&2
  exit 1
fi

printf '%s\n' "${bridge_args[@]}" > "$command_file"

echo "=== the client meets a real bridge ==="
echo "bridge: ${bridge_args[*]}"
echo "log: $log"

# The probe is a program, not a test: it needs the compiled classes and the JVM
# runtime classpath, and the build is the thing that has both.
#
# Three different things can happen here, and they are three different answers: the
# build failed (nothing was probed), the probe ran and did not connect (the bridge did
# not answer), and the probe ran and connected (the verdict). Only the third is a
# success. The build's own failure and the probe's verdict are different things, and
# the task does not fail the build on a failed probe — so the exit code alone cannot
# tell them apart: the verdict is read out of the probe's own output.
./gw :shared:acpBridgeProbe -PacpBridgeFile="$command_file" --console=plain --quiet > "$log" 2>&1
rc=$?

if [ "$rc" -ne 0 ]; then
  echo "acp-bridge-probe: the probe did not run — the build failed, or the task was stopped. The log says:" >&2
  tail -40 "$log" >&2
  exit "$rc"
fi

if ! grep -q "^connected:" "$log"; then
  echo "acp-bridge-probe: the probe did not print a verdict — it did not finish. The log says:" >&2
  tail -40 "$log" >&2
  exit 1
fi

grep -E "^(bridge|working directory|connected|agent|failure|methods|frames| )" "$log" || true

if grep -q "^connected: true" "$log"; then
  echo "=== the client completed the handshake against the real bridge ==="
  exit 0
fi

echo "=== the client did not complete the handshake against the real bridge ===" >&2
exit 1

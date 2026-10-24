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

# The bridge's command line: what the caller said, or the Claude bridge.
bridge="${*:-npx -y @agentclientprotocol/claude-agent-acp}"

if ! command -v npx >/dev/null 2>&1; then
  echo "acp-bridge-probe: node is not installed — the bridge is an 'npx' invocation, so there is nothing to drive." >&2
  echo "acp-bridge-probe: log: $log" >&2
  exit 1
fi

echo "=== the client meets a real bridge ==="
echo "bridge: ${bridge}"
echo "log: $log"

# The probe is a program, not a test: it needs the compiled classes and the JVM
# runtime classpath, and the build is the thing that has both. The bridge's command
# line travels as a property, because the daemon's environment is not this shell's.
./gw :shared:acpBridgeProbe -PacpBridge="$bridge" --console=plain --quiet > "$log" 2>&1
rc=$?

# The build's own failure and the probe's verdict are different things: a build that
# did not compile says nothing about the bridge, and a probe that failed says
# everything. Both are printed, and the script's exit code is the probe's.
if [ "$rc" -ne 0 ] && ! grep -q "^connected:" "$log"; then
  echo "acp-bridge-probe: the probe did not run. The log says:" >&2
  tail -40 "$log" >&2
  exit "$rc"
fi

grep -E "^(bridge|connected|agent|failure|methods|frames| )" "$log" || true

if grep -q "^connected: true" "$log"; then
  echo "=== the client completed the handshake against the real bridge ==="
  exit 0
fi

echo "=== the client did not complete the handshake against the real bridge ===" >&2
exit 1

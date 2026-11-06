#!/usr/bin/env bash
# Keep hk.pkl's pinned hk version in lockstep with mise.toml.
#
# hk.pkl is the single source of the hook configuration; this script only
# updates the version in its `amends` line. It does not regenerate the file.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

HK_VERSION="$(grep -E '^\s*hk\s*=' mise.toml | sed -E 's/.*=\s*"([^"]+)".*/\1/')"
if [ -z "$HK_VERSION" ]; then
  echo "Could not find pinned hk version in mise.toml" >&2
  exit 1
fi

# Update the version in the amends line in place.
python3 - "$HK_VERSION" <<'PY'
import re, sys
version = sys.argv[1]
path = "hk.pkl"
text = open(path, encoding="utf-8").read()
new, count = re.subn(
    r'amends "package://github.com/jdx/hk/releases/download/v[^/]+/hk@[^#]+#/Config\.pkl"',
    f'amends "package://github.com/jdx/hk/releases/download/v{version}/hk@{version}#/Config.pkl"',
    text,
)
if count != 1:
    print(f"expected exactly one amends line, found {count}", file=sys.stderr)
    sys.exit(1)
open(path, "w", encoding="utf-8").write(new)
PY

# Verify lockstep.
if ! grep -q "hk@${HK_VERSION}#/" hk.pkl; then
  echo "hk.pkl version is not in lockstep with mise.toml (${HK_VERSION})" >&2
  exit 1
fi

echo "hk.pkl version synced to hk ${HK_VERSION}"

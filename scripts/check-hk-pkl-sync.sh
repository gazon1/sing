#!/usr/bin/env bash
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
./scripts/generate-hk-pkl.sh >/dev/null
if ! git diff --quiet -- hk.pkl; then
  echo "hk.pkl is out of sync with mise.toml. Run ./scripts/generate-hk-pkl.sh" >&2
  exit 1
fi
echo "hk.pkl is in sync"

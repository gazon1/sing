#!/usr/bin/env bash
# Keep hk.pkl version in lockstep with mise.toml
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

HK_VERSION="$(grep -E '^\s*hk\s*=' mise.toml | sed -E 's/.*=\s*"([^"]+)".*/\1/')"
if [ -z "$HK_VERSION" ]; then
  echo "Could not find pinned hk version in mise.toml" >&2
  exit 1
fi

cat > hk.pkl <<EOF
amends "package://github.com/jdx/hk/releases/download/v${HK_VERSION}/hk@${HK_VERSION}#/Config.pkl"

fail_fast = true

hooks {
  ["pre-commit"] {
    fix = false
    stash = "none"

    steps {
      ["lint-staged"] {
        check = "export PATH=/home/max/.local/share/mise/installs/npm-lint-staged/15.2.10/node_modules/.bin:$PATH; if command -v heavy >/dev/null 2>&1; then heavy --timeout 300 --on-timeout run -- lint-staged; else lint-staged; fi"
      }

      ["compile-changed"] {
        depends = "lint-staged"
        check = "if command -v heavy >/dev/null 2>&1; then heavy --timeout 300 --on-timeout run -- python3 scripts/compile-changed.py; else python3 scripts/compile-changed.py; fi"
      }
    }
  }

  ["post-checkout"] {
    fix = false
    stash = "none"

    steps {
      ["post-checkout-tasks"] {
        check = "bash scripts/hooks/post-checkout-tasks.sh"
      }
    }
  }

  ["post-merge"] {
    fix = false
    stash = "none"

    steps {
      ["post-merge-tasks"] {
        check = "bash scripts/hooks/post-merge-tasks.sh"
      }
    }
  }
}
EOF

echo "Generated hk.pkl for hk ${HK_VERSION}"
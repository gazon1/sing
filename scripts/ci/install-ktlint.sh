#!/usr/bin/env bash
# Install the pinned ktlint CLI into a directory.
#
# ktlint is a self-executing jar published as a GitHub release asset: the file
# is named `ktlint`, and running it needs a JVM. The CI job has one (the
# Gradle build needs it anyway), and the local machine's JDK is the same one
# the build uses.
#
# The version is never written out here: the pin lives in mise.toml, the file
# mise installs from. A version written in two places is a version that can
# drift, and the formatter that drifts is the one that rewrites the tree.
#
# The version argument is optional. Without it the pin is read out of
# mise.toml (scripts/toolchain-version.py), which is the only copy of it; with
# it, the caller overrides that answer on purpose.
#
# Usage:  bash scripts/ci/install-ktlint.sh <target-dir> [version]
#
# Exit code: 0 when the binary is in place, 1 when it could not be installed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "usage: $0 <target-dir> [version]" >&2
    exit 2
fi

TARGET_DIR=$1
if [ "$#" -eq 2 ]; then
    VERSION=$2
else
    VERSION="$(python3 "$ROOT/scripts/toolchain-version.py" ktlint)"
fi

# The release tag is the version without a leading "v" (the asset is named
# after the tag), so the URL and the file it downloads cannot name different
# releases.
URL="https://github.com/pinterest/ktlint/releases/download/$VERSION/ktlint"

mkdir -p "$TARGET_DIR"
echo "==> installing ktlint $VERSION into $TARGET_DIR"
if ! curl -fsSL -o "$TARGET_DIR/ktlint" "$URL"; then
    echo "ktlint $VERSION could not be downloaded from $URL" >&2
    exit 1
fi
chmod +x "$TARGET_DIR/ktlint"

# A download that produced something unrunnable is not an install. The
# self-executing jar reports its own version, so this is the check that the
# file is the tool and not an HTML error page.
if ! "$TARGET_DIR/ktlint" --version; then
    echo "the downloaded ktlint does not run — the install is not usable" >&2
    exit 1
fi

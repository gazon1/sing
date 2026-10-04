#!/usr/bin/env bash
#
# Create this worktree's private GRADLE_USER_HOME and link back to the real
# ~/.gradle for the three entries that should stay shared.
#
# Idempotent, no Gradle invocation, no network. Safe to call from a git hook.
#
# Prints the absolute path of the user home on stdout; diagnostics go to stderr,
# so `GRADLE_USER_HOME="$(scripts/setup-gradle-home.sh)"` is a valid one-liner.
#
# Why the split is here and not "private home" alone: GRADLE_USER_HOME owns the
# daemon registry (the point — this is what makes `./gradlew --stop` local), but
# it also owns the Gradle distribution, the module cache and the toolchain JDKs.
# Duplicating those per worktree costs gigabytes and minutes and isolates
# nothing: they are not written concurrently in a way that corrupts them.
#
# See docs/decisions/2026-10-04-gradle-daemon-isolation.md
#
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
guh="${root}/.gradle-user-home"

mkdir -p "$guh"

# Linked, not copied — these are read-mostly and expensive.
for shared in wrapper caches jdks; do
    target="${HOME}/.gradle/${shared}"
    link="${guh}/${shared}"
    if [[ -d "$target" && ! -e "$link" ]]; then
        ln -sfn "$target" "$link"
    elif [[ -e "$link" && ! -L "$link" ]]; then
        echo "setup-gradle-home: $link exists and is not a symlink; leaving it alone" >&2
    fi
done

# daemon/ native/ notifications/ kotlin-profile/ .tmp/ are created by Gradle on
# first use and are deliberately not linked — daemon/ holds registry.bin, which
# is the entire reason this script exists.

echo "$guh"

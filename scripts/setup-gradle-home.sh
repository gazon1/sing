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
# THE ONE EXCEPTION: the configuration cache's keystore.
#
# Gradle 9 keeps it in `caches/<version>/cc-keystore/` and opens it under a
# lock. The caches are shared on purpose, so two worktrees that share one
# `caches/` share one keystore — and the second build cannot open it:
#
#     Could not open Gradle Configuration Cache keystore.
#
# The keystore is per-Gradle-version state that two worktrees must not share,
# so it is the one thing inside `caches/` that stays private. Everything else
# in `caches/` stays a link into the shared home, so a second Gradle version's
# cache is shared the same way.
#
# See docs/decisions/2026-10-04-gradle-daemon-isolation.md
#
set -euo pipefail
shopt -s nullglob

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
guh="${root}/.gradle-user-home"

mkdir -p "$guh"

kept=0

# Links `target` at `link`, unless something is already there that is not a link.
# A real directory at `link` is left alone: it holds state this worktree owns,
# and replacing it would throw that state away.
link_entry() {
    local target="$1"
    local link="$2"
    if [[ -L "$link" ]]; then
        ln -sfn "$target" "$link"
    elif [[ -e "$link" ]]; then
        kept="$((kept + 1))"
    else
        ln -sfn "$target" "$link"
    fi
}

# Linked, not copied — these are read-mostly and expensive.
for shared in wrapper jdks; do
    target="${HOME}/.gradle/${shared}"
    link="${guh}/${shared}"
    if [[ -d "$target" ]]; then
        link_entry "$target" "$link"
    fi
done

# The caches: shared, down to the last entry, except the keystore.
#
# `caches/` itself is a real directory here, and every entry inside it is a
# link into the shared home. A Gradle version's directory is one level deeper:
# it is real too, its own entries are links, and its `cc-keystore/` is a
# private directory of its own.
shared_caches="${HOME}/.gradle/caches"
local_caches="${guh}/caches"

if [[ -d "$shared_caches" ]]; then
    if [[ -L "$local_caches" ]]; then
        # The old layout: `caches/` itself was a link to the shared one, and the
        # keystore came with it. The link goes; the shared directory it pointed
        # at is untouched.
        rm -f "$local_caches"
    fi
    mkdir -p "$local_caches"
    for entry in "${shared_caches}"/*; do
        name="$(basename "$entry")"
        link="${local_caches}/${name}"

        if [[ ! -d "$entry/cc-keystore" ]]; then
            # Not a Gradle version's cache — the module cache, the jar cache, the
            # build cache. Shared whole.
            link_entry "$entry" "$link"
            continue
        fi

        # A Gradle version's cache. The keystore inside it must not be shared,
        # so this directory is real and its own entries are links.
        if [[ -L "$link" ]]; then
            # The old layout: the whole version directory was a link to the
            # shared one, and the keystore came with it. The link goes; the
            # shared directory it pointed at is untouched.
            rm -f "$link"
        fi
        mkdir -p "$link"
        for child in "${entry}"/*; do
            child_name="$(basename "$child")"
            if [[ "$child_name" == "cc-keystore" ]]; then
                continue
            fi
            link_entry "$child" "${link}/${child_name}"
        done
        mkdir -p "${link}/cc-keystore"
    done
fi

# daemon/ native/ notifications/ kotlin-profile/ .tmp/ are created by Gradle on
# first use and are deliberately not linked — daemon/ holds registry.bin, which
# is the entire reason this script exists.

if [[ "$kept" -gt 0 ]]; then
    echo "setup-gradle-home: left $kept existing entries alone (this worktree owns them)" >&2
fi

echo "$guh"

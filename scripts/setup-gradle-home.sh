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
# The shared home owns the cache content and the local directory is a view of
# it: what a build wrote locally before the shared home had that Gradle
# version is moved into the shared home and linked back, so the next worktree
# finds it there.
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
#
# A Gradle version's directory is recognised by its NAME. It cannot be
# recognised by the keystore inside it: a version whose configuration cache has
# not run yet has no `cc-keystore/`, and linking such a directory whole would
# let the keystore land in the shared home — the failure this script exists to
# prevent.
GRADLE_VERSION_DIR_RE='^[0-9]+(\.[0-9]+)*'

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

    # The Gradle versions: the shared home's and the local one's. A version
    # that only exists locally is a version the shared home does not have yet.
    versions="$(
        {
            for entry in "${shared_caches}"/*; do
                basename "$entry"
            done
            for entry in "${local_caches}"/*; do
                basename "$entry"
            done
        } | grep -E "$GRADLE_VERSION_DIR_RE" | sort -u || true
    )"

    for name in $versions; do
        shared_version="${shared_caches}/${name}"
        local_version="${local_caches}/${name}"

        if [[ -L "$local_version" ]]; then
            # The old layout: the whole version directory was a link to the
            # shared one, and the keystore came with it. The link goes; the
            # shared directory it pointed at is untouched.
            rm -f "$local_version"
        fi
        mkdir -p "$local_version"

        # The shared home owns the cache content, and the local directory is a
        # view of it. What the local directory holds as a real directory —
        # Gradle wrote it there before the shared home had this version — is
        # moved into the shared home and linked back, so the next worktree
        # finds it there.
        if [[ ! -d "$shared_version" ]]; then
            if ! mkdir -p "$shared_version" 2>/dev/null; then
                echo "setup-gradle-home: the shared home has no ${name} and cannot be given one - it stays local" >&2
                continue
            fi
        fi
        for child in "$local_version"/*; do
            child_name="$(basename "$child")"
            if [[ "$child_name" == "cc-keystore" ]]; then
                continue
            fi
            if [[ -L "$child" ]]; then
                continue
            fi
            if [[ -e "${shared_version}/${child_name}" ]]; then
                continue
            fi
            # Moving a gigabyte across a filesystem boundary is a copy, and a
            # copy in a git hook is a hook that hangs. The content stays local
            # in that case: sharing is worth it, waiting is not.
            if [[ "$(stat -c %d "$local_version")" != "$(stat -c %d "$shared_version")" ]]; then
                echo "setup-gradle-home: ${name}/${child_name} stays local - the shared home is on another filesystem" >&2
                continue
            fi
            if ! mv "$child" "${shared_version}/${child_name}" 2>/dev/null; then
                echo "setup-gradle-home: ${name}/${child_name} could not be shared - it stays local" >&2
            fi
        done

        # Every entry of the shared version directory is a link here, and the
        # keystore is the one thing that is not: it is private, and it is
        # private even when the shared home carries a stale one.
        for child in "$shared_version"/*; do
            child_name="$(basename "$child")"
            if [[ "$child_name" == "cc-keystore" ]]; then
                continue
            fi
            link_entry "$child" "${local_version}/${child_name}"
        done
        if [[ -L "${local_version}/cc-keystore" ]]; then
            rm -f "${local_version}/cc-keystore"
        fi
        mkdir -p "${local_version}/cc-keystore"
    done

    # Everything else in `caches/` is shared whole: the module cache, the jar
    # cache, the build cache.
    for entry in "${shared_caches}"/*; do
        name="$(basename "$entry")"
        if [[ "$name" =~ $GRADLE_VERSION_DIR_RE ]]; then
            continue
        fi
        link_entry "$entry" "${local_caches}/${name}"
    done
fi

# daemon/ native/ notifications/ kotlin-profile/ .tmp/ are created by Gradle on
# first use and are deliberately not linked — daemon/ holds registry.bin, which
# is the entire reason this script exists.

if [[ "$kept" -gt 0 ]]; then
    echo "setup-gradle-home: left $kept existing entries alone (this worktree owns them)" >&2
fi

echo "$guh"

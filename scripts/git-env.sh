#!/usr/bin/env bash
# The git environment a hook exports, dropped once.
#
# Git exports the variables of the worktree the push was made from to the hook
# it runs. A command that means "the repository in this directory" must not
# inherit them: a `git config` in a test's temporary repository then writes
# into the repository the hook runs in, and this checkout is shared by ~20
# worktrees.
#
# Which variables those are is not written down here — the canon names them,
# and this file asks.
#
# The list of names lives in one place — scripts/src/sgate/gatekit/gitenv.py —
# and the answer is read from there, so the hook, the gates and the tests
# cannot disagree about it. This file carries no copy of the list: a second
# copy is a list that can drift, and the whole point of the helper is that the
# answer is written down once.
#
# The module is read by path: the tree being pushed may carry the file and not
# the package around it.

# The `env` arguments that drop the names, read once.
GATEKIT_GIT_UNSET_ARGS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && \
    python3 src/sgate/gatekit/gitenv.py --shell-unset-args 2>/dev/null || echo '')"

# Run a command without the environment git exports to a hook.
#
# The names are a list of words, and each of them is its own argument to env.
without_hook_git_env() {
    # shellcheck disable=SC2086  # the names are a list of words, and env takes them apart
    env $GATEKIT_GIT_UNSET_ARGS "$@"
}

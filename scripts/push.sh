#!/usr/bin/env bash
#
# scripts/push.sh — a push that keeps git's exit code.
#
# Usage:
#     scripts/push.sh origin <branch>
#     scripts/push.sh --force-with-lease origin <branch>
#
# Why this exists
#
# The obvious one-liner is
#
#     git push "$@" 2>&1 | tail -40
#
# and it is wrong: in a pipeline the exit code is the exit code of the LAST
# command — the reader's — so the wrapper reports success even when git
# failed. The other version, `git push ... | tail`, closes the pipe early and
# git dies of SIGPIPE, which is exit code 141 — the same 141 that a lost SSH
# connection produces, so the two failures are indistinguishable in the log.
#
# So: git's output goes to a FILE (never to a pipe), the file's exit code is
# captured immediately, the tail of the file is printed for the reader, and the
# wrapper exits with git's own code.
#
# The log path is unique per run (PID + timestamp) and printed, so the whole
# log can be read afterwards:
#
#     cat /tmp/git-push-<pid>-<timestamp>.log
#
# `set -e` is deliberately absent: it would abort the script at the failing
# `git push` before the exit code could be captured, and the wrapper would
# exit with git's code by accident rather than on purpose.
set -uo pipefail

log="/tmp/git-push-$$-$(date +%Y%m%d-%H%M%S).log"

echo "push log: $log"

git push "$@" >"$log" 2>&1
rc=$?

echo "--- last lines of $log ---"
tail -40 "$log"
echo "--- end of log ---"
echo "git push exit code: $rc"

exit "$rc"
